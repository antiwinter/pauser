# Plan: Provider folder layout + meta.json manifest

## Investigation: is fieldSpec dynamic?

All three current providers expose `getFieldsSpec` returning a **literal array of
field descriptors** with no runtime-conditional values. Searched:

```
$ grep -rn "getFieldsSpec" providers-ts/
providers-ts/providers/benchmark/index.ts:8:  async getFieldsSpec() { return [{…literal…}]; }
providers-ts/providers/catvod/provider.ts:3:  export function getFieldsSpec(): ProviderFieldSpec[] {
providers-ts/providers/emby/provider.ts:9:  export function getFieldsSpec(): ProviderFieldSpec[] {
```

Each returns one static array. Field kinds used: `singleLine`, `password`,
`proxySelector`, `qrCode`. None reference host-queried state, async data, or
runtime branch. **All static today.** Safe to lift into `meta.json`. If a
future provider needs dynamic spec, the bridge protocol keeps `getFieldsSpec`
behind a separate code path (out of scope).

## Layout

Replace flat `.js` bundles with a folder per provider:

```
app/src/main/assets/<provider>/
  index.js         ← entry; behaviour only (no manifest declarations)
  meta.json        ← static manifest — single source of truth
  <jar>.jar        ← optional, any number, auto-injected
```

`JsProviderLoader` reads top-level folders, not loose `.js` files. Entry point
is `index.js` (npm-style). Order-independent; provider dir name is the protocol
unless `meta.json` overrides it.

## meta.json schema

Required fields (all must be present; provider fails to register if any
absent or wrong type):

```jsonc
{
  "protocol":     "catvod",          // string — endpoint identifier
  "providesArt":  true,              // bool — provider yields cover art
  "version":      "1.0.0",           // string — semver or free-form
  "displayName":  "CatVod",          // string — UI label
  "fieldSpec":    [                  // array — form fields (was getFieldsSpec)
    {"id":"config_url","labelKey":"catvod.field.config_url",
     "kind":"singleLine","required":true,"identity":true,"order":0,
     "placeholderKey":"catvod.field.config_url.placeholder"}
  ]
}
```

Supported `fieldSpec.kind` values (must match Kotlin enum):
`text | singleLine | password | proxySelector | qrCode`.

Validation rules:
- All top-level fields required.
- `fieldSpec[].kind` must be in the allowed set.

Failure mode: logcat error + provider skipped. Rest of the app works.

## Auto-inject of co-located jars

`JsProviderLoader` enumerates each provider folder. Every `.jar` file in the
folder is auto-injected — no manifest declaration needed. A provider that
doesn't want a jar fused doesn't put one in the folder. Convention over
configuration.

For each `.jar` sibling, the host:
1. Copies the jar from `assets/<provider>/<jar>` to
   `code_cache/jars/asset_<safeName>.jar`.
2. Calls `ClassPathInjector.inject(ctx, stagedJar)` to splice the jar's dex
   elements into the app's `PathClassLoader.pathList.dexElements`.

`ClassPathInjector` already implements this — we're just calling it from
`JsProviderLoader` instead of from a JS-driven `loadAsset`. Shim jars
(`catvod-shim.jar`) become opaque to JS; the host wires the fusion.

## Bridge protocol (after refactor)

`index.js` exports a default object with the dynamic methods the host needs:

```js
export default {
  init(args),              // { credentials, capabilities }
  test(args),              // { credentials } → { success, fields, error? }
  listEntry(args),         // { location, startIndex, limit, options? }
  search(args),
  getEntries(args),
  getPlaybackSources(args),
  updateEntryState(args),
  resetSpiders(),
  // for qrCode fields only:
  getQr(args),
  pollQr(args),
};
```

Removed from the JS surface:
- `globalThis.insomniaProvider = { … }` — replaced by `meta.json` static fields
- `providesArt` and `getFieldsSpec` — taken from meta.json
- `host.jar.loadAsset({…})` — replaced by `meta.json` dependencies[] auto-inject

Host reads `meta.json` for the static fields; calls the JS bridge exports
for the dynamic surface. **No fallback to `globalThis.insomniaProvider`** —
if meta.json is absent or invalid, the bundle fails.

## Auto-inject of co-located jars

`JsProviderLoader` enumerates each provider folder. Every `.jar` file in the
folder is auto-injected — no manifest declaration needed. A provider that
doesn't want a jar fused doesn't put one in the folder. Convention over
configuration.

For each `.jar` sibling, the host:
1. Copies the jar from `assets/<provider>/<jar>` to
   `code_cache/jars/asset_<safeName>.jar` (per staging rules below — sandbox
   never written by the host).
2. Calls `ClassPathInjector.inject(ctx, stagedJar)` to splice the jar's dex
   elements into the app's `PathClassLoader.pathList.dexElements`.

`ClassPathInjector` already implements this — we're just calling it from
`JsProviderLoader` instead of from a JS-driven `loadAsset`. Shim jars
(`catvod-shim.jar`) become opaque to JS; the host wires the fusion.

## Jar staging (your simpler model)

Only `code_cache/jars/<safe>.jar` ever holds a staged JAR. The host never
writes into `sandbox/jars/`. Sandbox stays provider-owned and free-form.

Staging rules:

- **URL source** (`host.jar.load({source: {url: …}})`):
  download bytes → write to `code_cache/jars/<safe>.jar`.
- **Buffer source** (base64):
  decode → write to `code_cache/jars/<safe>.jar`.
- **Path source** (`{path: "foo.jar"}`):
  JS-provided path relative to `sandboxRoot`. Host hardlinks
  `sandbox/<rel>` → `code_cache/jars/<safe>.jar`. Cross-FS or hardlink
  failure falls back to a byte-copy.

`<safe>` is the handle with `:` and `/` replaced by `_` (so the filesystem
path stays a single name; DexClassLoader splits `:`-bearing paths).

`code_cache/jars/<safe>.jar` is mode `0400` and its parent dirs are
chmod-stripped of group/world write so Android 13+ DexFile accepts them.
Chmod runs once per inode — see `DexFilePermissions`.

## Code changes

### `JsProviderLoader.kt`

Replace loop over `*.js` files with loop over asset directories. Each:

```kotlin
val dirName = folder.name                                 // e.g. "catvod"
val ctx     = ContextHolder.get()
val meta    = readProviderMeta(ctx.assets, dirName) ?: return skip("missing meta.json")
validateMeta(meta, dirName)                                // throws on bad shape
injectCoLocatedJars(dirName, ctx)                          // every .jar in folder → ClassPathInjector
val source  = readIndexJs(ctx.assets, dirName)
val provider = JsProvider.create(meta, source, hostApis)   // validates meta inside
register(provider)
```

### `JsProvider.kt`

`create()` takes `(meta: ProviderMeta, indexJs: String, hostApis: HostApis)`.
Static surface (protocol, providesArt, fieldSpec, version, displayName) is
in `meta`. Dynamic surface comes from the JS exports. Build the registry from
both. Drop the bootstrap `eval(HOST_BOOTSTRAP_JS) + read providesArt +
getFieldsSpec` prelude — those are now from meta.

### `JsClient.kt`

`init` payload no longer needs `deviceInfo`-based protocol discovery; the
client already knows its protocol from `meta.protocol` via the registry.

### `JarLoader.kt`

- Drop `jarsDir` field (sandbox-staging goes away).
- Drop `loadAsset()` method and its lock — auto-inject replaces it.
- Stage from `code_cache/jars/` only. For Path sources, hardlink sandbox
  into code_cache (not the reverse).
- `LoadSource` keeps `Url` / `Path` / `Buffer` — still useful for runtime
  plugin sources that arrive inline (e.g. Telegram-style sticker JARs).

### `HostApis.handleJar`

- Drop `loadAsset` case (returns error).
- `load` / `loadClass` / `reflect` / `registerLoader` / `adoptParent`
  unchanged — they already use the handle-returning load() contract.

### `ClassPathInjector.inject(ctx, bootstrapJar)`

- Accept a path that lives in `code_cache/jars/`. The chmod dance is a
  no-op on first create but kept for defensive belt-and-braces (cheap,
  cached by inode).
- Move the actual `tmpDir = code_cache/dex/_bootstrap/...` so the dex/so
  layout matches the convention used by `loadJarFile`.

### `catvod/index.ts` (and `provider.ts`)

- Remove `globalThis.insomniaProvider = { … }`.
- Remove the `host.jar.loadAsset({ name: 'catvod-shim.jar' })` call.
- The `init()` no longer needs to set `catvod-shim.jar` state; the host
  has already fused it before `init()` runs.
- `getFieldsSpec()` becomes a single static export consumed by the build
  step that bakes `meta.json`.

### `emby/index.ts`, `benchmark/index.ts`

- Same scrubbing: no `globalThis.insomniaProvider` no-op.
- `benchmark` keeps `getQr` / `pollQr` exports because `qrCode` is a kind
  but the QR generator is dynamic.

### Build / asset merger

`app/src/main/assets/catvod.js` is removed; replaced by `app/src/main/assets/catvod/{index.js, meta.json, catvod-shim.jar}`. The Gradle asset merger copies entire directory trees under `src/main/assets/`, so the bundle format change needs zero Gradle changes — just the source layout.

`providers-ts/dist/catvod.js` is the bundled output for the existing flat file. The build step that produces `app/src/main/assets/catvod.js` from `providers-ts/dist/catvod.js` needs to:
1. Write to `app/src/main/assets/catvod/index.js` instead.
2. Generate `app/src/main/assets/catvod/meta.json` from each provider's `meta.json` source (new file in `providers-ts/providers/<name>/`).
3. Copy any `.jar` artefacts (e.g. `dist/catvod-shim.jar`) into the same folder.

### Tests

- `providers-ts/test/host-apis.js`: drop `loadAsset` from the stub.
- `providers-ts/test/categories/config.js`: drop `getFieldsSpec` call —
  field count is now validated by reading `meta.json` directly.
- `content/providers/js/src/test/.../JarLoaderPrimitivesTest.kt`: keep
  colon-safe `dexFileName` test; drop the path-warm regression test that
  relies on the deleted `jarsDir` field.
- `HostApisSandboxTest`: drop any `loadAsset` enumeration.

## Order of operations

1. Move assets (`catvod.js` → `catvod/index.js`) and update copy/build scripts.
2. Add `providers-ts/providers/catvod/meta.json`, `emby/meta.json`,
   `benchmark/meta.json`. Validate schema parser in TS.
3. Drop `loadAsset` from JS bundles; drop `globalThis.insomniaProvider`
   preamble.
4. Refactor `JsProviderLoader` to folder-iteration; add meta.json parse +
   validation in Kotlin.
5. Simplify `JarLoader.kt` (drop `jarsDir`, `loadAsset`, hardlink-to-sandbox).
6. Update tests.

## Open questions deferred

- Should the host expose per-provider version info to the UI ("CatVod v1.2.3"
  in settings)? Out of scope — `version` is just a required field for now.
- Should `index.js` be required to exist in the folder, or is a jar-only
  folder valid (e.g. a "library" bundle)? Currently every folder is
  treated as a provider that needs `index.js` + `meta.json`; a stray
  jar-only folder will fail validation. Out of scope — keep strict.
