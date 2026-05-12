# Proxy System Implementation Plan

## Context

OpenTune is a multi-provider media player app (Emby, SMB, etc.) with two HTTP traffic layers that both need proxy support:
1. **Lower layer**: Provider API calls (catalog, search, playback resolution) — Emby uses Retrofit+OkHttp
2. **Upper layer**: ExoPlayer media streaming — creates its own OkHttpClient for streaming URLs and subtitle loading

The goal is a **single source of truth** for HTTP clients: the provider layer builds a proxied OkHttp client, and the player layer reuses it. Proxies are managed independently (separate screen) and assigned per-provider.

## Architecture Summary

- `:provider-api` — Pure Kotlin contracts (interfaces, data classes)
- `:providers:emby` — Emby provider (Retrofit + OkHttp for HTTP traffic)
- `:providers:smb` — SMB provider (SMBJ library, no HTTP — excluded from proxy)
- `:player` — ExoPlayer wrapper (OkHttp for media streaming)
- `:storage` — Room database
- `:app` — Android UI, provider registration, navigation

## Implementation Plan

### Phase 1: Storage — Proxy Config Entity & DAO

**New file: `storage/src/main/java/com/opentune/storage/ProxyConfigEntity.kt`**
```kotlin
@Entity(tableName = "proxy_configs", primaryKeys = ["id"])
data class ProxyConfigEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,            // user-friendly name like "Home Proxy"
    val proxyUrl: String,        // e.g. "http://192.168.11.22:8888"
    val isEnabled: Boolean = true,
    val createdAtEpochMs: Long,
)
```

**New file: `storage/src/main/java/com/opentune/storage/ProxyAssignmentEntity.kt`**
```kotlin
@Entity(tableName = "proxy_assignments", primaryKeys = ["sourceId"])
data class ProxyAssignmentEntity(
    @PrimaryKey val sourceId: String,
    val proxyConfigId: String,   // FK to proxy_configs.id, null = no proxy
)
```

**Modify: `storage/src/main/java/com/opentune/storage/Daos.kt`**
Add two DAOs:
- `ProxyConfigDao` — CRUD for proxy configs (list all, insert, update, delete, observeAll as Flow)
- `ProxyAssignmentDao` — map sourceId to proxyConfigId (upsert, getBySourceId, deleteBySourceId, **getAssignmentsForProxy** — returns list of sourceIds assigned to a given proxyConfigId, used by delete behavior)

**Modify: `storage/src/main/java/com/opentune/storage/OpenTuneDatabase.kt`**
Add both entities, add DAO accessors. Bump DB version. Project uses `fallbackToDestructiveMigration`, so no explicit migration needed.

**Modify: `storage/src/main/java/com/opentune/storage/StorageBindings.kt`**
Add `proxyConfigDao` and `proxyAssignmentDao` to the data class.

### Phase 2: The Single-Source-of-Truth HTTP Agent

**Modify: `provider-api/build.gradle.kts`**
Add `implementation(libs.okhttp)` — both `:providers:emby` and `:player` already depend on `:provider-api`, so they get OkHttp transitively.

**New file: `provider-api/src/main/java/com/opentune/provider/OkHttpAgent.kt`**
```kotlin
object OkHttpAgent {
    fun buildClient(
        proxyUrl: String? = null,
        builderAction: OkHttpClient.Builder.() -> Unit = {},
    ): OkHttpClient
}
```
- Parses proxy URL string → `java.net.Proxy(HTTP, InetSocketAddress)`
- Sets standard timeouts (30s connect, 120s read)
- Accepts a builder lambda for provider-specific interceptors (auth headers, logging)
- This is the **single source of truth** — both provider API calls and ExoPlayer streaming use it

### Phase 3: Provider API Contract Change

**Modify: `provider-api/src/main/java/com/opentune/provider/PlaybackContracts.kt`**
Add `httpClient: OkHttpClient? = null` to `PlaybackSpec`. The provider passes its pre-built proxied client here; the player reuses it.

**Modify: `provider-api/src/main/java/com/opentune/provider/ProviderContracts.kt`**

1. Add `supportsProxy: Boolean` property to `OpenTuneProvider` interface (default `false` for backward compat):
```kotlin
interface OpenTuneProvider {
    val providerType: String
    val supportsProxy: Boolean get() = false  // NEW
    // ... existing members
}
```

2. Change `OpenTuneProvider.createInstance` signature:
```kotlin
fun createInstance(
    values: Map<String, String>,
    capabilities: CodecCapabilities,
    proxyUrl: String? = null,  // NEW, default = null for backward compat
): OpenTuneProviderInstance
```

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyProvider.kt`**
Set `override val supportsProxy: Boolean = true`.

**Modify: `providers/smb/src/main/java/com/opentune/smb/SmbProvider.kt`**
Leave `supportsProxy` at default `false` — no proxy UI will appear for SMB.

### Phase 4: Emby Provider — Refactor to Shared Client

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyClientFactory.kt`**
Add an overload that accepts a pre-built `OkHttpClient`:
```kotlin
fun create(client: OkHttpClient, baseUrl: String, accessToken: String?): EmbyApi
```
Keep existing `create(baseUrl, accessToken, ...)` for `validateFields` (which runs before server is saved, so no proxy needed).

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyProvider.kt`**
In `createInstance`:
1. Call `OkHttpAgent.buildClient(proxyUrl) { add auth interceptors }`
2. Create `EmbyApi` via new factory overload
3. Pass both `api` and `httpClient` to `EmbyProviderInstance`

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyProviderInstance.kt`**
- Now holds a shared `EmbyApi` (created once at construction) instead of building `EmbyRepository` on every call
- Holds `httpClient: OkHttpClient` for `PlaybackSpec`
- All methods (`loadBrowsePage`, `searchItems`, `loadDetail`, `resolvePlayback`) use the shared `api`
- `resolvePlayback` returns `PlaybackSpec(..., httpClient = httpClient)`

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyPlaybackHooks.kt`**
Accept `EmbyApi` instead of raw credentials — reportPlaying/reportProgress/reportStopped call `api.reportXxx()`.

**`EmbyRepository.kt` becomes unused** — delete it. Its thin wrapper methods are inlined into `EmbyProviderInstance`.

### Phase 5: Wiring in the App Module

**Modify: `app/src/main/java/com/opentune/app/providers/ProviderInstanceRegistry.kt`**
- Add `proxyConfigDao` and `proxyAssignmentDao` constructor params
- In `buildInstance()`: lookup `ProxyAssignmentEntity` for the sourceId → get `proxyUrl` from `ProxyConfigEntity`
- Pass `proxyUrl` to `provider.createInstance(values, capabilities, proxyUrl)`
- `buildInstance` becomes `suspend fun`

**Modify: `app/src/main/java/com/opentune/app/OpenTuneApplication.kt`**
Pass new DAOs to `ProviderInstanceRegistry`.

**Modify: `app/src/main/java/com/opentune/app/providers/ServerConfigRepository.kt`**
- `submitAdd`: after inserting `ServerEntity`, upsert `ProxyAssignmentEntity` if a proxy was selected
- `submitEdit`: update `ProxyAssignmentEntity` if proxy selection changed
- `removeServer`: cascade-delete `ProxyAssignmentEntity`

### Phase 6: UI — Proxy Management + Per-Provider Selector

**New file: `app/src/main/java/com/opentune/app/ui/config/ProxyManageRoute.kt`**
- Lists all configured proxies with enable/disable toggle
- "Add Proxy" button → shows a text field for name + URL
- Delete proxy option (see "Delete proxy behavior" below)
- Uses `ProxyConfigDao.observeAll()` as a Flow for reactive UI

**Delete proxy behavior**

When a user deletes a named proxy (e.g. "Home Proxy"):
1. **Cascade-clear assignments**: All `ProxyAssignmentEntity` rows referencing that proxy's ID are deleted, so affected providers revert to direct (non-proxied) connections. No provider data is lost — only the proxy routing is removed.
2. **Show a snackbar/toast** listing how many providers were affected (e.g. "Proxy deleted. 2 servers now use direct connection.")
3. **Invalidate ProxyImageLoader** for all affected sourceIds so cover art switches back to the default ImageLoader.
4. **Active instances**: Currently-running provider instances still hold the old OkHttp client with the proxy baked in. They switch to direct only on the next `ProviderInstanceRegistry.getOrCreate()` call (e.g. after a reconnection or app restart). This is acceptable — it avoids mid-session network changes.

Implementation: `ProxyConfigDao` has a `delete(id)` method. Before calling it, query `ProxyAssignmentDao.getAssignmentsForProxy(id)` to get the list of affected `sourceId`s. Delete those assignments, invalidate image loaders, then delete the proxy config.

**Modify: `app/src/main/java/com/opentune/app/ui/config/ServerAddRoute.kt`**
- After provider fields, conditionally render a "Proxy" section **only if** `app.providerRegistry.provider(providerType).supportsProxy == true`:
  - Dropdown populated from `ProxyConfigDao.observeAll()` (filtering `isEnabled == true`)
  - Options: "None (direct)" + each proxy name
- Pass selected `proxyConfigId` to `ServerConfigRepository.submitAdd()`

**Modify: `app/src/main/java/com/opentune/app/ui/config/ServerEditRoute.kt`**
- Conditionally render the "Proxy" section **only if** the provider's `supportsProxy == true`
- Load current `ProxyAssignmentEntity` and pre-select the dropdown
- Save updated assignment on submit

**Modify: `app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt`**
Add route for `ProxyManageRoute`. Add entry point from Settings or Home screen.

**Add string resources** in `app/src/main/res/values/strings.xml`:
```xml
<string name="proxy_manage_title">Proxies</string>
<string name="proxy_add_title">Add Proxy</string>
<string name="proxy_name_hint">Proxy name</string>
<string name="proxy_url_hint">http://192.168.1.100:8888</string>
<string name="proxy_section_title">Proxy</string>
<string name="proxy_none">None (direct connection)</string>
```

### Phase 7: Player Module — Reuse Provided HTTP Client

**Modify: `player/src/main/java/com/opentune/player/PlaybackSpecExt.kt`**
```kotlin
internal fun PlaybackSpec.toMediaSource(context: Context): MediaSource {
    // If provider supplied a client, reuse it
    val okHttp = httpClient ?: run {
        // Fallback: create inline client (current behavior for SMB/custom sources)
        OkHttpClient.Builder().apply { ... }.build()
    }
    // If client was provided but has no auth headers baked in,
    // add them from urlSpec.headers
    val effectiveClient = if (httpClient != null && spec.headers.isNotEmpty()) {
        okHttp.newBuilder().addInterceptor { chain -> ... }.build()
    } else {
        okHttp
    }
    // ... rest unchanged
}
```

**Modify: `player/src/main/java/com/opentune/player/subtitle/SubtitleController.kt`**
In `selectFromSpec()` (line ~245), replace `DefaultHttpDataSource.Factory()` with:
```kotlin
val httpFactory = if (specState.value.httpClient != null) {
    OkHttpDataSource.Factory(specState.value.httpClient)
} else {
    DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(specState.value.subtitleHeaders)
}
```
Already has OkHttp dependency (via media3-okhttp in player build.gradle).

### Phase 8: Coil Cover Art — Per-Source Proxied ImageLoader

**New file: `app/src/main/java/com/opentune/app/image/ProxyImageLoader.kt`**
```kotlin
object ProxyImageLoader {
    private val loaders = mutableMapOf<String, ImageLoader>()

    fun get(sourceId: String, proxyUrl: String?, app: Application): ImageLoader
    fun invalidate(sourceId: String)
    fun clear()
}
```
- Builds an `ImageLoader` (Coil 2.x / 3.x) with a custom `OkHttpImageDownloader` using the proxied OkHttpClient
- Caches ImageLoader instances keyed by sourceId
- Invalidate on proxy config change or server removal

**Modify: `app/src/main/java/com/opentune/app/ui/catalog/MediaEntryComponent.kt`**
Pass `sourceId` to the component. Use `ProxyImageLoader.get(sourceId, ...)` as `imageLoader` param for `AsyncImage`.

**Modify: `app/src/main/java/com/opentune/app/ui/catalog/ThumbEntryComponent.kt`**
Same pattern — use proxied ImageLoader for thumbnail loading.

**Note**: These components currently receive just the URL string. They need `sourceId` threaded through from the parent (BrowseScreen, DetailScreen, etc.). This is a moderate refactor but localized to the catalog UI layer.

## Data Flow

```
UI: ProxyManageRoute
  └─ User adds proxy "Home Proxy" → ProxyConfigEntity saved

UI: ServerAddRoute
  └─ User selects "Home Proxy" from dropdown
  └─ submitAdd() saves ServerEntity + ProxyAssignmentEntity

ProviderInstanceRegistry.getOrCreate(sourceId)
  └─ Reads ServerEntity + ProxyAssignmentEntity → resolves proxyUrl
  └─ provider.createInstance(values, capabilities, proxyUrl)

EmbyProvider.createInstance(values, capabilities, proxyUrl)
  └─ OkHttpAgent.buildClient(proxyUrl) { auth interceptors } → OkHttpClient
  └─ EmbyApi = Retrofit with this client
  └─ EmbyProviderInstance(fields, api, httpClient, capabilities)

EmbyProviderInstance (all catalog methods)
  └─ Uses shared EmbyApi → all traffic goes through proxy

EmbyProviderInstance.resolvePlayback(...)
  └─ PlaybackSpec(..., httpClient = httpClient)

OpenTunePlayerScreen
  └─ spec.toMediaSource(context) → reuses spec.httpClient for media streaming
  └─ SubtitleController.selectFromSpec() → reuses spec.httpClient for subtitle URLs

Coil ImageLoader
  └─ ProxyImageLoader.get(sourceId) → ImageLoader with proxied OkHttp downloader
  └─ MediaEntryComponent / ThumbEntryComponent → cover art through proxy
```

## Traffic Coverage

| Traffic Type | Through Proxy? | How |
|---|---|---|
| Emby API calls (catalog, search, detail) | Yes | Shared OkHttpAgent client in EmbyApi/Retrofit |
| Emby playback URL fetching | Yes | Same shared client |
| ExoPlayer media streaming | Yes | PlaybackSpec.httpClient reused in toMediaSource() |
| ExoPlayer subtitle sidecar loading | Yes | PlaybackSpec.httpClient reused in SubtitleController |
| Emby playback hooks (report progress, etc.) | Yes | Shared EmbyApi |
| Coil cover art / thumbnails | Yes | ProxyImageLoader with proxied OkHttpImageDownloader |
| SMB protocol traffic | No | SMBJ raw protocol, not HTTP — intentionally excluded |

## Critical Files to Modify

| File | Change |
|---|---|
| `storage/.../ProxyConfigEntity.kt` | **NEW** — entity |
| `storage/.../ProxyAssignmentEntity.kt` | **NEW** — entity |
| `storage/.../Daos.kt` | Add ProxyConfigDao + ProxyAssignmentDao |
| `storage/.../OpenTuneDatabase.kt` | Add entities, DAOs, bump version |
| `storage/.../StorageBindings.kt` | Add DAOs |
| `provider-api/.../OkHttpAgent.kt` | **NEW** — single-source HTTP client factory |
| `provider-api/.../PlaybackContracts.kt` | Add `httpClient` to PlaybackSpec |
| `provider-api/.../ProviderContracts.kt` | Add `proxyUrl` param to createInstance |
| `providers/emby/.../EmbyClientFactory.kt` | Add pre-built client overload |
| `providers/emby/.../EmbyProvider.kt` | Use OkHttpAgent in createInstance |
| `providers/emby/.../EmbyProviderInstance.kt` | Hold shared api + httpClient |
| `providers/emby/.../EmbyPlaybackHooks.kt` | Accept EmbyApi |
| `providers/emby/.../EmbyRepository.kt` | **DELETE** |
| `player/.../PlaybackSpecExt.kt` | Reuse spec.httpClient |
| `player/.../subtitle/SubtitleController.kt` | Use spec.httpClient for subtitles |
| `app/.../ProviderInstanceRegistry.kt` | Lookup + pass proxyUrl |
| `app/.../OpenTuneApplication.kt` | Wire DAOs |
| `app/.../ServerConfigRepository.kt` | Save/load proxy assignments |
| `app/.../ProxyManageRoute.kt` | **NEW** — proxy management UI |
| `app/.../ServerAddRoute.kt` | Add proxy selector dropdown |
| `app/.../ServerEditRoute.kt` | Add proxy selector dropdown |
| `app/.../OpenTuneNavHost.kt` | Add proxy manage route |
| `app/.../ProxyImageLoader.kt` | **NEW** — per-source Coil ImageLoader |
| `app/.../MediaEntryComponent.kt` | Use proxied ImageLoader |
| `app/.../ThumbEntryComponent.kt` | Use proxied ImageLoader |

## Verification

1. Build: `./gradlew assembleDebug` — all modules compile
2. Add a proxy in the new Manage Proxies screen
3. Add an Emby provider with the proxy selected
4. Verify catalog browsing works (API calls through proxy)
5. Play a video — verify media streaming uses the proxied client
6. Enable external subtitles — verify sidecar subtitle loading uses the proxy
7. Verify cover art loads through the proxy
8. Test switching proxy assignment on an existing provider
9. Test removing a proxy that's assigned to a provider
