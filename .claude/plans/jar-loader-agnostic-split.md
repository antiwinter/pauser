# Plan: split `JarLoader.kt` — generic Kotlin core + catvod recipe in TS

## Goal

Make `JarLoader.kt` agnostic to catvod mechanics and tricks (rule #1 of `AGENTS.md`). Catvod-specific orchestration — MD5 manifest verification, `Init`/`DexNative`/`InitOrigin` class names, the secondary-loader dance — moves to `providers-ts/providers/catvod/`. Kotlin becomes a generic Android-dex loader that any dex JAR consumer can use without inheriting catvod-shaped assumptions. The cache lifecycle (download, integrity check, hand-off) lives in TS via `host.fs.{read,exists,delete}` + `host.crypto.checksum`; `host.jar.load` accepts a `source: { url } | { path } | { buffer }` so providers can do their own preprocessing before handing bytes to Kotlin. All `host.fs.*` and `host.jar.load({ source: { path, buffer } })` calls run inside a per-provider sandbox at `cacheDir/providers/<provider-name>/`, enforced by a `resolve()` helper in `HostApis` — providers cannot read or write outside their own folder, and removing a provider is a recursive delete of its sandbox.

Concrete deliverable: a second dex-JAR consumer (the Telegram shim per `.claude/plans/telegram-provider.md` lines 100–115, or any future `csp_*`-style provider) can write its JAR to a path and call `host.jar.load({ source: { path } })` without going through `catvodBoot` and without MD5 verification leaking into Kotlin. Memory cost: < 5 MB heap regardless of JAR size.

## Principle (rule #1, restated)

Kotlin knows Android dex plumbing. Catvod knows catvod mechanics. Anything that requires knowing catvod's class names, its MD5 manifest format, or its process-global native boot belongs in TS.

## Current state

`JarLoader.kt` (440 lines) mixes generic Android-dex plumbing with catvod mechanics:

| Lines | Content | Owner today | Should be |
|---|---|---|---|
| 348–367 | `downloadAndVerify` (URL→filename + MD5 check) | Kotlin | **TS** (provider downloads via `host.jar.load({ source: { url } })`, verifies via `host.fs.read` + `host.crypto.checksum`) |
| 436–440 | `File.md5hex()` extension | Kotlin | **delete** — `host.crypto.checksum({ encoding: 'base64', algo: 'md5' })` covers it |
| 100–202 | `boot` / `bootInternal` (Init.init → poll `loader()` → patch parent → InitOrigin.init) | Kotlin | **TS** |
| 54, 120–143 | `bootFutures` (per-URL boot serialization) | Kotlin | **delete** — `spider/jar.ts` already has `jarBootPromise` |
| 85–98 | `loadJarFile` (DexClassLoader instantiation, extractNativeLibs) | Kotlin | **stay** (generic) |
| 67–83 | `loadAsset` + `ClassPathInjector.inject` | Kotlin | **stay** (generic — Tinker-style dex element merge) |
| 209–261 | `reflect` | Kotlin | **stay** (generic; the one-line asset-key fallback from the Telegram plan applies here too) |
| 270–309 | `invokeStreaming` | Kotlin | **stay** (generic) |
| 311–328, 386–425 | `buildArgs` / `resolveMethod` / `jsonToAny` / `convertToParam` | Kotlin | **stay** (generic JSON↔JVM marshalling) |
| 429–433 | `urlKey` (SHA-256 of URL) | Kotlin | **stay** (generic) |
| 333–344 | `clear` / `clearInstances` | Kotlin | **stay** (generic) |
| — | `load(url, md5?)` accepts URL + optional MD5 | Kotlin | **TS** — generalised to `load(source: LoadSource)` where `LoadSource` is `Url` / `Path` / `Buffer` (sealed class) |
| — | `host.fs.write({ path, content })` only | TS | **extend** — add `read({ path, encoding? })`, `exists({ path })`, `delete({ path })` |

Both layers serialize the boot race today (`bootFutures` in Kotlin, `jarBootPromise` in TS) — duplicated defense that vanishes once orchestration moves.

---

## Unified surface — `host.crypto.checksum` (string | base64), `host.fs` (read/exists/delete/write), `host.jar.load` (source = url | path | buffer)

Per rule #1, Kotlin stays hash-agnostic and file-agnostic. TS owns the integrity protocol and the cache lifecycle. Memory efficiency for hundreds-of-MB JARs flows from `host.jar.load` accepting a **path** (Kotlin streams the file, no JS-side allocation).

### `host.crypto.checksum({ input, algo, encoding? })` — replaces `host.crypto.sha256`

Single primitive. Replaces today's `host.crypto.sha256`. Default `encoding: 'utf8'` keeps every existing string-hash caller working unchanged:

```ts
crypto: {
  checksum(args: {
    input: string;
    algo: 'md5' | 'sha-1' | 'sha-256' | 'sha-512';
    encoding?: 'utf8' | 'base64';   // default 'utf8'
  }): Promise<string>;                // lowercase hex
}
```

Kotlin impl: `MessageDigest.getInstance(algo).digest(decode(input, encoding)).joinToString("") { "%02x".format(it) }` where `decode("utf8") = input.toByteArray(Charsets.UTF_8)` and `decode("base64") = Base64.decode(input, Base64.DEFAULT)`. Unknown `algo` throws `IllegalArgumentException`. Unknown `encoding` throws.

Lowercase-hex return contract preserved — emby's Room primary keys (`"emby_${sha256(baseUrl + userId)}"`) keep producing the same hex string after the rename.

Migration of existing `host.crypto.sha256` callers (catvod `providers-ts/providers/catvod/config.ts:45`, `research/fty/plan-catvod-provider.md:474`, `research/fty/plan-m3u-provider.md:226`, `research/fty/refactor_backend_js.plan.md:201`): `host.crypto.sha256({ input })` → `host.crypto.checksum({ input, algo: 'sha-256' })`. Return contract preserved.

### `host.fs` — add `read`, `exists`, `delete`; keep `write`

```ts
fs: {
  write(args: { path: string; content: string }): Promise<string>;   // existing
  read(args: { path: string; encoding?: 'utf8' | 'base64' }): Promise<string>;  // default 'utf8'
  exists(args: { path: string }): Promise<boolean>;
  delete(args: { path: string }): Promise<boolean>;                   // true if removed
}
```

`read` with default `'utf8'` is for JSON dumps and config files (catvod/emby). `'base64'` is for binary (used by `host.crypto.checksum` over file bytes). Kotlin impl streams the file through the encoder — for a 100 MB JAR that's 100 MB on disk + 134 MB base64 string in JS heap, then a `Base64.decode` on Kotlin side. The MD5 verification step is the only place this happens, and the cost is acceptable since the result is one `delete` + one retry on mismatch. The load itself is the memory-critical path and stays path-based.

`exists` and `delete` are basic primitives — any provider doing cache work needs them.

### Per-provider sandbox — `cacheDir/providers/<provider-name>/`

Every provider type gets its own root. The host API resolves all relative paths against this root and rejects anything that escapes:
- **`host.fs.{read,write,exists,delete}({ path })`** — `path` is relative. The runtime strips any leading `/`, rejects `..` segments, and resolves against `cacheDir/providers/<provider-name>/`. The returned path on disk is `cacheDir/providers/<provider-name>/<path>`. Symlinks that escape the sandbox are rejected by `File.canonicalPath.startsWith(sandboxRoot)`.
- **`host.jar.load({ source: { path } })`** — same resolution. Provider writes its JAR (after preprocessing) to e.g. `host.fs.write({ path: 'spider.jar', content: ... })`, then calls `host.jar.load({ source: { path: 'spider.jar' } })`.
- **`host.jar.load({ source: { url } })`** — Kotlin writes the downloaded JAR to `cacheDir/providers/<provider-name>/jars/<urlKey(url)>.jar`. Per-provider cache; two providers loading the same URL have separate cache entries (tens of MB at most; not worth deduplicating).
- **`host.jar.load({ source: { buffer } })`** — cache key is **`sha256(buffer)`**. Auto-keying keeps the API terse; the caller doesn't need to specify a name. The JAR is written to `cacheDir/providers/<provider-name>/jars/<sha256(buffer)>.jar`. Idempotent across reloads of identical bytes.
Kotlin plumbing changes:

- **`HostApis(providerName: String, sandboxRoot: File)`** — new constructor. `providerName` is the JS bundle's identity (today `assetPath.removeSuffix(".js")`, e.g. `"catvod"`, `"emby"`; tomorrow a bundle-declared string if we ever split name from filename). Each `JsClient` constructs its own `HostApis` in `createClient(...)` and passes it to its `QuickJsEngine`. `HostApis` exposes a single private `resolve(path: String): File` helper used by `handleFs` and `handleJar`. Sandbox violations throw `SecurityException`.
- **`JarLoader(sandboxRoot: File, httpClient: OkHttpClient)`** — JAR cache moves under the sandbox. `loadJarFile` writes `cacheDir/providers/<provider-name>/dex/<key>/<gen>/` (was `codeCacheDir/dex/<key>/<gen>/` — keep codeCacheDir for dex output since it's cleared by the OS, but move JAR cache to sandbox). `download(url)` writes to `cacheDir/providers/<provider-name>/jars/`.
- **`EngineHostApis`** — unchanged.
- **`JsProvider.createClient(values)`** — constructs `HostApis(providerName = assetPath.removeSuffix(".js"), sandboxRoot = File(ContextHolder.get().cacheDir, "providers/$providerName"))`. Each `JsClient` gets its own `HostApis` (no longer shared).
- **`InsomniaApplication.debugJarBridge`** — keep `HostApis(providerName = "debug", sandboxRoot = File(cacheDir, "providers/debug"))` so the debug bridge has its own sandbox too.

Why **provider-name**, not `protocol`: today the two are equal (`protocol = assetPath.removeSuffix(".js")` in `JsProvider.kt:31`), but they mean different things. `protocol` is the wire identifier for catalog navigation and provider registry lookup — it's a routing key. `providerName` is the **owner of local state** (JARs, dumps, caches, configs). The sandbox holds state, not protocol data, so the path should reflect ownership, not routing. Using `providerName` keeps the mental model clean if a future bundle declares its own name (e.g. `name = "CatVod"`) — the sandbox would track that, while `protocol` stays as the wire identifier.

Why provider-name-scoped (not endpoint-scoped): two endpoints of the same provider share the same code and threat model; isolating them adds bookkeeping for no security gain. If a future need arises (e.g. multi-tenant catvod configs), promote `providerName` from `assetPath.removeSuffix(".js")` to `sha256(providerName + sorted(values))`.

### `host.jar.load` — accept a source, drop `md5`

```ts
jar: {
  load(args: {
    source:
      | { url: string }      // Kotlin downloads to cacheDir/providers/<provider-name>/jars/<urlKey(url)>.jar, loads. No MD5.
      | { path: string }     // Kotlin streams file at path into DexClassLoader. Memory-efficient.
      | { buffer: string };  // base64-encoded bytes. Kotlin decodes, writes to cacheDir/providers/<provider-name>/jars/<sha256(buffer)>.jar, loads.
  }): Promise<void>;
}
```

Three modes, picked by discriminated union:
- **`{ url }`** — convenience for the simple case. Same flow as today minus the MD5 check (caller does it via `host.fs.read` + `host.crypto.checksum` if needed). Kotlin downloads to `cacheDir/providers/<provider-name>/jars/<urlKey(url)>.jar`, creates DexClassLoader.
- **`{ path }`** — memory-efficient for large JARs. Provider did its own preprocessing (download, extract, jpeg-trick, MD5 check) and saved to a relative path inside its sandbox. Kotlin resolves the path against the sandbox root, streams the file into DexClassLoader. If the resolved file is already in the dex-output dir, point DexClassLoader directly at it; otherwise stream-copy. O(1) heap.
- **`{ buffer }`** — synthetic / small JARs (test fixtures, derived bytes). Kotlin base64-decodes the buffer, writes to `cacheDir/providers/<provider-name>/jars/<sha256(buffer)>.jar`, creates DexClassLoader. Idempotent for same buffer content (cache key = content hash).

No `checksum` in `host.jar` — MD5/SHA verification lives in `host.crypto.checksum` over file bytes read via `host.fs.read`.

---

## New Kotlin surface (post-refactor)

`host.jar` after the refactor — `load` is the only primitive that changed shape; the rest are new or unchanged:

```ts
jar: {
  load(args: {
    source:
      | { url: string }
      | { path: string }
      | { buffer: string };
  }): Promise<void>;
  loadAsset(args: { name: string }): Promise<void>;
  preload(args: { url: string, cls: string }): Promise<void>;
  awaitSecondaryLoader(args: {
    url: string,
    cls: string,
    method: string,
    timeoutMs?: number,
  }): Promise<string>;
  adoptParent(args: { childKey: string, parentKey: string }): Promise<void>;
  reflect(args: { /* unchanged */ }): Promise<string>;
  invokeStreaming(args: { /* unchanged */ }): Promise<...>;
  clear(args?: null): Promise<void>;
  clearInstances(args?: null): Promise<void>;
};
```

Rationale for each new primitive — every one is generic on its own:

- **`preload({ url, cls })`** — `loaders[urlKey(url)]?.loadClass(cls)`. Triggers the class's `<clinit>`. Used to force-load `DexNative` so its `<clinit>` extracts the bundled `.so`. Generic: any dex JAR with a class whose `<clinit>` does side-effect work benefits.
- **`awaitSecondaryLoader({ url, cls, method, timeoutMs? })`** — polls `cls.method()` (a static 0-arg method) every 50ms up to `timeoutMs` (default 1500), until the result is a `ClassLoader`. Stores the returned loader under `secondary:<urlKey>` so subsequent `reflect` calls find it. Returns the secondary key. Generic: any native-dex JAR that spawns a secondary loader via a static `loader()` getter uses the same pattern. (FongMi uses it; spider-native init flows use it.)
- **`adoptParent({ childKey, parentKey })`** — sets `ClassLoader.parent` via reflection. `parentKey: "context"` is a special value that means `ContextHolder.get().classLoader`. Generic Android plumbing — needed because `DexClassLoader` doesn't expose a public parent setter.

For `host.jar.load`'s three source modes:
- **`{ url }`** reuses the existing `download(url)` path: write to `cacheDir/providers/<provider-name>/jars/<urlKey(url)>.jar`, then DexClassLoader. No MD5.
- **`{ path }`** resolves the path against the sandbox root. If the resolved file is already in the dex-output dir (`codeCacheDir/dex/...`), point DexClassLoader directly at it (no copy). Otherwise stream-copy into cache. O(1) heap.
- **`{ buffer }`** decodes base64, writes to `cacheDir/providers/<provider-name>/jars/<sha256(buffer)>.jar`, loads.

---

## New TS catvod orchestration

`spider/jar.ts` owns the boot dance end-to-end:

```ts
const CATVOD_INIT         = 'com.github.catvod.spider.Init';
const CATVOD_DEX_NATIVE   = 'com.github.catvod.spider.DexNative';
const CATVOD_INIT_ORIGIN  = 'com.github.catvod.spider.InitOrigin';
const CATVOD_SHIM_ASSET   = 'catvod-shim.jar';

export async function ensureJar(jarUrl: string, md5?: string): Promise<void> {
  if (jarBootPromise) return jarBootPromise;
  jarBootPromise = (async () => {
    await host.jar.loadAsset({ name: CATVOD_SHIM_ASSET });

    // Provider owns the cache lifecycle: download, integrity check, hand off.
    // Kotlin's load just streams the file — memory-efficient for hundreds-of-MB JARs.
    // All paths below are sandboxed under cacheDir/providers/catvod/ by HostApis.
    const cachePath = `jars/${urlKey(jarUrl)}.jar`;
    if (!(await host.fs.exists({ path: cachePath }))) {
      await host.jar.load({ source: { url: jarUrl } });           // convenience download + load
    } else {
      if (md5) {
        const b64 = await host.fs.read({ path: cachePath, encoding: 'base64' });
        const actual = await host.crypto.checksum({ input: b64, encoding: 'base64', algo: 'md5' });
        if (actual !== md5) {
          await host.fs.delete({ path: cachePath });
          throw new Error(`JAR MD5 mismatch (${jarUrl}): expected=${md5} actual=${actual}`);
        }
      }
      await host.jar.load({ source: { path: cachePath } });        // re-load from disk cache
    }

    // CatVod boot dance, expressed as primitives + catvod class names.
    await host.jar.preload({ url: jarUrl, cls: CATVOD_DEX_NATIVE });
    await host.jar.reflect({ url: jarUrl, cls: CATVOD_INIT,      method: 'init',   args: [] });
    const secondaryKey = await host.jar.awaitSecondaryLoader({
      url: jarUrl, cls: CATVOD_INIT, method: 'loader', timeoutMs: 2000,
    });
    await host.jar.adoptParent({ childKey: secondaryKey, parentKey: 'context' });
    await host.jar.reflect({ url: jarUrl, cls: CATVOD_INIT_ORIGIN, method: 'init', args: [] });

    const reg = await host.relay.register({
      cls: 'com.github.catvod.spider.Proxy', method: 'proxy', token: 'catvod',
    });
    relayBaseUrl = reg.baseUrl;
  })();
  try { await jarBootPromise; }
  catch (e) { jarBootPromise = null; throw e; }
}
```

The flow above keeps the `{ url }` mode as a one-call convenience for the "no cache yet" path, but the warm path is `{ path }` — Kotlin re-reads the same cached file without going through HTTP. Future preprocessing (jpeg-trick, partial extraction, custom manifest) slots in between `host.fs.exists` and `host.jar.load`.

`loadSpider`, `createJarSpider`, `resetSpiders`, `init` — unchanged. The constants (`CATVOD_*`) move from current `host.jar.boot` param names into TS data — they never cross into Kotlin now.

---

## Files to change

### Kotlin

**`content/providers/js/src/main/java/com/insomnia/provider/js/JarLoader.kt`** — strip catvod mechanics + generalise `load`:
- Delete `md5hex()` extension (436–440).
- Delete `downloadAndVerify()` (348–367). Replace with a generic `download(url: String): File` that fetches via `OkHttpClient`, writes to `File(sandboxRoot, "jars/${urlKey(url)}.jar")` (sandbox-rooted; no more `ContextHolder.get().cacheDir` lookup at the JAR layer — the sandbox is passed in). No MD5 check. No `setReadOnly()` (no-op on internal storage).
- Replace `load(url, md5?)` with `load(source: LoadSource)` where `LoadSource` is a sealed class: `LoadSource.Url(url)`, `LoadSource.Path(path)`, `LoadSource.Buffer(bufferB64)`. Body dispatches on the variant:
  - `Url(url)` → `download(url)` then `loadJarFile(...)`.
  - `Path(path)` → resolve against sandbox root via the `HostApis.resolve()` helper (Kotlin gets an already-validated absolute path); if already under sandbox, point DexClassLoader directly; else stream-copy to `sandboxRoot/jars/${sha256Bytes(path.toByteArray())}.jar` and load. Stream-copy is O(1) heap.
  - `Buffer(b64)` → `Base64.decode(b64)`, write to `sandboxRoot/jars/${sha256(buffer)}.jar`, load. For small/synthetic JARs.
- Add `fun preload(url: String, cls: String)` — looks up the primary loader by `urlKey(url)` and calls `loadClass(cls)`. Returns Unit.
- Add `fun awaitSecondaryLoader(url: String, cls: String, method: String, timeoutMs: Long = 1500): String` — finds the loader for `urlKey(url)`, polls `cls.method()` static 0-arg until result is a `ClassLoader` or timeout. On success, stores under `"secondary:${urlKey(url)}"` in `loaders` and returns the key. Throws on timeout.
- Add `fun adoptParent(childKey: String, parentKey: String)` — looks up child loader by key, sets its `parent` field via `ClassLoader::class.java.getDeclaredField("parent")`. `parentKey == "context"` → `ContextHolder.get().classLoader`; otherwise look up `loaders[parentKey]`.
- Delete `bootFutures` map (54), `boot` (58–65 entry), `boot` / `bootInternal` (100–202).
- Keep `loadJarFile`, `extractNativeLibs`, `loadAsset`, `reflect`, `invokeStreaming`, `urlKey`, `clear`, `clearInstances`, `buildArgs`, `resolveMethod`, `jsonToAny`, `convertToParam`, `tryLoadClass`, `loaders`, `instances`, `keyGen`, `loadLocks`, `loadGen`, `convertToParam` — unchanged.

**`content/providers/js/src/main/java/com/insomnia/provider/js/HostApis.kt`**:

- **Constructor change:** `class HostApis(providerName: String, sandboxRoot: File)`. The no-arg constructor is removed. Every existing `HostApis()` call site must be updated. `providerName` is the bundle-derived name today (see the rationale in the sandbox section above).
- **Private helper:** `private fun resolve(path: String): File` — strips leading `/`, rejects `..` segments, resolves against `sandboxRoot`, and asserts `file.canonicalPath.startsWith(sandboxRoot.canonicalPath)` as defense-in-depth (catches symlink escapes). Throws `SecurityException` on violation.

`handleJar`:
- `load`: parse `source` as a discriminated union (`{ url }` / `{ path }` / `{ buffer }`); call `jarLoader.load(source)`. Reject if zero or more than one variant is present.
- Add `preload`, `awaitSecondaryLoader`, `adoptParent` branches.
- Drop the `checksum` branch (moved to `host.crypto`).
- Keep `loadAsset`, `reflect`, `clear`, `clearInstances` — unchanged.

`handleCrypto`:
- Replace the `sha256` branch with a `checksum` branch that takes `{ input, algo, encoding? }`. Default `encoding = "utf8"`. Kotlin impl: `MessageDigest.getInstance(algo).digest(decode(input, encoding)).joinToString("") { "%02x".format(it) }`. Throws `IllegalArgumentException` on unknown `algo` or `encoding`.
- Keep the lowercase-hex return contract.

`handleFs`:
- Add `read` branch: parse `{ path, encoding? }` (default `'utf8'`). Resolve path via `resolve(path)`. Stream file via `FileInputStream` into a `ByteArrayOutputStream`, then either return as UTF-8 string or Base64-encode. For very large files the caller is responsible for keeping the load off the path (MD5 of a 500 MB JAR still allocates 670 MB of base64 — acceptable for the integrity-check step).
- Add `exists` branch: `resolve(path).exists()`.
- Add `delete` branch: `resolve(path).delete()`.
- Existing `write` branch unchanged in signature; the body now resolves the path via `resolve(path)` before writing.

**`content/providers/js/src/main/java/com/insomnia/provider/js/JarLoader.kt`** — additional change beyond the load() rewrite above:
- **Constructor change:** `class JarLoader(sandboxRoot: File, httpClient: OkHttpClient)`. JAR cache moves under the sandbox root. The shared `JarLoader` instance in `InsomniaApplication.debugJarLoader` becomes `JarLoader(File(cacheDir, "providers/debug"), OkHttpClient())` — debug JARs land under their own sandbox.
- Add `preload({ url: string, cls: string }): Promise<void>`.
- Add `awaitSecondaryLoader({ url: string, cls: string, method: string, timeoutMs?: number }): Promise<string>`.
- Add `adoptParent({ childKey: string, parentKey: string }): Promise<void>`.
- Drop `checksum(...)` (moved to `host.crypto`).
- Keep `loadAsset`, `reflect`, `clear`, `clearInstances` — unchanged signatures.

**`providers-ts/utils/types.ts`** — `HostAPI.crypto`:
- Replace `sha256({ input })` with `checksum({ input, algo, encoding? })`. Default `encoding: 'utf8'`. Returns lowercase hex.

**`providers-ts/utils/types.ts`** — `HostAPI.fs`:
- Add `read({ path: string, encoding?: 'utf8' | 'base64' }): Promise<string>` (default `'utf8'`).
- Add `exists({ path: string }): Promise<boolean>`.
- Add `delete({ path: string }): Promise<boolean>`.
- Keep `write({ path: string, content: string })` unchanged.

**`providers-ts/providers/catvod/spider/jar.ts`** — replace `ensureJar` body with the orchestration shown above. Constants `CATVOD_INIT`/`CATVOD_DEX_NATIVE`/`CATVOD_INIT_ORIGIN`/`CATVOD_SHIM_ASSET` move from `host.jar.boot` param names into TS-only data.

**`providers-ts/test/host-apis.js`** — `handleJarStub`:
- `load` stub: switch from `{ url, md5 }` to `{ source: { url } | { path } | { buffer } }`. Pre-populate the chosen variant in the test fixture.
- Add stub for `preload` (return `true`), `awaitSecondaryLoader` (return `'stub-secondary'`), `adoptParent` (return `true`).
- Drop the `checksum` stub.

**`providers-ts/test/host-apis.js`** — `handleCryptoStub`:
- Replace `sha256` with `checksum({ input, algo, encoding })` returning the same lowercase hex. Default `encoding` to `'utf8'` in the stub.

**`providers-ts/test/host-apis.js`** — `handleFsStub`:
- Add `read` (return fixture content as string or base64), `exists` (return `true`), `delete` (return `true`).

### Update cross-references

- **`providers-ts/providers/catvod/index.ts:91`** — `resetJarSpiders(jar?.url, jar?.md5)` still passes md5, but `resetSpiders` ignores it after the refactor (md5 verification lives in `ensureJar`). Drop the md5 param from `resetSpiders` too.
- Any debug route smoke test or doc referencing `host.jar.boot` — update or remove.

---

## Phased delivery

### Phase A — `host.jar.load(source)` shape + `host.fs.{read,exists,delete}` + crypto unify (low risk, immediate win)

**Status: done.** All 8 sub-steps executed. Sandbox plumbing wired through `JsProviderLoader` and `InsomniaApplication` (debug). `JarLoader.kt` carries zero catvod-specific strings. `host.crypto.checksum` replaces `host.crypto.sha256`. Unit tests: 10 in `HostApisSandboxTest`. Smoke: `:app:compileDebugKotlin` clean; bundle build warning-free.

### Phase B — Boot orchestration move (full rule #1 satisfaction)

**Status: done.** `JarLoader.kt` exposes three generic primitives: `loadClass` (force class load — runs `<clinit>`), `registerLoader` (register an instance handle as a named loader), and `adoptParent` (patch `ClassLoader.parent` with `parentKey: "context"` for the app classloader). `awaitStatic` was removed — its polling moved to TS where the catvod-specific timing (50 ms / 5 s) belongs; the method call uses existing `reflect`, the loader registration uses `registerLoader`. `boot`/`bootInternal`/`bootFutures` deleted. `spider/jar.ts` boot sequence: `loadClass` → `reflect(Init.init)` → poll `reflect(Init.loader)` → `registerLoader` → `adoptParent`. Unit tests: 4 in `JarLoaderPrimitivesTest` (error paths). Smoke: TS build warning-free; Kotlin compile + tests clean.
### Phase C — Cleanup (after both phases pass smoke tests)

**Status: done.**

- `JarLoader.kt` — removed `jsonPrimitive` import (unused after Phase A refactor). `MessageDigest` retained — `SHA-256` is still used by `urlKey()`.
- `HostApis.kt` — removed `booleanOrNull` import (unused after Phase A refactor).
- `.claude/plans/catvod-provider.md` and `research/fty/plan-host-load-jar.md` document the **pre-refactor** API shape (`host.jar.load({ url, md5 })` etc.) and are now superseded by this plan. They are kept for history; the active contract lives in `providers-ts/utils/types.ts` and the implementation in `content/providers/js/src/main/java/com/insomnia/provider/js/`.
- The `host.fs.read` cost is documented inline at the catvod call site (`providers-ts/providers/catvod/spider/jar.ts:52`) and in the `host.jar.load` JSDoc (`providers-ts/utils/types.ts`). Catvod's MD5 check is the only consumer of the 134% heap delta; future providers that don't need MD5 verification should use the `{ source: { url } }` mode (Kotlin streams the bytes) or `{ source: { path } }` (Kotlin streams the file from disk).
- Per-provider sandbox cleanup on endpoint removal is **deferred**. The sandbox (`cacheDir/providers/<provider-name>/`) is provider-name-scoped; if two endpoints share the same provider, they share the folder. Adding refcounted cleanup is a follow-up — for now, the sandbox lingers after endpoint removal (bounded by downloads — a few hundred MB max). Users clear app data to wipe. This matches today's behavior for the shared JAR cache.
- Optional follow-up (not in this PR): a debug route `DELETE /providers/<provider-name>/cache` for power-user "wipe this provider's local state" — just `sandboxRoot.deleteRecursively()`. Easy to add later if needed.
- `loadClass` (originally `forceInit`) mirrors `Class.forName(cls, true, loader)`. `awaitStatic` was removed as catvod-shaped — it bundled polling (catvod's async `Init.init()` pattern), method invocation (generic, already covered by `reflect`), and auto-registration (catvod-specific heuristic) into one primitive. The polling moved to TS; the registration became `registerLoader`. `adoptParent` kept — the operation (patch `ClassLoader.parent`) is generic even if only catvod calls it today.

---

## Acceptance

Kotlin-side proof:
- `grep -n -i 'md5\|Init\|DexNative\|InitOrigin\|catvod' content/providers/js/src/main/java/com/insomnia/provider/js/JarLoader.kt` returns no matches.
- `JarLoader.kt` is 352 lines. The 3 generic primitives (`loadClass`, `registerLoader`, `adoptParent`) replaced catvod's `boot`/`bootInternal`/`bootFutures`. Catvod-specific lines are zero; the file is fully agnostic.
- `host.jar.load` in `types.ts` accepts `source: { url } | { path } | { buffer }` — no `md5` field anywhere.
- `host.jar.boot` does not exist in `types.ts` or `HostApis.handleJar`.
- `host.crypto.sha256` does not exist in `types.ts`, `HostApis.handleCrypto`, or any TS caller — replaced by `host.crypto.checksum({ input, algo, encoding? })`. Default `encoding: 'utf8'`. Return contract preserved (lowercase hex).
- `host.fs` in `types.ts` exposes `read({ path, encoding? })`, `exists({ path })`, `delete({ path })`, `write({ path, content })`.
- `grep -nE 'sha256\(' providers-ts research` returns only matches that explicitly mention the algo string (`algo: 'sha-256'`) — no orphaned bare `sha256(` callers left.
- **Sandbox:** `HostApis(providerName, sandboxRoot)` constructor exists and is the only constructor (no no-arg). `JarLoader(sandboxRoot, httpClient)` constructor exists. Unit tests: 10 in `HostApisSandboxTest` (write/read/exists/delete + encoding round-trips + checksum parity + `..` rejection + absolute-path normalization) and 4 in `JarLoaderPrimitivesTest` (loadClass/registerLoader/adoptParent error paths) — 14/14 passing. The catvod and emby endpoints get their own folders under `cacheDir/providers/<provider-name>/`; renaming `catvod.js` to `clapper.js` would create a fresh sandbox and the old `cacheDir/providers/catvod/` lingers (deferred cleanup, see Phase C note below).
TS-side proof:
- `grep -n 'CATVOD_' providers-ts/providers/catvod/spider/jar.ts` shows the constants declared but never crossing into a `host.jar.*` argument that names them (only as values for `cls:`, `name:`).
- A type 3 (`csp_*`) site loads, browses (`homeContent`, `categoryContent`), shows detail (`detailContent`), and plays (`playerContent`) end-to-end.
- Concurrent `ensureJar` calls share one promise; failed boot clears `jarBootPromise` and is retryable.
- The Telegram shim per `telegram-provider.md` (when implemented) can use `loadAsset + reflect` (or `load({ source: { path } })` after writing its own bytes) without ever invoking `catvodBoot` or `checksum`.
- An emby Room primary-key round-trip test confirms `host.crypto.checksum({ input, algo: 'sha-256' })` produces the same hex string as the previous `host.crypto.sha256({ input })` call.
- Memory smoke test (instrumented Android run): `host.jar.load({ source: { path: '<200 MB file>' } })` heap delta ≤ 5 MB; `host.fs.read({ path, encoding: 'base64' })` heap delta ≈ 134 % of file size (documented cost of MD5 verification — same shape as today's `downloadAndVerify` which already allocates the bytes for the digest).

---

## Risk and rollback

- **Phase A risk: low.** Removing MD5 verification from Kotlin is a one-step swap; the TS-side checksum is a direct functional replacement. If checksum primitive has a bug, `ensureJar` throws on every load and the catvod provider refuses to initialize — visible immediately, easy to revert by re-adding the MD5 check inside `downloadAndVerify`. Memory note: `host.fs.read({ encoding: 'base64' })` allocates ~134 % of the file size in JS heap — same cost as today's `downloadAndVerify` (which buffers the whole JAR for the digest). The load itself is path-mode and stays O(1).
- **Phase B risk: medium.** The boot dance is delicate — `awaitSecondaryLoader` must poll at the right cadence, `adoptParent` must run after the secondary loader is created but before `InitOrigin.init` runs. A wrong primitive composition corrupts the secondary loader's `Context` reference (the original `bootFutures` comment explains why this is process-global). Mitigations: keep the existing `boot` Kotlin function as a deprecated alias for one release; or ship Phase B behind a debug flag first.
- **Rollback path:** each phase is a single PR; revert the PR to roll back.

## Open question for review
`adoptParent({ childKey, parentKey })` — should `parentKey: "context"` be a string sentinel, or should `adoptParent` take a separate `parentLoaderHandle?: string` parameter and require callers to fetch the context classloader handle explicitly? Sentinel is terser; explicit handle is more uniform with the rest of the API. Default to sentinel — fewer round-trips and `ContextHolder` is internal enough that callers shouldn't need to know.