# Plan: Server generator route + :gen-art module + Coil disk cache as single cache

## Context
The storage module was refactored to add `OssCache` (content-addressed disk cache with Room metadata + LRU eviction). This adds a second cache system parallel to Coil's native disk cache, and `AssetGenerator` (a Composable) directly knows about the cache.

We'll eliminate both by routing cover generation through the local server as an HTTP endpoint. Coil treats it as a regular URL, caches via its own disk cache, and serves from cache on subsequent requests. The generator logic lives in a new `:gen-art` module.

## Data flow
1. `listEntries()` returns provider data
2. For each item where `providesCover=false` and `item.cover == null`:
   - `item.cover = "http://localhost:7920/genart/v1/{sourceId}/{itemId}"`
3. Coil fetches the URL → server calls `:gen-art` → returns JPEG bytes → Coil caches
4. Failed generation or no cover available: `item.cover = "http://localhost:7920/asset/fallback"` → Coil caches the fallback naturally

## Architecture

```
:gen-art (new) — standalone, no deps
:server → :contracts, :storage, :gen-art
:app → :server, :storage, providers
```

## Files

### New module: :gen-art

**Create** `gen-art/build.gradle.kts` — Android library, minSdk=24, no project dependencies

**Create** `gen-art/src/main/java/com/opentune/genart/GenArt.kt`
- Single `object GenArt` with:
  - `const val VERSION = "v1"` — bumped when the extraction algorithm changes, invalidates all Coil caches via URL mismatch
  - `fun generateCover(videoUrl: String, headers: Map<String, String>): ByteArray?`
  - For playable: `MediaMetadataRetriever` extracts embedded picture, or frame at 1/3 duration
  - Resize/crop to 300×250
  - Returns JPEG bytes, or null on failure
  - No knowledge of `OpenTuneProviderInstance`, `PlaybackSpec`, or hooks — pure URL-in, bytes-out

### Server: register asset routes

**Modify** `server/src/main/java/com/opentune/server/OpenTuneServer.kt`
- Register `installAssetRoutes(ctx)` alongside `streamProxy.installRoutes()` and `installDebugRoutes(ctx)`

**Create** `server/src/main/java/com/opentune/server/AssetRoutes.kt`
- Two route groups:
  1. `GET /genart/{version}/{sourceId}/{itemId}` — dynamic generation
     - `version` must match `GenArt.VERSION` (e.g. `"v1"`); mismatch → 404
     - Resolves the provider instance via `ctx.getInstance(sourceId)`
     - Calls `instance.getPlaybackSpec(itemId, 0)` to get the media URL and auth headers
     - Passes `spec.url` and `spec.headers` to `GenArt.generateCover()`
     - Calls `spec.hooks.onDispose()` in finally block
     - Returns JPEG bytes on success, 1x1 blank JPEG on failure
     - Content-Type: `image/jpeg`
  2. `GET /asset/{name}` — static placeholders
     - Serves built-in fallback images (e.g. `fallback` — 1x1 gray JPEG)
     - Content-Type determined by file extension

### Storage module (remove OssCache, revert MediaState)

**Delete** `storage/src/main/java/com/opentune/storage/oss/OssCache.kt`
**Delete** `storage/src/main/java/com/opentune/storage/oss/OssCacheEntity.kt`

**Modify** `storage/src/main/java/com/opentune/storage/ServerEntities.kt`
- Remove `cachedCover`, `cachedBackdrops`, `cachedLogo` fields
- Restore `coverCachePath: String? = null` (pre-refactor)
- Remove `CACHE_FAILED` companion constant

**Modify** `storage/src/main/java/com/opentune/storage/MediaStateContracts.kt`
- Restore `upsertCoverCache(protocol, sourceId, itemId, path: String?)` method
- Restore `MediaStateSnapshot.coverCachePath: String?` field
- Remove `upsertCachedAssets` overloads

**Modify** `storage/src/main/java/com/opentune/storage/RoomMediaStateStore.kt`
- Restore `upsertCoverCache` implementation
- Remove `toBackdropList()` / `toBackdropString()` helpers
- Restore `toSnapshot()` mapping

**Modify** `storage/src/main/java/com/opentune/storage/Daos.kt`
- Restore `updateCoverCache(protocol, sourceId, itemId, path: String?, now: Long)`
- Remove `OssCacheDao` interface

**Modify** `storage/src/main/java/com/opentune/storage/OpenTuneDatabase.kt`
- Remove `OssCacheEntity` from entities, revert version 9 → 8

**Modify** `storage/src/main/java/com/opentune/storage/StorageBindings.kt`
- Remove `ossCache: OssCache` field entirely

### App module

**Modify** `app/src/main/java/com/opentune/app/OpenTuneApplication.kt`
- Remove `OssCache` import and initialization from `storageBindings`
- No `:gen-art` dependency needed — the app only assigns generator URLs (plain strings)

**Modify** `app/src/main/java/com/opentune/app/ui/catalog/AssetGenerator.kt`
- Strip all cache logic. Just assign generator URLs to items:
  - For each item where `!providesCover` and `item.cover == null`: `item.cover = "http://localhost:7920/genart/${GenArt.VERSION}/${sourceId}/${item.id}"`
- Rename to avoid conflict with `com.opentune.genart.AssetGenerator` (e.g. `RememberCoverUrlAssigner`)

**Modify** `app/src/main/java/com/opentune/app/ui/catalog/MediaEntryComponent.kt`
- No change — `coverImageModel()` already handles HTTP URLs uniformly via `AsyncImage`

### Coil upgrade

**Modify** `gradle/libs.versions.toml`
- Upgrade `coil = "2.7.0"` → `coil = "3.2.0"` (latest 3.x)
- Update the dependency artifact from `io.coil-kt:coil-compose` to `io.coil-kt.coil3:coil-compose` (Maven coordinates changed in 3.x)
- Configure a custom `ImageLoader` with explicit disk cache size (e.g. 200MB) instead of Coil's default

**Modify** `app/src/main/java/com/opentune/app/OpenTuneApplication.kt` (or new composable setup)
- Create a custom Coil `ImageLoader` with disk cache configured to ~200MB
- Replace default ImageLoader via `Coil.setImageLoader(...)` in Application.onCreate

### Build config

**Modify** `settings.gradle.kts` — add `include(":gen-art")`
**Modify** `server/build.gradle.kts` — add `implementation(project(":gen-art"))`

## Verification
1. `./gradlew assembleDebug` — builds successfully
2. Deploy, browse SMB server — first load triggers server-side generation, Coil caches
3. Second browse — Coil serves from disk cache
4. Browse Emby server — Coil caches HTTP cover URLs directly
5. Restart app — Coil disk cache survives, covers load instantly
