# Proxy System Implementation Plan

## Context

OpenTune is a multi-provider media player app (Emby, SMB, etc.) with two HTTP traffic layers that both need proxy support:
1. **Lower layer**: Provider API calls (catalog, search, playback resolution) — Emby uses Retrofit+OkHttp
2. **Upper layer**: ExoPlayer media streaming — creates its own OkHttpClient for streaming URLs and subtitle loading

The goal is a **single source of truth** for HTTP clients: the provider layer builds a proxied OkHttp client, and the player layer reuses it.

**Key architectural decision**: Proxy is modeled as a first-class provider type, mirroring how `OpenTuneProvider` works. Each proxy type defines its own configuration fields, validates them, implements its own proxy logic internally, and exposes an `OkHttpClient`. The app doesn't care how the proxy works internally.

## Architecture Summary

- `:provider-api` — Pure Kotlin contracts (interfaces, data classes) — gains `ProxyProvider` interface
- `:providers:emby` — Emby provider (Retrofit + OkHttp for HTTP traffic)
- `:providers:smb` — SMB provider (SMBJ, no HTTP, sets `supportsProxy = false`)
- `:providers:coil` — **NEW** — First proxy provider (Coil-based HTTP proxy)
- `:player` — ExoPlayer wrapper (OkHttp for media streaming)
- `:storage` — Room database
- `:app` — Android UI, provider registration, navigation

## Implementation Plan

### Phase 1: `ProxyProvider` Interface + Registry (in `:provider-api`)

**New file: `provider-api/src/main/java/com/opentune/provider/ProxyProvider.kt`**

```kotlin
interface ProxyProvider {
    val proxyType: String

    /**
     * Configuration fields this proxy type needs. Rendered by the generic proxy-add UI.
     */
    fun getFieldsSpec(): List<ServerFieldSpec>

    /**
     * Validate the supplied fields (e.g. test connectivity).
     */
    suspend fun validateFields(values: Map<String, String>): ValidationResult

    /**
     * Build and return a fully-configured OkHttpClient that routes traffic through this proxy.
     * The app does not care how the proxy works internally — it just uses the returned client.
     */
    fun createClient(values: Map<String, String>): OkHttpClient

    /**
     * Optional bootstrap (e.g. initialize native libs, create temp dirs).
     */
    fun bootstrap(context: PlatformContext) {}
}
```

**New file: `provider-api/src/main/java/com/opentune/provider/ProxyProviderRegistry.kt`**

```kotlin
class ProxyProviderRegistry private constructor(
    private val providers: Map<String, ProxyProvider>,
) {
    fun proxy(proxyType: String): ProxyProvider = providers[proxyType]
        ?: error("Unknown proxy provider: $proxyType")
    fun allProxies(): Collection<ProxyProvider> = providers.values

    companion object {
        fun discover(): ProxyProviderRegistry {
            val list = ServiceLoader
                .load(ProxyProvider::class.java, ProxyProvider::class.java.classLoader)
                .toList()
            return ProxyProviderRegistry(list.associateBy { it.proxyType })
        }
    }
}
```

**Modify: `provider-api/build.gradle.kts`**
Add `implementation(libs.okhttp)` — both `:providers:emby` and `:player` depend on `:provider-api` so they get OkHttp transitively.

### Phase 2: Storage — Proxy Config Entity & DAO

**New file: `storage/src/main/java/com/opentune/storage/ProxyConfigEntity.kt`**

Mirrors `ServerEntity` structure — stores proxy type + provider-specific fields:

```kotlin
@Entity(tableName = "proxy_configs", primaryKeys = ["id"])
data class ProxyConfigEntity(
    @PrimaryKey val id: String,
    val proxyType: String,       // e.g. "coil" — matches ProxyProvider.proxyType
    val displayName: String,     // user-chosen name like "Home Proxy"
    val fieldsJson: String,      // proxy-provider-specific config blob
    val isEnabled: Boolean = true,
    val createdAtEpochMs: Long,
)
```

**New file: `storage/src/main/java/com/opentune/storage/ProxyAssignmentEntity.kt`**

Maps media providers to proxy configs:

```kotlin
@Entity(tableName = "proxy_assignments", primaryKeys = ["sourceId"])
data class ProxyAssignmentEntity(
    @PrimaryKey val sourceId: String,
    val proxyConfigId: String?,  // FK to proxy_configs.id, null = no proxy
)
```

**Modify: `storage/src/main/java/com/opentune/storage/Daos.kt`**

Add two DAOs:
- `ProxyConfigDao` — observeAll (Flow), getBySourceId, insert, update, delete
- `ProxyAssignmentDao` — upsert, getBySourceId, deleteBySourceId, **getAssignmentsForProxy** (returns list of sourceIds assigned to a given proxyConfigId)

**Modify: `storage/src/main/java/com/opentune/storage/OpenTuneDatabase.kt`**

Add both entities, add DAO accessors. Bump DB version. Project uses `fallbackToDestructiveMigration`.

**Modify: `storage/src/main/java/com/opentune/storage/StorageBindings.kt`**

Add `proxyConfigDao` and `proxyAssignmentDao`.

### Phase 3: `OpenTuneProvider` Gets `supportsProxy` Flag

**Modify: `provider-api/src/main/java/com/opentune/provider/ProviderContracts.kt`**

Add to `OpenTuneProvider` interface:
```kotlin
val supportsProxy: Boolean get() = false  // default: no proxy UI for this provider
```

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyProvider.kt`**

```kotlin
override val supportsProxy: Boolean = true
```

**SmbProvider** — leave at default `false`. No proxy UI will appear for SMB.

### Phase 4: Provider API Contract — `createInstance` Accepts `OkHttpClient`

**Modify: `provider-api/src/main/java/com/opentune/provider/PlaybackContracts.kt`**

Add `httpClient: OkHttpClient? = null` to `PlaybackSpec`. The media provider passes the proxy provider's client here; the player reuses it.

**Modify: `provider-api/src/main/java/com/opentune/provider/ProviderContracts.kt`**

Change `createInstance` signature:
```kotlin
fun createInstance(
    values: Map<String, String>,
    capabilities: CodecCapabilities,
    httpClient: OkHttpClient? = null,  // NEW — the proxied client from the assigned proxy provider
): OpenTuneProviderInstance
```

### Phase 5: Coil Proxy Provider (First Implementation)

**New module: `providers/coil/`**

```
providers/coil/build.gradle.kts
providers/coil/src/main/java/com/opentune/coil/CoilProxyProvider.kt
providers/coil/src/main/java/com/opentune/coil/CoilProxyFieldsJson.kt
```

**`providers/coil/build.gradle.kts`** — depends on `:provider-api`, OkHttp, kotlinx-serialization.

**`CoilProxyProvider.kt`** — implements `ProxyProvider`:
- `proxyType = "coil"`
- `getFieldsSpec()` returns fields like: host, port, username, password (or whatever Coil proxy needs)
- `validateFields()` tests connectivity to the Coil proxy
- `createClient()` builds an `OkHttpClient` that routes through the Coil proxy
- Registered via ServiceLoader (`META-INF/services/com.opentune.provider.ProxyProvider`)

**`CoilProxyFieldsJson.kt`** — serializable config blob (mirrors `EmbyServerFieldsJson` pattern).

### Phase 6: Emby Provider — Accept Proxied Client

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyClientFactory.kt`**

Add an overload that accepts a pre-built `OkHttpClient`:
```kotlin
fun create(client: OkHttpClient, baseUrl: String, accessToken: String?): EmbyApi
```
Keep existing `create(baseUrl, accessToken, ...)` for `validateFields` (no proxy needed during validation).

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyProvider.kt`**

In `createInstance`:
1. Receive the `httpClient` from the registry
2. If `httpClient != null`, use `EmbyClientFactory.create(httpClient, baseUrl, token)`
3. If `httpClient == null`, use the existing `create(baseUrl, token, ...)` (direct connection)
4. Pass the client (either shared or new) to `EmbyProviderInstance`

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyProviderInstance.kt`**

- Now holds a shared `EmbyApi` (created once at construction)
- Holds `httpClient: OkHttpClient` for `PlaybackSpec`
- All methods use the shared `api`
- `resolvePlayback` returns `PlaybackSpec(..., httpClient = httpClient)`

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyPlaybackHooks.kt`**

Accept `EmbyApi` instead of raw credentials.

**`EmbyRepository.kt` becomes unused** — delete it.

### Phase 7: Wiring in the App Module

**Modify: `app/src/main/java/com/opentune/app/providers/ProviderInstanceRegistry.kt`**

- Add `proxyConfigDao` and `proxyAssignmentDao` constructor params
- In `buildInstance()`:
  1. Lookup `ProxyAssignmentEntity` for the sourceId → get `proxyConfigId`
  2. If `proxyConfigId != null`, load `ProxyConfigEntity` → decode `fieldsJson` → find `ProxyProvider` → call `createClient(fields)` to get `OkHttpClient`
  3. Pass the `httpClient` to `provider.createInstance(values, capabilities, httpClient)`
- `buildInstance` becomes `suspend fun`

**Modify: `app/src/main/java/com/opentune/app/OpenTuneApplication.kt`**

- Create `ProxyProviderRegistry` via `discover()`, call `bootstrap()` on each
- Pass new DAOs + `ProxyProviderRegistry` to `ProviderInstanceRegistry`

**Modify: `app/src/main/java/com/opentune/app/providers/ServerConfigRepository.kt`**

- `submitAdd`: after inserting `ServerEntity`, upsert `ProxyAssignmentEntity` if a proxy was selected
- `submitEdit`: update `ProxyAssignmentEntity` if proxy selection changed
- `removeServer`: cascade-delete `ProxyAssignmentEntity`

### Phase 8: UI — Proxy Management + Per-Provider Selector

**New file: `app/src/main/java/com/opentune/app/ui/config/ProxyManageRoute.kt`**

- Lists all configured proxies showing their `displayName` + `proxyType` label
- Enable/disable toggle per proxy
- "Add Proxy" → navigates to `ProxyTypePickerRoute`
- Delete proxy (see "Delete proxy behavior" below)
- Uses `ProxyConfigDao.observeAll()` as Flow for reactive UI

**New file: `app/src/main/java/com/opentune/app/ui/config/ProxyTypePickerRoute.kt`**

Simple type picker listing all `proxyProvider.allProxies()` (e.g. "Coil"). Selecting one navigates to `ServerFormRoute(entityType = PROXY, entryType = selectedType)`.

**Refactor: `app/src/main/java/com/opentune/app/ui/config/ServerFormRoute.kt`**

Replaces `ServerAddRoute`, `ServerEditRoute`, `ProxyAddRoute`, and `ProxyEditRoute` with a single unified form composable:

```kotlin
enum class EntityType { SERVER, PROXY }

@Composable
fun ServerFormRoute(
    entityType: EntityType,       // SERVER or PROXY
    entryType: String,            // "emby", "smb", "coil", etc.
    existingId: String? = null,   // null = add mode, non-null = edit mode
    onDone: () -> Unit,
)
```

Internal dispatch logic:
- **Fields**: `entityType == SERVER` → `providerRegistry.provider(entryType).getFieldsSpec()`, `PROXY` → `proxyProviderRegistry.proxy(entryType).getFieldsSpec()`
- **Validation**: calls the appropriate provider's `validateFields()`
- **Submit add**: `SERVER` → `ServerConfigRepository.submitAdd()` + `ProxyAssignmentEntity`; `PROXY` → inserts `ProxyConfigEntity`
- **Submit edit**: `SERVER` → `ServerConfigRepository.submitEdit()` + updates `ProxyAssignmentEntity`; `PROXY` → updates `ProxyConfigEntity`
- **Draft storage**: keyed by `(entityType, entryType)` — same DataStore draft mechanism

This reduces 4 form routes to 1 shared component + a small `ProxyTypePickerRoute`.

**Within ServerFormRoute when `entityType == SERVER` and `existingId == null` (add mode) or `existingId != null` (edit mode)**:
If the current media provider has `supportsProxy == true`, render an additional "Proxy" section after the provider fields:
- Dropdown populated from `ProxyConfigDao.observeAll()` (filtering `isEnabled == true`)
- Options: "None (direct)" + each proxy's `displayName`
- For edit mode, load current `ProxyAssignmentEntity` and pre-select

**Delete proxy behavior:**

When a user deletes a named proxy config:
1. **Cascade-clear assignments**: All `ProxyAssignmentEntity` rows referencing that proxy's ID are deleted. Affected media providers revert to direct connections. No provider data is lost.
2. **User feedback**: Show a snackbar listing how many servers were affected (e.g. "Proxy deleted. 2 servers now use direct connection.")
3. **Invalidate image loaders**: `ProxyImageLoader.invalidate(sourceId)` for each affected sourceId
4. **Active instances**: Currently-running provider instances keep their old `OkHttpClient` until the next `ProviderInstanceRegistry.getOrCreate()` call. Avoids mid-session network disruption.

**Modify: `app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt`**

Add routes for `ProxyManageRoute`, `ProxyTypePickerRoute`, `ServerFormRoute` (replaces existing server add/edit routes). Add entry point from Settings/Home.

**Delete**: `app/src/main/java/com/opentune/app/ui/config/ServerAddRoute.kt` and `ServerEditRoute.kt` — replaced by `ServerFormRoute`.

**Add string resources** in `app/src/main/res/values/strings.xml`:
```xml
<string name="proxy_manage_title">Proxies</string>
<string name="proxy_add_title">Add Proxy</string>
<string name="proxy_section_title">Proxy</string>
<string name="proxy_none">None (direct connection)</string>
<string name="proxy_select_type">Select proxy type</string>
```

### Phase 9: Player Module — Reuse Provided HTTP Client

**Modify: `player/src/main/java/com/opentune/player/PlaybackSpecExt.kt`**

```kotlin
internal fun PlaybackSpec.toMediaSource(context: Context): MediaSource {
    val factory = customMediaSourceFactory
    if (factory != null) {
        @Suppress("UNCHECKED_CAST")
        return (factory as () -> MediaSource)()
    }
    val spec = checkNotNull(urlSpec) { "..." }

    // Reuse the provided client, or fall back to creating one
    val okHttp = httpClient ?: OkHttpClient.Builder().apply {
        if (spec.headers.isNotEmpty()) {
            addInterceptor { chain ->
                val req = chain.request().newBuilder().apply {
                    spec.headers.forEach { (k, v) -> header(k, v) }
                }.build()
                chain.proceed(req)
            }
        }
    }.build()

    // If a proxied client was provided and there are additional headers, wrap it
    val effectiveClient = if (httpClient != null && spec.headers.isNotEmpty()) {
        okHttp.newBuilder().addInterceptor { chain ->
            val req = chain.request().newBuilder().apply {
                spec.headers.forEach { (k, v) -> header(k, v) }
            }.build()
            chain.proceed(req)
        }.build()
    } else {
        okHttp
    }

    val dataSourceFactory = OkHttpDataSource.Factory(effectiveClient)
    // ... rest unchanged
}
```

**Modify: `player/src/main/java/com/opentune/player/subtitle/SubtitleController.kt`**

In `selectFromSpec()` (line ~245), replace `DefaultHttpDataSource.Factory()`:
```kotlin
val httpFactory = if (specState.value.httpClient != null) {
    OkHttpDataSource.Factory(specState.value.httpClient)
} else {
    DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(specState.value.subtitleHeaders)
}
```

### Phase 10: Coil Cover Art — Per-Source Proxied ImageLoader

**New file: `app/src/main/java/com/opentune/app/image/ProxyImageLoader.kt`**

```kotlin
object ProxyImageLoader {
    private val loaders = mutableMapOf<String, ImageLoader>()

    fun get(sourceId: String, httpClient: OkHttpClient?, app: Application): ImageLoader
    fun invalidate(sourceId: String)
    fun clear()
}
```

- Builds an `ImageLoader` (Coil) with a custom `OkHttpImageDownloader` using the proxied `OkHttpClient`
- Caches `ImageLoader` instances keyed by `sourceId`
- Invalidate on proxy config change, proxy deletion, or server removal

**Modify: `app/src/main/java/com/opentune/app/ui/catalog/MediaEntryComponent.kt`**

Pass `sourceId` to the component. Use `ProxyImageLoader.get(sourceId, httpClient, app)` as `imageLoader` for `AsyncImage`.

**Modify: `app/src/main/java/com/opentune/app/ui/catalog/ThumbEntryComponent.kt`**

Same pattern — use proxied ImageLoader for thumbnail loading.

**Note**: These components currently receive just the URL string. They need `sourceId` threaded through from parent (BrowseScreen, DetailScreen, etc.). Moderate refactor but localized.

## Data Flow

```
UI: ProxyManageRoute
  └─ User adds proxy → picks type "Coil" → fills Coil fields → validates → saves ProxyConfigEntity

UI: ServerAddRoute
  └─ (only shown if provider.supportsProxy == true)
  └─ User selects "Home Proxy" from dropdown
  └─ submitAdd() saves ServerEntity + ProxyAssignmentEntity

ProviderInstanceRegistry.getOrCreate(sourceId)
  └─ Reads ServerEntity + ProxyAssignmentEntity → gets proxyConfigId
  └─ If proxyConfigId != null:
       ├─ Loads ProxyConfigEntity → decodes fieldsJson
       ├─ Finds ProxyProvider by proxyType
       └─ proxyProvider.createClient(fields) → OkHttpClient
  └─ provider.createInstance(values, capabilities, httpClient)

EmbyProvider.createInstance(values, capabilities, httpClient)
  └─ If httpClient != null: EmbyApi = Retrofit with this client
  └─ If httpClient == null: EmbyApi = Retrofit with default client (direct)
  └─ EmbyProviderInstance(fields, api, httpClient, capabilities)

EmbyProviderInstance (all catalog methods)
  └─ Uses shared EmbyApi → all traffic goes through proxy

EmbyProviderInstance.resolvePlayback(...)
  └─ PlaybackSpec(..., httpClient = httpClient)

OpenTunePlayerScreen
  └─ spec.toMediaSource(context) → reuses spec.httpClient for media streaming
  └─ SubtitleController.selectFromSpec() → reuses spec.httpClient for subtitle URLs

Coil ImageLoader
  └─ ProxyImageLoader.get(sourceId, httpClient) → ImageLoader with proxied OkHttp downloader
  └─ MediaEntryComponent / ThumbEntryComponent → cover art through proxy
```

## Traffic Coverage

| Traffic Type | Through Proxy? | How |
|---|---|---|
| Emby API calls (catalog, search, detail) | Yes (if assigned) | Proxy provider's OkHttpClient via Retrofit |
| Emby playback URL fetching | Yes (if assigned) | Same shared client |
| ExoPlayer media streaming | Yes (if assigned) | PlaybackSpec.httpClient reused in toMediaSource() |
| ExoPlayer subtitle sidecar loading | Yes (if assigned) | PlaybackSpec.httpClient reused in SubtitleController |
| Emby playback hooks (report progress, etc.) | Yes (if assigned) | Shared EmbyApi built on proxy client |
| Coil cover art / thumbnails | Yes (if assigned) | ProxyImageLoader with proxied OkHttpImageDownloader |
| SMB protocol traffic | No | SMBJ raw protocol, not HTTP — `supportsProxy = false` |

## Critical Files to Modify

| File | Change |
|---|---|
| `provider-api/.../ProxyProvider.kt` | **NEW** — proxy provider interface |
| `provider-api/.../ProxyProviderRegistry.kt` | **NEW** — ServiceLoader registry |
| `provider-api/.../PlaybackContracts.kt` | Add `httpClient` to PlaybackSpec |
| `provider-api/.../ProviderContracts.kt` | Add `supportsProxy`, change `createInstance` sig |
| `providers/coil/` | **NEW module** — first proxy provider impl |
| `storage/.../ProxyConfigEntity.kt` | **NEW** — entity |
| `storage/.../ProxyAssignmentEntity.kt` | **NEW** — entity |
| `storage/.../Daos.kt` | Add ProxyConfigDao + ProxyAssignmentDao |
| `storage/.../OpenTuneDatabase.kt` | Add entities, DAOs, bump version |
| `storage/.../StorageBindings.kt` | Add DAOs |
| `providers/emby/.../EmbyClientFactory.kt` | Add pre-built client overload |
| `providers/emby/.../EmbyProvider.kt` | `supportsProxy = true`, use provided client |
| `providers/emby/.../EmbyProviderInstance.kt` | Hold shared api + httpClient |
| `providers/emby/.../EmbyPlaybackHooks.kt` | Accept EmbyApi |
| `providers/emby/.../EmbyRepository.kt` | **DELETE** |
| `player/.../PlaybackSpecExt.kt` | Reuse spec.httpClient |
| `player/.../subtitle/SubtitleController.kt` | Use spec.httpClient for subtitles |
| `app/.../ProviderInstanceRegistry.kt` | Lookup proxy → build client → pass to provider |
| `app/.../OpenTuneApplication.kt` | Wire ProxyProviderRegistry + DAOs |
| `app/.../ServerConfigRepository.kt` | Save/load proxy assignments |
| `app/.../ProxyManageRoute.kt` | **NEW** — proxy management UI |
| `app/.../ProxyTypePickerRoute.kt` | **NEW** — proxy type picker |
| `app/.../ServerFormRoute.kt` | **NEW** — replaces ServerAddRoute + ServerEditRoute |
| `app/.../ServerAddRoute.kt` | **DELETE** — replaced by ServerFormRoute |
| `app/.../ServerEditRoute.kt` | **DELETE** — replaced by ServerFormRoute |
| `app/.../OpenTuneNavHost.kt` | Add proxy routes, switch server add/edit to ServerFormRoute |
| `app/.../ProxyImageLoader.kt` | **NEW** — per-source Coil ImageLoader |
| `app/.../MediaEntryComponent.kt` | Use proxied ImageLoader |
| `app/.../ThumbEntryComponent.kt` | Use proxied ImageLoader |

## Verification

1. Build: `./gradlew assembleDebug` — all modules compile
2. Go to Proxy Management → Add a Coil proxy with test credentials
3. Add an Emby provider — verify proxy dropdown appears
4. Add an SMB provider — verify proxy section does NOT appear
5. Assign the Coil proxy to the Emby server
6. Verify catalog browsing works (API calls through proxy)
7. Play a video — verify media streaming uses the proxied client
8. Enable external subtitles — verify subtitle loading uses the proxy
9. Verify cover art loads through the proxy
10. Delete the proxy — verify affected servers revert to direct connection with a snackbar message
