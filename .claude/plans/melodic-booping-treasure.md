# Architectural Refactor Plan

## Context
The proxy support implementation works but has design issues: scattered null checks, the wrong layer owning the `OkHttpClient`, a redundant `proxy_assignments` table, and missing JS proxy support. The core insight: **the proxy `OkHttpClient` belongs to the provider layer** — every network call (REST, validation, image loading, playback) should go through it. The provider stores the client internally and produces an already-proxied+authorized `PlaybackSpec.httpClient` (non-null). No callee should branch on whether a proxy exists.

## Corrected Architecture

### Key design decisions
1. `OpenTuneProvider.createClient(values, caps, httpClient: OkHttpClient)` — provider receives the http client at construction. Stores it internally for all its own REST/API calls.
2. `EndpointClient` carries `httpClient: OkHttpClient` and `imageLoader: ImageLoader` as properties set by the app after construction — not managed by provider code.
3. `PlaybackSpec.httpClient: OkHttpClient` — **non-null**. The provider always provides one (its internally-stored client). No more null checks in player code.
4. `PlaybackSpec.url: String` — still present. Stream URL is needed; there are no auth headers in `PlaybackSpec`.
5. `validateFields(values, httpClient: OkHttpClient)` — validation also receives the proxied client so it can reach the server.
6. `EndpointHandle` is removed entirely. `EndpointClientRegistry` returns `EndpointClient` directly. The client instance itself has everything it needs.
7. `ProxyImageLoader` singleton is removed. App builds `ImageLoader` directly using a shared `DiskCache` and the client's `httpClient`.
8. Shared `DiskCache` is built once in `OpenTuneApplication` and passed to all image loaders — one cache, one size limit, correct LRU.

### Data flow
```
App reads entity.proxyConfigId → builds OkHttpClient (proxied or plain OkHttpClient())
  → passes to provider.createClient(values, caps, httpClient)
      → EndpointClient stores httpClient internally
      → getPlaybackSpec().httpClient = this.httpClient  ← non-null
  → builds ImageLoader(app, sharedDiskCache, client.httpClient)
      → attaches as client.imageLoader
  → all routes use client directly (client.imageLoader, client.httpClient)
```

---

## Implementation Plan

### Phase A: Storage schema simplification
**Files:** `storage/.../Entities.kt`, `Daos.kt`, `OpenTuneDatabase.kt`, `StorageBindings.kt`

1. Add `proxyConfigId: String? = null` to `EndpointEntity`
2. Remove `ProxyAssignmentEntity` and `ProxyAssignmentDao`
3. Bump DB version, add migration: `ALTER TABLE endpoints ADD COLUMN proxyConfigId TEXT`
4. Remove `proxyAssignmentDao` from `StorageBindings`
5. Remove `isEnabled` from `ProxyConfigEntity`

### Phase B: Contracts — inject httpClient into provider, add imageLoader to EndpointClient
**Files:** `contracts/.../ProviderContracts.kt`, `contracts/.../PlaybackContracts.kt`

1. Add `httpClient: OkHttpClient` param to `OpenTuneProvider.validateFields()`
2. Add `httpClient: OkHttpClient` param to `OpenTuneProvider.createClient()`
3. Add `var imageLoader: ImageLoader` to `EndpointClient` interface (coil dep in contracts — accepted)
4. Change `PlaybackSpec.httpClient` from `OkHttpClient? = null` to `OkHttpClient` (non-null)
5. Remove `supportsProxy` from `OpenTuneProvider` — all providers support proxy by default now

### Phase C: Provider implementations
**Files:** `providers/emby/.../EmbyProvider.kt`, `providers/js/.../JsProvider.kt`, `providers/js/.../JsProviderInstance.kt`, SMB/other providers

1. **Emby**: accept `httpClient` in `createClient()`, store it, use it for Retrofit/OkHttp calls; `getPlaybackSpec()` returns `PlaybackSpec(url=..., httpClient=this.httpClient)`
2. **JS**: accept `httpClient` in `createClient()`, pass to `JsProviderInstance`; instance stores it, passes to `HostApis.handleHttp()`; `getPlaybackSpec()` returns non-null `httpClient`
3. **Other providers**: accept and store `httpClient`; pass through to `PlaybackSpec`
4. All providers: accept and store `imageLoader` (app sets it post-construction)

### Phase D: OpenTuneApplication — shared DiskCache
**Files:** `app/.../OpenTuneApplication.kt`

1. Build `sharedDiskCache: DiskCache` once in `OpenTuneApplication` (same directory/size as current `ProxyImageLoader` cache)
2. Export `sharedDiskCache` as a property for use when building per-client image loaders

### Phase E: EndpointClientRegistry — build client + imageLoader, return client directly
**Files:** `app/.../providers/EndpointClientRegistry.kt`

1. In `buildClient()`: read `entity.proxyConfigId` directly (no `ProxyAssignmentDao` join)
2. Build `OkHttpClient`: proxy-configured if `proxyConfigId` set, else plain `OkHttpClient()`
3. Call `provider.createClient(values, caps, httpClient)` → `EndpointClient`
4. Build `ImageLoader` using `sharedDiskCache` + `client.httpClient`, assign to `client.imageLoader`
5. Return `EndpointClient` directly — no `EndpointHandle` wrapper
6. Remove `EndpointHandle` data class entirely
7. Update all callers (`EndpointClientRegistry.getOrCreate()`, server `getClient()`/`registerClient()`) to use `EndpointClient` directly

### Phase F: Player — remove null checks
**Files:** `player/.../PlaybackSpecExt.kt`, `player/.../SubtitleController.kt`, `app/.../ui/player/PlayerRoute.kt`

1. `PlaybackSpec.toMediaSource(context)` uses `spec.httpClient` directly (non-null, no fallback)
2. `SubtitleController` uses `spec.httpClient` directly (no null check)
3. `PlayerRoute` uses `client.httpClient` from the `EndpointClient` directly

### Phase G: Remove ProxyImageLoader, update routes
**Files:** `app/.../image/ProxyImageLoader.kt`, all routes

1. Delete `ProxyImageLoader.kt`
2. Update all routes to use `client.imageLoader` instead of `ProxyImageLoader.get(endpointId, ...)`
3. Update `BrowseRoute`, `SearchRoute`, `DetailRoute`, etc. to take `EndpointClient` instead of `EndpointHandle`

### Phase H: Remove ProxyConfigRepository
**Files:** `app/.../providers/ProxyConfigRepository.kt`, proxy UI routes

1. Delete `ProxyConfigRepository.kt`
2. Proxy form route calls storage DAOs directly (same pattern as `ProviderFormRoute`)

---

## Critical Files
- `storage/src/main/java/com/opentune/storage/Entities.kt`
- `storage/src/main/java/com/opentune/storage/Daos.kt`
- `storage/src/main/java/com/opentune/storage/OpenTuneDatabase.kt`
- `contracts/src/main/java/com/opentune/provider/ProviderContracts.kt`
- `contracts/src/main/java/com/opentune/provider/PlaybackContracts.kt`
- `app/src/main/java/com/opentune/app/OpenTuneApplication.kt`
- `app/src/main/java/com/opentune/app/providers/EndpointClientRegistry.kt`
- `app/src/main/java/com/opentune/app/providers/EndpointHandle.kt` (to be deleted)
- `app/src/main/java/com/opentune/app/image/ProxyImageLoader.kt` (to be deleted)
- `player/src/main/java/com/opentune/player/engine/PlaybackSpecExt.kt`
- `player/src/main/java/com/opentune/player/controller/SubtitleController.kt`
- `providers/emby/src/main/java/com/opentune/provider/emby/EmbyProvider.kt`
- `providers/js/src/main/java/com/opentune/provider/js/JsProvider.kt`
- `providers/js/src/main/java/com/opentune/provider/js/JsProviderInstance.kt`

## Verification
1. `./gradlew assembleDebug` — must succeed with no errors
2. Emby endpoint with proxy: images load proxied, REST calls use proxy, playback uses proxy client
3. JS endpoint with proxy: JS http calls go through proxy
4. Emby endpoint without proxy: plain `OkHttpClient()` used throughout, no NPEs
5. Proxy config add/edit/delete still works after removing ProxyConfigRepository
