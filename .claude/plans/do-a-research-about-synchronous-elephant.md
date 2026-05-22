# Proxy System Implementation Plan

## Context

OpenTune is a multi-provider media player app (Emby, SMB, JS, etc.) with two HTTP traffic layers that both need proxy support:
1. **Lower layer**: Provider API calls (catalog, search, playback resolution) — Emby uses Retrofit+OkHttp via `EmbyClientFactory`
2. **Upper layer**: ExoPlayer media streaming — creates its own `OkHttpClient` in `PlaybackSpecExt.kt` for streaming URLs and subtitle loading

The goal is a **single source of truth** for HTTP clients: the app layer resolves a proxied `OkHttpClient` per endpoint and threads it through to both layers via `PlaybackSpec`. Provider and player code stay unaware of where the client came from.

**Key architectural decision**: Proxy is modeled as a first-class type mirroring `OpenTuneProvider`. Each proxy type defines its own fields, validates them, and produces an `OkHttpClient`. The app doesn't care how internally. The `OkHttpClient` is resolved in `EndpointClientRegistry` and stored in an `EndpointHandle` wrapper — it does not flow through `OpenTuneProvider.createClient()` or `EmbyProviderInstance`, only into `PlaybackSpec` for the player layer.

## Architecture Summary

- `:contracts` — Pure Kotlin contracts — gains `ProxyProvider` interface in `com.opentune.proxy`
- `:providers:emby` — Emby provider (Retrofit + OkHttp)
- `:providers:smb` — SMB provider (SMBJ, no HTTP, `supportsProxy = false`)
- `:providers:js` — JS provider (QuickJS, `supportsProxy = false`)
- `:proxy:http` — **NEW** — HTTP CONNECT proxy provider implementation
- `:player` — ExoPlayer wrapper
- `:storage` — Room database
- `:app` — Android UI, provider registration, navigation

## Implementation Plan

### Phase 1: `ProxyProvider` Interface + Registry (in `:contracts`)

**New file: `contracts/src/main/java/com/opentune/proxy/ProxyProvider.kt`**

```kotlin
interface ProxyProvider {
    val proxyType: String
    fun getFieldsSpec(): List<ProviderFieldSpec>
    suspend fun validateFields(values: Map<String, String>): ValidationResult
    fun createClient(values: Map<String, String>): OkHttpClient
    fun bootstrap(context: PlatformContext) {}
}
```

`ProviderFieldSpec`, `ValidationResult`, and `PlatformContext` are imported from `com.opentune.provider`.

**New file: `contracts/src/main/java/com/opentune/proxy/ProxyProviderRegistry.kt`**

```kotlin
class ProxyProviderRegistry private constructor(
    private val providers: Map<String, ProxyProvider>,
) {
    fun proxy(proxyType: String): ProxyProvider =
        providers[proxyType] ?: error("Unknown proxy provider: $proxyType")
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

**Modify: `contracts/build.gradle.kts`**
Add `compileOnly(libs.okhttp)` — `:providers:emby` and `:player` already depend on OkHttp directly; contracts only needs it for the interface signature.

### Phase 2: Storage — Proxy Config Entity & DAO

**New entities in `storage/src/main/java/com/opentune/storage/Entities.kt`**:

```kotlin
@Entity(tableName = "proxy_configs")
data class ProxyConfigEntity(
    @PrimaryKey val id: String,
    val proxyType: String,
    val displayName: String,
    val fieldsJson: String,
    val isEnabled: Boolean = true,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "proxy_assignments")
data class ProxyAssignmentEntity(
    @PrimaryKey val endpointId: String,
    val proxyConfigId: String?,
)
```

**New DAOs in `storage/src/main/java/com/opentune/storage/Daos.kt`**:

```kotlin
@Dao
interface ProxyConfigDao {
    @Query("SELECT * FROM proxy_configs ORDER BY createdAtEpochMs ASC")
    fun observeAll(): Flow<List<ProxyConfigEntity>>

    @Query("SELECT * FROM proxy_configs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProxyConfigEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProxyConfigEntity)

    @Update
    suspend fun update(entity: ProxyConfigEntity)

    @Query("DELETE FROM proxy_configs WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ProxyAssignmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProxyAssignmentEntity)

    @Query("SELECT * FROM proxy_assignments WHERE endpointId = :endpointId LIMIT 1")
    suspend fun getByEndpointId(endpointId: String): ProxyAssignmentEntity?

    @Query("SELECT endpointId FROM proxy_assignments WHERE proxyConfigId = :proxyConfigId")
    suspend fun getEndpointIdsForProxy(proxyConfigId: String): List<String>

    @Query("DELETE FROM proxy_assignments WHERE endpointId = :endpointId")
    suspend fun deleteByEndpointId(endpointId: String)

    @Query("DELETE FROM proxy_assignments WHERE proxyConfigId = :proxyConfigId")
    suspend fun deleteByProxyConfigId(proxyConfigId: String)
}
```

**Modify: `storage/src/main/java/com/opentune/storage/OpenTuneDatabase.kt`**
Add both entities, add DAO accessors, bump version 9 → 10. Project uses `fallbackToDestructiveMigration`.

**Modify: `storage/src/main/java/com/opentune/storage/StorageBindings.kt`**
Add `proxyConfigDao` and `proxyAssignmentDao` to `OpenTuneStorageBindings`.

### Phase 3: `OpenTuneProvider` Gets `supportsProxy` Flag

**Modify: `contracts/src/main/java/com/opentune/provider/ProviderContracts.kt`**

```kotlin
val supportsProxy: Boolean get() = false
```

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyProvider.kt`**
```kotlin
override val supportsProxy: Boolean = true
```

SMB and JS — leave at default `false`.

### Phase 4: `EndpointHandle` — Proxy Client Resolved in the App Layer

**New file: `app/src/main/java/com/opentune/app/providers/EndpointHandle.kt`**

```kotlin
data class EndpointHandle(
    val client: EndpointClient,
    val httpClient: OkHttpClient?,  // null = direct connection
)
```

**Modify: `app/src/main/java/com/opentune/app/providers/EndpointClientRegistry.kt`**

- Store `EndpointHandle` instead of bare `EndpointClient`
- Add `proxyConfigDao`, `proxyAssignmentDao`, and `proxyProviderRegistry` constructor params
- In `buildHandle()` (replaces `buildClient()`):
  1. Decode `EndpointEntity.fieldsJson` → call `provider.createClient(values, capabilities)` → `EndpointClient`
  2. Look up `ProxyAssignmentEntity` for the `endpointId`
  3. If assigned: load `ProxyConfigEntity` → decode fields → `proxyProvider.createClient(fields)` → `OkHttpClient`
  4. Return `EndpointHandle(client, httpClient)`
- `buildHandle` is `suspend fun` due to DB lookups
- All public methods (`getOrCreate`, `registerClient`, `update`, `populateEager`) return / operate on `EndpointHandle`

`OpenTuneProvider.createClient()` signature stays unchanged — proxy is entirely invisible to providers.

### Phase 5: `PlaybackSpec` Carries the Proxy Client for the Player

**Modify: `contracts/src/main/java/com/opentune/provider/PlaybackContracts.kt`**

```kotlin
data class PlaybackSpec(
    ...
    val httpClient: OkHttpClient? = null,  // injected by app layer, not by the provider
)
```

**Modify: `app/src/main/java/com/opentune/app/ui/catalog/PlayerRoute.kt`** (or wherever `getPlaybackSpec` is called)

After resolving `PlaybackSpec` from the provider, inject the handle's client:
```kotlin
val handle = endpointClientRegistry.getOrCreate(endpointId)
val spec = handle.client.getPlaybackSpec(itemRef, startMs)
    .copy(httpClient = handle.httpClient)
```

This keeps `EmbyProviderInstance` and all other `EndpointClient` implementations unaware of proxy entirely.

### Phase 6: Emby Provider — Shared API Client

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyClientFactory.kt`**

Add an overload that accepts a pre-built `OkHttpClient`:
```kotlin
fun create(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String?,
    json: Json = embyJson(),
): EmbyApi
```
The existing `create(baseUrl, accessToken, ...)` remains for `validateFields`.

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyRepository.kt`**

Accept `EmbyApi` as a constructor param instead of constructing it internally — removes the hidden client construction.

**Modify: `providers/emby/src/main/java/com/opentune/emby/EmbyProviderInstance.kt`**

Build `EmbyApi` once at construction from `EmbyClientFactory.create(baseUrl, accessToken)` and hold it as a field. Pass to `EmbyRepository` directly. No proxy awareness needed here.

### Phase 7: HTTP Proxy Provider

**New module: `proxy/http/`**

```
proxy/http/build.gradle.kts
proxy/http/src/main/java/com/opentune/proxy/http/HttpProxyProvider.kt
proxy/http/src/main/java/com/opentune/proxy/http/HttpProxyFieldsJson.kt
proxy/http/src/main/resources/META-INF/services/com.opentune.proxy.ProxyProvider
```

`HttpProxyProvider` implements `ProxyProvider`:
- `proxyType = "http"`
- `getFieldsSpec()` — host, port, username (optional), password (optional)
- `validateFields()` — test HTTP CONNECT to the proxy endpoint
- `createClient()` — `OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))).build()` plus optional `ProxyAuthenticator`

### Phase 8: Wiring in the App Module

**Modify: `app/src/main/java/com/opentune/app/OpenTuneApplication.kt`**

- Build `ProxyProviderRegistry` via `discover()`, call `bootstrap()` on each
- Pass `proxyProviderRegistry`, `proxyConfigDao`, `proxyAssignmentDao` to `EndpointClientRegistry`

**Modify: `app/src/main/java/com/opentune/app/providers/EndpointConfigRepository.kt`**

- `submitAdd`: after inserting `EndpointEntity`, upsert `ProxyAssignmentEntity` if a proxy was selected
- `submitEdit`: update `ProxyAssignmentEntity` if proxy selection changed
- `removeEndpoint`: cascade-delete `ProxyAssignmentEntity`

### Phase 9: UI — Unified Form Route + Proxy Management

**Replace `EndpointAddRoute` and `EndpointEditRoute` with a single `ProviderFormRoute`**

All four config forms (endpoint add, endpoint edit, proxy add, proxy edit) share the same shape: render `ProviderFieldSpec` fields, validate, submit. Unify into one composable:

```kotlin
enum class FormEntityType { ENDPOINT, PROXY }

@Composable
fun ProviderFormRoute(
    entityType: FormEntityType,
    protocol: String,           // provider protocol or proxy type
    existingId: String? = null, // null = add, non-null = edit
    onDone: () -> Unit,
)
```

Internal dispatch:
- **Fields**: `ENDPOINT` → `providerRegistry.provider(protocol).getFieldsSpec()`; `PROXY` → `proxyProviderRegistry.proxy(protocol).getFieldsSpec()`
- **Validation/submit**: calls the appropriate `validateFields()` and persists `EndpointEntity` or `ProxyConfigEntity`
- **Draft storage**: keyed by `(entityType, protocol)` — same DataStore draft mechanism as today
- **Proxy selector section**: shown only when `entityType == ENDPOINT && provider.supportsProxy == true` — dropdown from `ProxyConfigDao.observeAll()` (enabled only) with "None (direct)" + each proxy's `displayName`; pre-populated from `ProxyAssignmentEntity` in edit mode

**Delete**: `EndpointAddRoute.kt` and `EndpointEditRoute.kt`.

**New file: `app/src/main/java/com/opentune/app/ui/config/ProxyManageRoute.kt`**

Settings screen listing all saved proxy configs. Shows `displayName` + `proxyType` per entry. Actions: enable/disable toggle, edit (→ `ProviderFormRoute(PROXY, proxyType, id)`), delete. Entry point from `SettingsScreen`.

**Delete proxy behavior:**
1. Cascade-clear all `ProxyAssignmentEntity` rows for that proxy → affected endpoints revert to direct
2. Show snackbar: "Proxy deleted. N servers now use direct connection."
3. Invalidate `EndpointHandle` cache for affected endpoints in `EndpointClientRegistry`
4. Running instances keep their old client until next `getOrCreate()` call

**Modify: `app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt`**

Replace endpoint add/edit routes with `ProviderFormRoute`. Add route for `ProxyManageRoute`. Add entry point from `SettingsScreen`.

**Add string resources** in `app/src/main/res/values/strings.xml`:
```xml
<string name="proxy_manage_title">Proxies</string>
<string name="proxy_add_title">Add Proxy</string>
<string name="proxy_section_title">Proxy</string>
<string name="proxy_none">None (direct connection)</string>
```

### Phase 10: Player Module — Reuse Proxy Client from `PlaybackSpec`

**Modify: `player/src/main/java/com/opentune/player/engine/PlaybackSpecExt.kt`**

Reuse `spec.httpClient` when available instead of always creating a fresh client:
```kotlin
val okHttp = spec.httpClient
    ?.newBuilder()
    ?.apply { if (spec.headers.isNotEmpty()) addInterceptor(headersInterceptor(spec.headers)) }
    ?.build()
    ?: OkHttpClient.Builder()
        .apply { if (spec.headers.isNotEmpty()) addInterceptor(headersInterceptor(spec.headers)) }
        .build()
```

**Modify: `player/src/main/java/com/opentune/player/controller/SubtitleController.kt`**

```kotlin
val httpFactory = if (spec.httpClient != null)
    OkHttpDataSource.Factory(spec.httpClient)
else
    DefaultHttpDataSource.Factory().setDefaultRequestProperties(spec.subtitleHeaders)
```

### Phase 11: Coil Cover Art — Per-Endpoint Proxied ImageLoader

**New file: `app/src/main/java/com/opentune/app/image/ProxyImageLoader.kt`**

```kotlin
object ProxyImageLoader {
    private val loaders = mutableMapOf<String, ImageLoader>()
    fun get(endpointId: String, httpClient: OkHttpClient?, app: Application): ImageLoader
    fun invalidate(endpointId: String)
    fun clear()
}
```

Builds a Coil `ImageLoader` backed by the proxy `OkHttpClient`. The app-wide singleton in `OpenTuneApplication` is used as fallback for endpoints without a proxy.

**Modify: `app/src/main/java/com/opentune/app/ui/catalog/MediaEntryComponent.kt`** and **`ThumbEntryComponent.kt`**

Pass `endpointId` + `httpClient` from the `EndpointHandle`. Use `ProxyImageLoader.get(endpointId, httpClient, app)` as the `imageLoader` for `AsyncImage`.

## Data Flow

```
UI: ProxyManageRoute
  └─ Edit/delete existing proxies
  └─ Add proxy → ProviderFormRoute(PROXY, "http") → inserts ProxyConfigEntity

UI: ProviderFormRoute(ENDPOINT, ...)
  └─ (proxy section only shown if provider.supportsProxy == true)
  └─ User selects proxy from dropdown
  └─ submitAdd() → EndpointEntity + ProxyAssignmentEntity

EndpointClientRegistry.getOrCreate(endpointId)
  └─ provider.createClient(values, capabilities) → EndpointClient
  └─ ProxyAssignmentEntity → ProxyConfigEntity → proxyProvider.createClient(fields) → OkHttpClient
  └─ returns EndpointHandle(client, httpClient)

PlayerRoute
  └─ handle.client.getPlaybackSpec(...).copy(httpClient = handle.httpClient)

Player
  └─ PlaybackSpecExt.toMediaSource() → reuses spec.httpClient
  └─ SubtitleController → reuses spec.httpClient for subtitle URLs

Coil ImageLoader
  └─ ProxyImageLoader.get(endpointId, handle.httpClient) → per-endpoint ImageLoader
```

## Traffic Coverage

| Traffic Type | Through Proxy? | How |
|---|---|---|
| Emby API calls (catalog, search, detail) | Yes (if assigned) | `EndpointHandle.httpClient` passed to `EmbyClientFactory` at construction |
| Emby playback URL fetching | Yes (if assigned) | Same proxied `EmbyApi` |
| ExoPlayer media streaming | Yes (if assigned) | `PlaybackSpec.httpClient` reused in `PlaybackSpecExt` |
| ExoPlayer subtitle sidecar loading | Yes (if assigned) | `PlaybackSpec.httpClient` reused in `SubtitleController` |
| Emby playback hooks (progress, stop) | Yes (if assigned) | Shared proxied `EmbyApi` in `EmbyPlaybackHooks` |
| Coil cover art / thumbnails | Yes (if assigned) | `ProxyImageLoader` with proxy `OkHttpClient` |
| SMB protocol traffic | No | SMBJ raw protocol — `supportsProxy = false` |
| JS provider traffic | No (for now) | QuickJS handles its own HTTP — `supportsProxy = false` |

## Critical Files to Modify / Create

| File | Change |
|---|---|
| `contracts/.../proxy/ProxyProvider.kt` | **NEW** — proxy provider interface |
| `contracts/.../proxy/ProxyProviderRegistry.kt` | **NEW** — ServiceLoader registry |
| `contracts/.../PlaybackContracts.kt` | Add `httpClient: OkHttpClient?` to `PlaybackSpec` |
| `contracts/.../ProviderContracts.kt` | Add `supportsProxy` |
| `proxy/http/` | **NEW module** — HTTP CONNECT proxy provider |
| `storage/.../Entities.kt` | Add `ProxyConfigEntity`, `ProxyAssignmentEntity` |
| `storage/.../Daos.kt` | Add `ProxyConfigDao`, `ProxyAssignmentDao` |
| `storage/.../OpenTuneDatabase.kt` | Add entities + DAOs, bump version 9 → 10 |
| `storage/.../StorageBindings.kt` | Add DAOs to `OpenTuneStorageBindings` |
| `providers/emby/.../EmbyClientFactory.kt` | Add pre-built client overload |
| `providers/emby/.../EmbyRepository.kt` | Accept `EmbyApi` as constructor param |
| `providers/emby/.../EmbyProviderInstance.kt` | Build and hold `EmbyApi` at construction |
| `app/.../EndpointHandle.kt` | **NEW** — wraps `EndpointClient` + `OkHttpClient?` |
| `app/.../EndpointClientRegistry.kt` | Resolve proxy client; store + return `EndpointHandle` |
| `app/.../OpenTuneApplication.kt` | Wire `ProxyProviderRegistry` + new DAOs |
| `app/.../EndpointConfigRepository.kt` | Save/load proxy assignments on add/edit/remove |
| `app/.../PlayerRoute.kt` | Inject `handle.httpClient` into `PlaybackSpec` |
| `player/.../PlaybackSpecExt.kt` | Reuse `spec.httpClient` |
| `player/.../SubtitleController.kt` | Reuse `spec.httpClient` for subtitle HTTP |
| `app/.../ProxyManageRoute.kt` | **NEW** — proxy management UI |
| `app/.../ProviderFormRoute.kt` | **NEW** — unified add/edit form for endpoints and proxies |
| `app/.../EndpointAddRoute.kt` | **DELETE** — replaced by `ProviderFormRoute` |
| `app/.../EndpointEditRoute.kt` | **DELETE** — replaced by `ProviderFormRoute` |
| `app/.../OpenTuneNavHost.kt` | Add proxy routes, replace endpoint add/edit routes |
| `app/.../ProxyImageLoader.kt` | **NEW** — per-endpoint Coil ImageLoader |
| `app/.../MediaEntryComponent.kt` | Use proxied ImageLoader |
| `app/.../ThumbEntryComponent.kt` | Use proxied ImageLoader |

## Verification

1. `./gradlew assembleDebug` — all modules compile
2. Settings → Proxies → Add an HTTP proxy with test credentials
3. Add an Emby endpoint — verify proxy dropdown appears
4. Add an SMB endpoint — verify proxy section does NOT appear
5. Assign proxy to the Emby endpoint
6. Verify catalog browsing works (API calls through proxy)
7. Play a video — verify media streaming uses the proxied client
8. Enable external subtitles — verify subtitle loading uses the proxy
9. Verify cover art loads through the proxy
10. Delete the proxy — verify affected endpoints revert to direct with a snackbar
