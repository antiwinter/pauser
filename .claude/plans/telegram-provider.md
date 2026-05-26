# Telegram Content Provider — Implementation Plan

## Context

Add Telegram as a content provider for OpenTune (Android TV). Users log in by scanning a QR code with their own Telegram mobile app (not a bot). Joined channels/groups surface as libraries; messages within them are browsable as content items (videos, photos, text posts). Text posts are parsed for movie metadata (title, year, rating, genres, links). Video messages are playable; photo messages are viewable. Introduces a new optional `getSprite(itemRef, ts)` capability to the provider interface.

---

## 1. The API Call Problem (Most Important Decision)

**Telegram speaks MTProto — a binary TLS-encrypted protocol — not HTTP/REST.** This cannot be implemented inside QuickJS (32 MB RAM, no Node crypto, no BigInt, no WebSocket).

**Recommended approach: Extend `host` with a `host.tdlib.*` namespace backed by TDLib.**

TDLib (official Telegram C library) has a JSON API — every function is a JSON object in / JSON object out. On Android, TDLib ships as a prebuilt `.so` (arm64-v8a + armeabi-v7a). The existing `__hostDispatchRaw(ns, name, argsJson)` architecture maps cleanly: JS calls `host.tdlib.getChats(args)` → Kotlin dispatches to TDLib → JSON response returned.

This keeps all MTProto complexity in Kotlin. The TypeScript bundle only handles data modeling and content mapping.

**Not viable**: local HTTP server bridge (adds round-trip overhead per call, doesn't simplify TDLib integration), MTProto-in-JS (requires crypto/BigInt not available in sandbox).

---

## 2. Architecture Split

The new Kotlin module `content/providers/telegram/` is a **native provider** (not a pure JS provider) because authentication requires TDLib before any JS engine is running. Content methods then delegate to a QuickJS engine with the `tdlib` host namespace injected.

```
Authentication (getQr, pollQr, validateFields)
    → TelegramEndpointClient.kt (Kotlin, TDLib directly)

Content (listEntry, search, getDetail, getPlaybackSpec, getSprite)
    → QuickJS engine running dist/telegram.js
    → host.tdlib.* → TelegramHostApi.kt → TdClientWrapper.kt → TDLib
```

---

## 3. QR Auth Flow

Telegram QR auth flow (`auth.loginToken` → display QR → `auth.loginTokenWait`):

1. `TelegramProvider.getFieldsSpec()` returns `[FormFieldSpec(id="qr", kind=FormFieldKind.QrCode)]` — this triggers the QR UI in `ProviderFormRoute.kt`.
2. `TelegramEndpointClient.getQr()` calls TDLib `requestQrCodeAuthentication` → returns `QrResult.QrReady(token, qrData = "tg://login?token=<base64>")`.
3. `pollQr(token)` calls TDLib current auth state → maps: `WaitOtherDeviceConfirmation` → `QrResult.Scanning`, `Ready` → `QrResult.Confirmed(fields = mapOf("endpoint_id" to id))`.
4. `validateFields()` receives the confirmed fields; verifies session is ready; returns `{ success: true, hash, name, fields }`.
5. The `endpoint_id` in fields maps to the TDLib database directory for this session.

The JS bundle's `getFieldsSpec()` returns a single non-required `display_name` field (satisfies test harness; actual auth is in Kotlin).

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
        └── [Digipak]  "Album (3 items)"      id = "tg:album:1234567890:mgroupId"
```

Top level (`listEntry(null)`): TDLib `getChats({ chatList: chatListMain, limit: 100 })`, filter to supergroups/channels.

Type sub-folders are **virtual** — synthetic `Digipak` entries encoding the filter type in the ID. Populated via `searchMessages` with TDLib content filters.

Pagination: TDLib message search is cursor-based (`fromMessageId`). Module-level cursor cache in JS state maps `(chatId, filter, startIndex)` → `fromMessageId` to satisfy the contract's `(startIndex, limit)` API.

---

## 5. Text Parser for Movie Metadata

`parser.ts` — pure function, no host calls.

```typescript
const TITLE_YEAR = /^[^\w]*(.+?)\s*\((\d{4})\)\s*$/m;
const RATING = /(?:IMDB|IMDb|⭐|★|Rating)\s*[:\s]?\s*([\d.]+)\s*(?:\/\s*10)?/i;
const GENRE_EMOJI = /[🏷️🎭]\s*([A-Za-z ,]+)/u;
const HASHTAGS = /#([A-Za-z][A-Za-z0-9_]*)/g;
const IMDB_URL = /https?:\/\/(?:www\.)?imdb\.com\/title\/(tt\d+)/i;
const TMDB_URL = /https?:\/\/(?:www\.)?themoviedb\.org\/(?:movie|tv)\/(\d+)/i;
```

Output `ParsedMovieInfo` maps to:

| Parser field | EntryInfo | EntryDetail |
|---|---|---|
| `title` | `title` | `title` |
| `year` | — | `year` |
| `rating` | `communityRating` | `rating` |
| `genres` | `genres` | — |
| `overview` | `overview` (first 200 chars) | `overview` |
| `imdbId` | — | `providerIds["Imdb"]`, `externalUrls` |
| `tmdbId` | — | `providerIds["Tmdb"]`, `externalUrls` |
| `trailerUrl` | — | `externalUrls[{name:"Trailer"}]` |

`etag` = `sha256(rawText)` via `host.crypto.sha256`.

---

## 6. `getSprite` Interface

Add as an **optional method** on the global `OpenTuneProviderBridge` — not Telegram-specific, so Emby can later add trickplay support.

**`providers-ts/utils/types.ts`**:
```typescript
getSprite?(args: { itemRef: string; ts: number }): Promise<string | null>;
```

**`content/contract/src/.../ProviderContracts.kt`** (`EndpointClient`):
```kotlin
open suspend fun getSprite(itemRef: String, ts: Long): String? = null
```

**`JsProviderInstance.kt`**: add override that checks `hasMethod("getSprite")` before calling into JS, returns `null` if absent.

For Telegram, `getSprite` returns the video message's static thumbnail (one per video) as a data URL — same thumbnail regardless of `ts`. For Emby, a future implementation can call the trickplay endpoint with the real timestamp.

---

## 7. Files to Create/Modify

### New TypeScript Files (`providers-ts/providers/telegram/`)

| File | Purpose |
|---|---|
| `index.ts` | IIFE entry; wires `globalThis.opentuneProvider`; module-level `state` |
| `provider.ts` | `getFieldsSpec()` (returns `display_name` field), `validateFields()`, `makeInstanceState()` |
| `instance.ts` | `listEntry`, `search`, `getDetail`, `getPlaybackSpec`, `getSprite`; cursor cache |
| `api.ts` | Thin wrappers over `host.tdlib.*` |
| `dto.ts` | TDLib JSON response types (`TgChat`, `TgMessage`, `TgVideo`, `TgPhoto`, etc.) |
| `mapper.ts` | `chatToEntry()`, `messageToEntry()`, `messageToDetail()`, `messageToPlaybackSpec()` |
| `parser.ts` | `parseMessageText(text): ParsedMovieInfo` — all regex logic |

### New Kotlin Module (`content/providers/telegram/`)

| File | Purpose |
|---|---|
| `TelegramProvider.kt` | `OpenTuneProvider` impl; `getFieldsSpec()` adds QrCode field |
| `TelegramEndpointClient.kt` | `EndpointClient` impl; `getQr()`/`pollQr()` via TDLib; content methods via QuickJS |
| `TdClientWrapper.kt` | Wraps TDLib `Client.java`; bridges callbacks to coroutines; manages DB dir per endpoint |
| `TelegramHostApi.kt` | Handles `host.tdlib.*` namespace dispatch; routes to `TdClientWrapper` |
| `TelegramProviderLoader.kt` | `OpenTuneProviderLoader` SPI entry; registers `TelegramProvider` |
| `build.gradle.kts` | TDLib AAR dep, `:content:contract`, `:content:providers:js`, coroutines |

### Files to Modify

| File | Change |
|---|---|
| `providers-ts/utils/types.ts` | Add `getSprite?` to `OpenTuneProviderBridge`; add `tdlib` namespace to `HostAPI` |
| `content/contract/.../ProviderContracts.kt` | Add `open suspend fun getSprite(itemRef, ts): String? = null` |
| `content/providers/js/.../JsProviderInstance.kt` | Add `getSprite()` override with `hasMethod` guard |
| `content/providers/js/.../QuickJsEngine.kt` | Add `extraNamespaces: Map<String, NamespaceHandler>` to `dispatchHost`; construct tdlib namespace in `TelegramEndpointClient` |
| `providers-ts/test/host-apis.js` | Add `tdlib` stub that throws clearly |
| `settings.gradle.kts` | Add `include(":content:providers:telegram")` |
| `app/build.gradle.kts` | Add `implementation(project(":content:providers:telegram"))` |

No changes needed to Rollup config — it auto-discovers `providers/*/index.ts`. `telegram.js` appears in `dist/` automatically and is included via existing `assets.srcDirs` in `app/build.gradle.kts`.

---

## 8. Implementation Sequence

1. **`getSprite` plumbing** — add optional method to TS bridge + Kotlin contract + `JsProviderInstance` wire-up. No existing tests break.
2. **Host namespace extension** — modify `QuickJsEngine.dispatchHost` to support extra namespaces; add `tdlib` stub to test `HostApis`.
3. **TypeScript provider files** — implement in order: `dto.ts` → `api.ts` → `parser.ts` → `mapper.ts` → `instance.ts` → `provider.ts` → `index.ts`. Verify `dist/telegram.js` builds.
4. **Kotlin module skeleton** — `TdClientWrapper` + `TelegramHostApi` (TDLib integration). Wire into `TelegramEndpointClient` content methods via QuickJS.
5. **QR auth** — implement `getQr()`/`pollQr()` on `TelegramEndpointClient`. Test on device.
6. **Registration** — `TelegramProviderLoader`, `settings.gradle.kts`, `app/build.gradle.kts`. Full end-to-end test.

---

## 9. Verification

- `npm run test -- telegram` passes: `config`, `catalog`, `detail`, `playback`, `telegram-parser` categories.
- `parser.ts` unit tests: standard emoji post, IMDB URL extraction, hashtag genres, no-metadata fallback.
- Device QA: Add Telegram provider → QR displayed → scan with phone → channels appear → browse videos → play → getSprite returns thumbnail during seek.
- `getSprite` test: call with a `tg:msg:*` video ID, expect `data:image/jpeg;base64,...` or `null`.

---

## Critical Files

- `providers-ts/utils/types.ts` — contract types; single source of truth for bridge interface
- `content/providers/js/src/main/java/com/opentune/provider/js/QuickJsEngine.kt` — host dispatch extension point
- `content/providers/js/src/main/java/com/opentune/provider/js/JsProviderInstance.kt` — `getSprite` override
- `content/contract/src/main/java/com/opentune/content/contract/ProviderContracts.kt` — Kotlin contract
- `providers-ts/providers/emby/index.ts` — reference pattern for new provider entry point
- `core/form/src/main/java/com/opentune/core/form/ProviderFormRoute.kt` — QR polling UI (no changes; just confirm contract is met)
