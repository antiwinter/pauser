# Telegram Content Provider — Implementation Plan

## Context

> **Plan status: not yet implemented.** The host-API contract in this document is current — `host.jar.loadAsset({ name })`, `host.jar.reflect({ url, cls, method, args, instance, factoryCls, factoryMethod })`, and `host.jar.load({ source: { url | path | buffer } })` are the post-refactor shapes (see `providers-ts/utils/types.ts`). Telegram's `api.ts` is the right place to compose those primitives — no shim-specific changes to `JarLoader.kt` are needed for the load/reflect flow. If Telegram's TDLib jar ever needs the secondary-loader dance (Phase B's `loadClass`/`registerLoader`/`adoptParent`), the same primitives used by catvod compose into TDLib's boot recipe.

**Core rule: No Telegram-specific code in the main `:content` module. All Telegram logic lives in the shim-jar.**

---

## 1. Architecture: Telegram Shim-JAR (all TG logic lives here)

**Telegram speaks MTProto via TDLib — a C library with a Java JSON wrapper.** This cannot run in QuickJS (no crypto, no BigInt, no native support).

**Design: Load TDLib via a Telegram shim-jar**, following the exact same pattern as the catvod shim-jar.

```
providers-ts/providers/telegram/shim-jar/
  src/
    com/opentune/telegram/
      TelegramBridge.java    — all Telegram logic: auth, content, search
      TDLibClient.java       — TDLib JSON wrapper (execute request → response)
  build.sh                   — javac → d8 → jar (same as catvod/shim-jar/build.sh)
  → dist/telegram-shim.jar   — output: classes.dex + libtdjson.so per ABI
```

The shim-jar defines its own interface, independent of the main app's contracts:

```java
public abstract class TelegramBridge {
    void init(Context ctx) throws Exception;
    void init(Context ctx, String extend) throws Exception;
    // QR auth
    String getQr() throws Exception;              // returns {token, qrData}
    String pollQr(String token) throws Exception; // returns {status, fields?}
    // Content (returns JSON strings matching OpenTune contract types)
    String homeContent(boolean filter) throws Exception;
    String categoryContent(String chatId, String pg, boolean filter, HashMap<String,String> extend) throws Exception;
    String detailContent(List<String> ids) throws Exception;
    String searchContent(String key, boolean quick, String pg) throws Exception;
    String playerContent(String flag, String id, List<String> vipFlags) throws Exception;
    String getSprite(String itemRef, long ts) throws Exception;
    void destroy();
}
```

**Why shim-jar over AAR/new module:**
- `JarLoader` already handles `.so` extraction + `DexClassLoader` setup
- CatVod already proves this pattern works
- Zero `settings.gradle.kts` or `app/build.gradle.kts` changes needed
- TDLib loaded only when Telegram provider is used, not at app startup
- Clean isolation: TDLib updates don't require app rebuild

---

## 2. How the Pieces Connect

The TypeScript provider calls `host.jar.reflect()` directly — no new host namespace needed.

```
┌─ Main App (:content) — NO Telegram-specific code ──────────────────┐
│                                                                     │
│  JarLoader.kt         ← one-line fix: asset-key fallback in reflect  │
│  JsProviderInstance.kt← generic getQr()/pollQr()/getSprite() overrides│
│                                                                     │
├─ Shim-JAR (telegram-shim.jar) — ALL Telegram logic here ────────────┤
│                                                                     │
│  TelegramBridge       ← TDLib init, QR auth, content fetch          │
│  TDLibClient          ← TDLib JSON wrapper                          │
│  libtdjson.so         ← native lib (arm64-v8a, armeabi-v7a)         │
│                                                                     │
├─ TypeScript (providers-ts/providers/telegram/) ──────────────────────┤
│                                                                     │
│  index.ts             ← wires globalThis.opentuneProvider           │
│  provider.ts          ← getFieldsSpec() (QrCode field)              │
│  instance.ts          ← listEntry, search, getDetail, getPlaybackSpec│
│  api.ts               ← wraps host.jar.reflect() with asset key     │
│  parser.ts            ← text → movie metadata (pure function)        │
│  mapper.ts            ← shim JSON → EntryInfo/EntryDetail            │
│  dto.ts               ← TDLib JSON response types                    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**`api.ts` wraps `host.jar.reflect()` with the asset key:**

```typescript
// providers-ts/providers/telegram/api.ts
const ASSET_KEY = 'asset:telegram-shim.jar';
const BRIDGE_CLS = 'com.opentune.telegram.TelegramBridge';

export async function shimReflect(method: string, args: unknown[]): Promise<string> {
  await host.jar.loadAsset({ name: 'telegram-shim.jar' });
  return await host.jar.reflect({
    url: ASSET_KEY, cls: BRIDGE_CLS, method, args,
  });
}

export async function ensureLoaded(): Promise<void> {
  await host.jar.loadAsset({ name: 'telegram-shim.jar' });
}
```

**Existing `host.jar.reflect()` API handles everything** — no new host namespace, no new `dispatchHost` branch. The asset key (`"asset:telegram-shim.jar"`) is passed as the `url` parameter, matching how `JarLoader` stores asset-loaded JARs. **Caveat:** `reflect()` looks up the loader by `urlKey(url) = SHA-256(url).take(16)` — but `loadAsset` stores under the raw `"asset:$name"` key. The one-line `loaders[urlKeyVal] ?: loaders[url]` fallback below is still required before Telegram's `api.ts` works; today `reflect({ url: ASSET_KEY, … })` would error. If Telegram's TDLib shim ever needs a boot dance, compose the generic primitives — `loadClass` to run `<clinit>`, poll `reflect` for a `ClassLoader` handle, `registerLoader` to register it, `adoptParent` to wire its parent.

### One-line `JarLoader` fix

Asset-loaded JARs are stored with key `"asset:$name"`, but `reflect()` looks up by `urlKey(url)` (SHA-256 hash). For asset keys, we need a direct-key fallback:

```kotlin
// JarLoader.reflect() — add one line:
fun reflect(url: String, ...): String {
    val loader = loaders[urlKey(url)]
        ?: loaders[url]  // asset keys like "asset:telegram-shim.jar" are not hashed
        ?: error("JAR not loaded: $url")
    // ... rest unchanged
}
```

---

## 3. QR Auth Flow

The form system (`ProviderFormRoute.kt`) already handles QR fields. `ContentRoutes` wiring:

```kotlin
val hasQr = fields.any { it.kind == FormFieldKind.QrCode }
onGetQr = { provider.createClient(emptyMap(), caps).getQr() }
onPollQr = { client.pollQr(token) }
```

**Current gap:** `JsProviderInstance` does NOT override `getQr()` / `pollQr()`. These need to be added as generic methods that delegate to JS:

```kotlin
// JsProviderInstance.kt — generic, NOT Telegram-specific
override suspend fun getQr(): QrResult.QrReady? {
    ensureReady()
    val resultJson = engine.callMethod("getQr", "{}") ?: return null
    // parse {token, qrData} → QrResult.QrReady
}

override suspend fun pollQr(token: String): QrResult {
    ensureReady()
    val args = buildJsonObject { put("token", token) }
    val resultJson = engine.callMethod("pollQr", args.toString()) ?: ...
    // parse → QrResult variant
}
```

The TypeScript provider implements `getQr()` and `pollQr()` which call into the shim-jar:

```typescript
async getQr(): Promise<{token: string, qrData: string} | null> {
  const raw = await shimReflect('getQr', []);
  return JSON.parse(raw);
}

async pollQr(args: {token: string}): Promise<QrPollResult> {
  const raw = await shimReflect('pollQr', [args.token]);
  return JSON.parse(raw);
}
```

The shim-jar's `TelegramBridge` handles actual TDLib QR calls.

---

## 4. Content Hierarchy

```
listEntry(null)
└── [Folder] "Movie Channel"     id = "tg:chat:1234567890"
    listEntry("tg:chat:1234567890")
    ├── [Digipak] "Videos"       id = "tg:folder:1234567890:videos"
    ├── [Digipak] "Photos"       id = "tg:folder:1234567890:photos"
    ├── [Digipak] "Posts"        id = "tg:folder:1234567890:posts"
    └── [Digipak] "Albums"       id = "tg:folder:1234567890:albums"
        listEntry("tg:folder:1234567890:videos")
        ├── [Playable] "Movie Title (2023)"   id = "tg:msg:1234567890:42000"
        ├── [Image]    "Photo from Channel"   id = "tg:msg:1234567890:42001"
        └── [Digipak]  "Album (3 items)"      id = "tg:album:1234567890:mGroupId"
```

Top level (`listEntry(null)`): TDLib `getChats`, filter to supergroups/channels.

Type sub-folders are **virtual** — synthetic `Digipak` entries encoding the filter type in the ID. Populated via `searchMessages` with TDLib content filters.

Pagination: TDLib message search is cursor-based (`fromMessageId`). Module-level cursor cache in JS state maps `(chatId, filter, startIndex)` → `fromMessageId`.

---

## 5. Text Parser for Movie Metadata

`parser.ts` — pure function, no host calls. Parses message text for:
- Title + year: `/^[^\w]*(.+?)\s*\((\d{4})\)\s*$/m`
- Rating: `/(?:IMDB|IMDb|⭐|★|Rating)\s*[:\s]?\s*([\d.]+)\s*(?:\/\s*10)?/i`
- Genres via hashtags and emoji
- IMDB/TMDB URLs for provider IDs

Output maps to `EntryInfo` (for list) and `EntryDetail` (for detail view).

---

## 6. `getSprite` Interface

Add as an **optional method** on `EndpointClient` and `OpenTuneProviderBridge` — not Telegram-specific.

**`providers-ts/utils/types.ts`**:
```typescript
getSprite?(args: { itemRef: string; ts: number }): Promise<string | null>;
```

**`ProviderContracts.kt`**:
```kotlin
open suspend fun getSprite(itemRef: String, ts: Long): String? = null
```

**`JsProviderInstance.kt`**: add override that checks for JS `getSprite` method, delegates to JS if present.

For Telegram, returns the video message's static thumbnail as a data URL (one per video, regardless of `ts`).

---

## 7. Files to Create/Modify

### New — Shim-JAR (`providers-ts/providers/telegram/shim-jar/`)

| File | Purpose |
|------|---------|
| `src/com/opentune/telegram/TelegramBridge.java` | All Telegram logic: TDLib init, QR auth, content fetching |
| `src/com/opentune/telegram/TDLibClient.java` | TDLib JSON wrapper (`execute(requestJson): String`) |
| `build.sh` | Compile → dex → jar (copies catvod/shim-jar/build.sh pattern) |

### New — TypeScript (`providers-ts/providers/telegram/`)

| File | Purpose |
|------|---------|
| `index.ts` | IIFE entry; wires `globalThis.opentuneProvider` |
| `provider.ts` | `getFieldsSpec()` (returns QrCode field), `validateFields()` |
| `instance.ts` | `listEntry`, `search`, `getDetail`, `getPlaybackSpec`, `getSprite`; cursor cache |
| `api.ts` | `shimReflect()` — wraps `host.jar.reflect()` with asset key |
| `dto.ts` | TDLib JSON response types |
| `mapper.ts` | shim JSON → `EntryInfo`/`EntryDetail` |
| `parser.ts` | `parseMessageText(text): ParsedMovieInfo` — all regex logic |

### Files to Modify (all generic, NOT Telegram-specific)

| File | Change |
|------|--------|
| `providers-ts/utils/types.ts` | Add `getSprite?` to `OpenTuneProviderBridge` |
| `content/contract/.../ProviderContracts.kt` | Add `open suspend fun getSprite(itemRef: String, ts: Long): String? = null` |
| `content/providers/js/.../JarLoader.kt` | One line: `?: loaders[url]` fallback in `reflect()` for asset keys |
| `content/providers/js/.../JsProviderInstance.kt` | Add generic `getQr()`, `pollQr()`, `getSprite()` overrides |
| `providers-ts/test/host-apis.js` | Add `tdlib` stub to `handleJarStub` |

### Assets

| File | Purpose |
|------|---------|
| `app/src/main/assets/telegram-shim.jar` | Telegram shim + TDLib native libs (built by shim-jar/build.sh) |

No `settings.gradle.kts` change. No new Gradle module. No `app/build.gradle.kts` dependency change. No new host namespace. No changes to `HOST_BOOTSTRAP_JS` or `dispatchHost()`.

---

## 8. Implementation Sequence

### Phase 1: `getSprite` plumbing + `JarLoader` fix (no Telegram dependency)
1. Add `getSprite?` to `OpenTuneProviderBridge` in `types.ts`
2. Add `open suspend fun getSprite(itemRef: String, ts: Long): String? = null` to `EndpointClient`
3. Add `getSprite()` override to `JsProviderInstance`
4. One-line fix in `JarLoader.reflect()`: add `?: loaders[url]` for asset-key fallback
5. Verify no existing tests break

### Phase 2: Shim-JAR build infrastructure
1. Create `providers-ts/providers/telegram/shim-jar/build.sh` (copy from catvod)
2. Create shim interface: `TelegramBridge.java`, `TDLibClient.java`
3. Implement TDLib QR auth + content methods
4. Build `telegram-shim.jar` (will need `libtdjson.so` binaries)

### Phase 3: TypeScript provider files
Implement in order: `dto.ts` → `api.ts` → `parser.ts` → `mapper.ts` → `instance.ts` → `provider.ts` → `index.ts`. Verify `dist/telegram.js` builds. Add generic `getQr()` / `pollQr()` overrides to `JsProviderInstance`.

### Phase 4: End-to-end
1. Place `telegram-shim.jar` in `app/src/main/assets/`
2. `telegram.js` auto-discovered by `JsProviderLoader` (existing `.js` scan)
3. Device QA: Add Telegram → QR displayed → scan → channels appear → browse → play → sprite

---

## 9. Verification

- `npm run test -- telegram` passes: `config`, `catalog`, `detail`, `playback`, `telegram-parser` categories
- `parser.ts` unit tests: standard emoji post, IMDB URL extraction, hashtag genres, no-metadata fallback
- Device QA: Add Telegram provider → QR displayed → scan with phone → channels appear → browse videos → play → getSprite returns thumbnail during seek
- `getSprite` test: call with a `tg:msg:*` video ID, expect `data:image/jpeg;base64,...` or `null`

---

## Critical Files

- `providers-ts/utils/types.ts` — contract types; single source of truth for bridge interface
- `content/providers/js/src/main/java/com/opentune/provider/js/JarLoader.kt` — one-line asset-key fallback in `reflect()`
- `content/providers/js/src/main/java/com/opentune/provider/js/JsProviderInstance.kt` — `getQr`, `pollQr`, `getSprite` overrides
- `content/contract/src/main/java/com/opentune/content/contract/ProviderContracts.kt` — `getSprite` addition
- `content/contract/src/main/java/com/opentune/content/contract/QrContracts.kt` — QR types (no changes needed)
- `core/form/src/main/java/com/opentune/core/form/ProviderFormRoute.kt` — QR UI (no changes needed)
- `providers-ts/providers/catvod/shim-jar/` — reference pattern for build.sh + Java structure
- `providers-ts/providers/catvod/handlers/jar.ts` — reference pattern for shim-jar calling from TS
- `providers-ts/test/host-apis.js` — test harness; add tdlib stub to `handleJarStub`
