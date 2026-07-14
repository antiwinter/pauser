# Plan: Finish the `draft split` — delete the old surface

## Why

Commit `23db816 draft split` (branch `cat2.9`) tangled **two** refactors and
finished neither's delete half, so it reads as churn instead of subtraction:

- **A — provider-folder + `meta.json` manifest**: lift static provider surface
  (`protocol`, `providesArt`, `fieldSpec`, `version`, `displayName`) out of the
  JS bundle into a per-provider `meta.json`; iterate folders; auto-inject
  co-located jars. This is the intended "split catvod concerns from Kotlin".
- **B — JarLoader handle rework**: `load()` returns an opaque handle;
  `loadClass`/`reflect`/`registerLoader` key off `handle` not `url`; jars stage
  to `code_cache/jars/`; hardlink direction flips. This is the real
  *agnostic-split bug fix* (see `JarLoaderPrimitivesTest`).

The commit **added** the new structure without **removing** the old. Result:
static field spec now lives in **three** places, `loadAsset` "removed" but
present in **four**, and staging logic duplicated across two classes.

AGENTS.md (draft/pre-release rules): **no backward-compat layers, no
deprecated-but-still-works bridges — update call sites and delete the old
approach.** This plan does the missing deletes.

## Scope

Cleanup only. No behavior change to the meta.json / handle model that already
landed. Do **not** re-plan A or B — they work; finish their subtraction.

---

## Findings (grounded at `23db816`)

1. **`meta.json` is a second source of truth, not a replacement.**
   - `providers-ts/providers/catvod/provider.ts` — still exports
     `getFieldsSpec()` returning the **identical** array now in `catvod/meta.json`.
     **Zero importers** (`index.ts` dropped the import). Dead.
   - `providers-ts/providers/emby/provider.ts` — exports `getFieldsSpec()` (dead,
     no importer) **and** `makeClientState()` (live, imported by `emby/index.ts`).
     Must gut half, keep half.
   - `providers-ts/utils/types.ts:244` `InsomniaProviderBridge` still declares
     `providesArt` + `getFieldsSpec()`. `ProviderFieldSpec` (types.ts:123) now
     only referenced by the two dead `provider.ts` functions + that interface.

2. **`loadAsset` "removed" but present in four places** — a shim AGENTS.md forbids:
   - `JarLoader.loadAsset()` — kept, reimplemented against new staging.
   - `HostApis` `"loadAsset"` case — returns `"error: loadAsset removed"` string.
   - `types.ts` `HostAPI.jar.loadAsset` — still on interface.
   - `HostApisSandboxTest.loadAsset_returnsDocumentedError` — asserts the shim.
   All providers are rebundled from source in this repo, so "old bundles might
   call it" does not apply.

3. **Staging primitive duplicated.** `JsProviderLoader.stagingDir()/sanitize()`
   and `JarLoader.stageDir()/dexFileName()` are the same `code_cache/jars` +
   `DexFilePermissions.chmodForDex` + colon/slash-replace dance in two classes.

---

## Changes

### 1. Kill the second source of truth (TS)

- **DELETE** `providers-ts/providers/catvod/provider.ts` entirely (dead file).
- **EDIT** `providers-ts/providers/emby/provider.ts`:
  - Remove `getFieldsSpec()` and its `ProviderFieldSpec` import.
  - Keep `makeClientState()` + `PlatformInfo` import + the `buildDeviceProfile`
    import. Update the file header comment (drop "getFieldsSpec").
- **EDIT** `providers-ts/utils/types.ts`:
  - Remove `providesArt` and `getFieldsSpec()` from `InsomniaProviderBridge`
    (interface now describes only the dynamic surface — matches
    `test/categories/bridge.js` `REQUIRED_METHODS`).
  - Remove `export interface ProviderFieldSpec` (types.ts:123) — confirm no live
    importer remains after the two deletes above (grep gate below).
    Note: `meta.json` field entries map to Kotlin `FieldSpecDto`, not this TS type.

### 2. Actually remove `loadAsset` (Kotlin + TS)

- **EDIT** `content/providers/js/.../JarLoader.kt`: delete `fun loadAsset(...)`.
- **EDIT** `content/providers/js/.../HostApis.kt`: delete the `"loadAsset"`
  `when` branch in `handleJar`.
- **EDIT** `providers-ts/utils/types.ts`: delete `loadAsset` from `HostAPI.jar`.
- **EDIT** `content/providers/js/src/test/.../HostApisSandboxTest.kt`: delete
  `loadAsset_returnsDocumentedError`.
- **EDIT** `providers-ts/test/host-apis.js`: drop any `loadAsset` handling in the
  jar stub (verify — the `draft split` may already have removed it).

### 3. Extract the shared staging primitive (Kotlin)

- **NEW** `content/providers/js/.../JarStaging.kt` (internal object) OR extend
  `DexFilePermissions` — one home for:
  - `stageDir(ctx): File` = `code_cache/jars` + chmod dir + chmod codeCacheDir.
  - `safeName(key): String` = `replace(':','_').replace('/','_')`.
- **EDIT** `JsProviderLoader.kt`: replace `stagingDir()`/`sanitize()` with the
  shared calls.
- **EDIT** `JarLoader.kt`: replace `stageDir()`/`dexFileName()` with the shared
  calls. Keep `dexFileName`'s DEX-path-split rationale comment at the call site
  or on the shared fn.

### 4. (Optional, recommended) Re-split the history

Not required to make the tree correct, but makes the change reviewable and is
the stated goal ("neater, not just different"). Two commits:
- **Commit B**: JarLoader handle rework + `ClassPathInjector` per-jar keyset
  (the bug fix) + staging extraction (#3).
- **Commit A**: provider-folder + meta.json + all deletes (#1, #2).

If re-splitting is out of scope, land #1–#3 as a single follow-up commit
`chore(js): finish provider split — delete dead field-spec + loadAsset`.

---

## Verification

- `cd providers-ts && yarn build` — all three providers bundle to
  `dist/<name>/index.js` + `meta.json`; no `ProviderFieldSpec` / `getFieldsSpec`
  unresolved-import errors.
- `providers-ts/test/cli.js` per provider (catvod, emby, benchmark) — Bridge +
  Config categories green (they already read `meta.json`, not `getFieldsSpec`).
- Kotlin: `content/providers/js` unit tests compile and pass without the
  `loadAsset` test; `JarLoaderPrimitivesTest` (handle + `dexFileName`) still green.
- Grep gates (must return **nothing** live after the change):
  - `getFieldsSpec` outside `.claude/plans/`.
  - `loadAsset` anywhere in `content/providers/js/` and `providers-ts/`.
  - `ProviderFieldSpec` anywhere (type fully retired).

## Grep gate (pre-flight, re-run at HEAD before editing)

```
getFieldsSpec | loadAsset | ProviderFieldSpec
```
Each must resolve only to the files this plan deletes/edits — no surprise
live caller. If a live caller appears, STOP and fold it into scope.

## Non-goals

- No change to the meta.json schema, folder-iteration, handle threading, or
  hardlink staging model — those landed in `draft split` and work.
- No new provider. No migration/back-compat (draft rules).
