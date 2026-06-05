# OpenTune architecture conventions

When adding or changing TV screens for a **content source**, follow these rules so navigation and code stay easy to grep.

## Plans

Store implementation plans under **`<project-root>/.claude/plans/`** (not `~/.claude/plans/`). When writing a plan, always use the project-local path so it stays in the repo alongside the code it describes.

---

## Draft development (no migrations, no legacy shims)

OpenTune is still **draft / pre-release**. Do **not**:

- Add **backward-compatibility** layers for removed code paths (no dual APIs, no "deprecated but still works" bridges, no version switches to keep old behavior alive).
- Implement **data or navigation migrations** for end users (no Room destructive-migration workarounds solely to preserve old installs).

When something changes, **update call sites and schema directly** and delete the old approach.

---

## Module graph

```
:app ─┬─ :content:ui ── :content:contract
      ├─ :content:providers:smb ── :content:contract
      ├─ :content:providers:js ── :content:contract
      ├─ :proxy:ui ── :proxy:contract
      ├─ :proxy:providers:http ── :proxy:contract
      ├─ :player
      ├─ :storage
      ├─ :server ── :content:contract
      ├─ :core:form ── :core:form:contract
      ├─ :image-viewer
      └─ :gen-art
```

**No module imports `:storage`.** Providers (`:content:providers:*`) and proxies (`:proxy:providers:*`) are pure — they never touch Room or preferences.

---

## Embedded HTTP server (`OpenTuneServer`)

[`OpenTuneServer`](server/src/main/java/com/opentune/server/OpenTuneServer.kt) is started in `OpenTuneApplication.onCreate()` and runs for the app's lifetime. It is the **single mechanism** through which any provider byte resource (SMB video, SMB cover, SMB sidecar subtitle) becomes a plain `http://` URL.

- Binds to `0.0.0.0` (all interfaces) on fixed port **7920** (`SERVER_PORT`). Both the local player and LAN clients can reach it.
- Implements [`StreamRegistrar`](content/contract/src/main/java/com/opentune/content/contract/StreamRegistrar.kt) by delegating to [`StreamProxy`](server/src/main/java/com/opentune/server/StreamProxy.kt); callers only interact with `OpenTuneServer`.
- Token registry: `ConcurrentHashMap<token, TokenEntry>`. Each token is a random UUID hex string embedded in the URL path `/stream/<token>`. File size is cached after first query so repeated HEAD-like requests don't open extra connections.
- Route `GET /stream/{token}`: looks up token → calls `client.openStream(itemRef)` → streams bytes, honoring `Range` headers with `206 Partial Content`. One SMB session opened per HTTP request, closed when response finishes.
- **Auth by token entropy**: tokens are single-use opaque strings revoked explicitly by the provider.
- All SMB URLs produced for playback and cover extraction are `http://127.0.0.1:7920/stream/<token>` — loopback only. LAN features (future) will use the device's LAN IP.
- Debug routes (provider/catalog/navigate API) installed only when `AppContext` (debug mode) is non-null. Gen-art routes also require `AppContext`.

### `StreamRegistrar` / `StreamRegistrarHolder`

Defined in [`:content:contract`](content/contract/src/main/java/com/opentune/content/contract/StreamRegistrar.kt). Endpoints call:

```kotlin
val url = StreamRegistrarHolder.get().registerStream(this, itemRef)   // returns http://127.0.0.1:7920/stream/{token}
StreamRegistrarHolder.get().revokeToken(url)   // call when done
```

`StreamRegistrarHolder.set(openTuneServer)` is called in `OpenTuneApplication.onCreate()`.

### `ProviderStream`

Defined in [`:content:contract`](content/contract/src/main/java/com/opentune/content/contract/ProviderContracts.kt). Random-access stream with explicit `close()`. Used **only** by `OpenTuneServer`'s route handler; no player or UI code calls it directly.

```kotlin
interface ProviderStream {
    suspend fun getSize(): Long
    suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
    fun close()
}
```

`EndpointClient.openStream(itemRef: String): ProviderStream? = null` — overridden by SMB, default null for Emby/JS.

---

## Content providers

### Contracts (`:content:contract`)

[`:content:contract`](content/contract/src/main/java/com/opentune/content/contract/) contains:

- **`OpenTuneProvider`** — stateless factory. Key members:
  - `val protocol: String` — stable registry key
  - `val providesArt: Boolean` — `true` if catalog list items carry HTTP cover art directly (e.g. Emby); `false` if covers must be extracted from the media stream (e.g. SMB)
  - `fun getFieldsSpec(): List<FormFieldSpec>`
  - `fun createClient(values: Map<String, String>): EndpointClient`
- **`EndpointClient`** — abstract class, the live protocol handle for one configured endpoint. Key members:
  - `open var imageLoader: coil3.ImageLoader? = null`
  - `open var httpClient: okhttp3.OkHttpClient = OkHttpClient()`
  - `open suspend fun test(): EndpointValidationResult`
  - `abstract suspend fun listEntry(location, startIndex, limit, options): EntryList`
  - `abstract suspend fun search(scopeLocation, query): EntryList`
  - `abstract suspend fun getDetail(itemRef): EntryDetail`
  - `abstract suspend fun getPlaybackSpec(itemRef, startMs): PlaybackSpec`
  - `abstract suspend fun getEntries(itemRefs): EntryList`
  - `open suspend fun getTaggedEntries(tag, scopeLocation, startIndex, limit, sortBy, sortOrder): EntryList`
  - `open suspend fun tagEntry(itemRef, tag, value): Unit`
  - `open suspend fun openStream(itemRef): ProviderStream? = null`
  - `open suspend fun getQr(): QrResult.QrReady?` / `open suspend fun pollQr(token): QrResult`
- **`ProviderStream`** — random-access stream. See above.
- **`StreamRegistrar`** / **`StreamRegistrarHolder`** — cross-module service locator for token registration. See above.
- **`OpenTuneProviderRegistry`** / **`OpenTuneProviderRegistryHolder`** — protocol → `OpenTuneProvider` lookup.
- **`EndpointClientRegistryHolder`** / **`EndpointClientAccess`** — endpointId → `EndpointClient` lifecycle (getOrCreate, registerHandle, update, remove, buildHttpClient).
- **`CatalogContracts.kt`** — `EntryInfo`, `EntryList`, `EntryDetail`, `EntryType`, `EntryUserData`, `SearchQuery`, `QueryOptions`, `SortField`, `SortOrder`, `EntryTag`, `ExternalUrl`, `StreamInfo`, `CatalogRouteTokens`.
- **`PlaybackMimeTypes`** — container format → MIME type mapping.

### Registry

[`OpenTuneProviderRegistry`](content/contract/src/main/java/com/opentune/content/contract/OpenTuneProviderRegistry.kt) maps `protocol` string → `OpenTuneProvider` instance. Providers register via `OpenTuneProviderLoader` SPI (`META-INF/services/com.opentune.content.contract.OpenTuneProviderLoader`).

[`EndpointClientRegistry`](app/src/main/java/com/opentune/app/providers/EndpointClientRegistry.kt) in `:app` manages the `EndpointClient` lifecycle — builds clients with shared `DiskCache`, `OkHttpClient` (optionally routed through a proxy), and per-endpoint `ImageLoader`. Set via `EndpointClientRegistryHolder`.

### Implementations

| Module | Provider | `providesArt` | `openStream` |
|---|---|---|---|
| `:content:providers:smb` | `SmbProvider` / `SmbClient` | `false` | overridden — opens smbj `DiskShare`, wraps in `SmbProviderStream : ProviderStream` |
| `:content:providers:js` | `JsProvider` / `JsClient` | varies | not overridden (null) |

**Source-prefixed identifiers** (`Smb*`, `Js*`) are confined to their respective modules. Do **not** place them under `ui/catalog`, `ui/home`, or `app/.../providers/` (only the neutral registry and [`EndpointClientRegistry`](app/src/main/java/com/opentune/app/providers/EndpointClientRegistry.kt) reside in `:app`).

---

## Proxy providers

[`:proxy:contract`](proxy/contract/src/main/java/com/opentune/proxy/contract/) contains:

- **`ProxyProvider`** — factory for HTTP proxy clients:
  - `val proxyType: String` — stable registry key
  - `fun getFieldsSpec(): List<FormFieldSpec>`
  - `suspend fun validateFields(values): ProxyValidationResult`
  - `fun createClient(values): OkHttpClient` — returns a configured `OkHttpClient`
- **`ProxyProviderRegistry`** / **`ProxyProviderRegistryHolder`** — `proxyType` → `ProxyProvider` lookup.

| Module | Proxy |
|---|---|
| `:proxy:providers:http` | `HttpProxyProvider` — plain HTTP CONNECT proxy |

Providers **never** import `:storage`.

---

## Playback contracts (`:player`)

[`PlaybackContracts.kt`](player/src/main/java/com/opentune/player/PlaybackContracts.kt) defines:

- **`PlaybackSpec`** — `url: String` is always non-null (SMB uses a loopback URL from `OpenTuneServer`). Contains `headers`, `mimeType`, `title`, `durationMs`, `bitrate`, `hooks: OpenTunePlaybackHooks`, `subtitleTracks: List<SubtitleTrack>`, `httpClient: OkHttpClient`. No `customMediaSourceFactory`.
- **`OpenTunePlaybackHooks`** — `onPlaybackReady`, `onProgressTick`, `onStop`, `onDispose`, `progressIntervalMs`. SMB implementation revokes stream tokens on dispose.
- **`SubtitleTrack`** — `trackId`, `label`, `language`, `isDefault`, `isForced`, `externalRef`.

---

## Storage (`:storage`)

[`:storage`](storage/src/main/java/com/opentune/storage/) owns all persistence. Key types:

- **`EndpointEntity`** — `@PrimaryKey val endpointId: String` (`"${providerType}_${hash}"`), `protocol`, `displayName`, `fieldsJson`, `proxyId?`, timestamps.
- **`ProxyEntity`** — `@PrimaryKey val id: String`, `proxyType`, `displayName`, `fieldsJson`, timestamps.
- **`EntryStateEntity`** — composite PK `(endpointId, itemId)`. Field `protocol` is stored but is not part of the PK. Tracks `positionMs`, `playbackSpeed`, `isFavorite`, `title`, `type`, `selectedSubtitleTrackId`, `selectedAudioTrackId`, `updatedAtEpochMs`.
- **`EndpointDao`** / **`ProxyDao`** — Room DAOs for CRUD.
- **`EntryStateStore`** / **`RoomEntryStateStore`** — CRUD for `EntryStateEntity`.
- **`AppPrefsStore`** — app-level preferences (proxy settings, etc.).
- **`OpenTuneStorageBindings`** — exposes `endpointDao`, `entryStateStore`, `appConfigStore`, `proxyDao`. Created by `OpenTuneApplication` and passed to routes.

**No `OssCache` / blob cache** in current storage. Cover art is served directly from provider URLs (HTTP covers) or extracted on-demand.

**Database version:** check [`OpenTuneDatabase`](storage/src/main/java/com/opentune/storage/OpenTuneDatabase.kt) for current version. Uses `fallbackToDestructiveMigration`.

---

## Cover art

### List covers (`providesArt = false` providers, e.g. SMB)

Cover generation is handled by [`rememberAssetGenerator`](content/ui/src/main/java/com/opentune/content/ui/catalog/BrowseRoute.kt), a `@Composable` hook used in `BrowseRoute` and `SearchRoute`:

```kotlin
val assetGenerator = rememberAssetGenerator(app, protocol, endpointId, client, items)
```

`items` is a `SnapshotStateList<EntryInfo>` owned by the route. When a cover is resolved, `rememberAssetGenerator` writes it directly into the list (`items[idx] = items[idx].copy(cover = path)`), which drives recomposition automatically. **No parallel override map; no extra cover props on `MediaEntryComponent`.**

Priority chain per item:
1. DB lookup for cached cover
2. `ossCache.getPath(key)` — resolve key to file path; if LRU-evicted, falls through to re-extraction
3. `client.getPlaybackSpec(itemId, 0)` → `PlaybackSpec` → `MediaMetadataRetriever` → embedded picture → store via `ossCache.put(bytes)`
4. On failure: write `CACHE_FAILED` sentinel to DB, never retried

Extraction is bounded to **4 concurrent jobs** via `Semaphore(4)`. Items with `CACHE_FAILED` or an already-resolved cover are skipped immediately.

When `provider.providesArt = true` (Emby), `rememberAssetGenerator` returns a no-op and does no work.

### Detail poster

`DetailScreen` renders `detail.poster` (not `detail.cover`). `MediaArt.None` renders nothing. Posters are not cached on disk.

### Cover clean-up on endpoint removal / identity change

[`EndpointConfigRepository`](app/src/main/java/com/opentune/app/providers/EndpointConfigRepository.kt) `removeEndpoint` and the identity-change edit branch both execute, in order:
1. `mediaStateStore.deleteByEndpoint(endpointId)` — deletes all Room rows for the endpoint
2. `endpointDao.delete(endpointId)` — deletes the endpoint record
3. `instanceRegistry.remove(endpointId)` — evicts the live instance

No explicit file deletion is needed — `OssCache` LRU eviction handles cleanup automatically.

---

## Shared catalog UI

Routes and screens under **`content/ui/src/main/java/com/opentune/content/ui/catalog`**:

| File | Role |
|---|---|
| `BrowseRoute` / `SearchRoute` | Create `mutableStateListOf<EntryInfo>()`, call `rememberAssetGenerator`, pass both to the screen |
| `BrowseScreen` / `SearchScreen` | Accept `SnapshotStateList<EntryInfo>`; populate with `.clear()` + `.addAll()`; call `onItemsLoaded` after each batch |
| `MediaEntryComponent` | Renders `item.cover` directly — no cover override param |
| `DetailRoute` / `DetailScreen` | Load `EntryDetail`, render `detail.poster` |
| `AssetGenerator` | `rememberAssetGenerator` hook + `updateItemCover` helper; currently covers only |
| `ArtUrlInjector` | Protocol/endpoint → art URL injection for entries |
| `CatalogNav` | Navigation helpers; `LIBRARIES_ROOT_SEGMENT` = `CatalogRouteTokens.LIBRARIES_ROOT_SEGMENT` |

**Player shell:** [`OpenTunePlayerScreen`](player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt) in `:player` takes `PlaybackSpec` only — no SMB/Emby branching.

---

## Navigation route strings

Unified catalog flows (`protocol` values come from `OpenTuneProvider.protocol`):

- `browse/{protocol}/{endpointId}/{location}` — URL-encoded `location` (opaque to Nav)
- `detail/{protocol}/{endpointId}/{itemRef}`
- `player/{protocol}/{endpointId}/{itemRef}/{startMs}`
- `search/{protocol}/{endpointId}/{scopeLocation}`

Endpoint configuration (neutral):

- `endpoint_add/{protocol}` — `Routes.endpointAdd`
- `endpoint_edit/{protocol}/{endpointId}` — `Routes.endpointEdit`

Encode/decode in `Routes` and/or `CatalogNav` only — avoid scattering magic strings. Libraries root token: `CatalogNav.LIBRARIES_ROOT_SEGMENT`.

---

## Server config UI

[`EndpointAddRoute`](app/src/main/java/com/opentune/app/ui/config/EndpointAddRoute.kt) / [`EndpointEditRoute`](app/src/main/java/com/opentune/app/ui/config/EndpointEditRoute.kt) under `ui/config`. Driven by `provider.getFieldsSpec()`. Field labels resolve via `strings.xml` + [`ProviderFieldLabels`](app/src/main/java/com/opentune/app/ui/config/ProviderFieldLabels.kt).

---

## Form module (`:core:form`)

[`:core:form:contract`](core/form/contract/src/main/java/com/opentune/core/form/contract/) provides neutral form types:

- **`FormFieldSpec`** — field definition (text, password, QR, etc.)
- **`QrResult`** — QR code login states

[`:core:form`](core/form/src/main/java/com/opentune/core/form/) provides UI:

- **`ProviderFormRoute`** — generic form renderer driven by `List<FormFieldSpec>`
- **`FormFieldsRenderer`** — composable field rendering
- **`FormFieldLabels`** — string resource label resolver
- **`SubmitResult`** — form submission result
- **`QrCodeField`** — QR code scanning/display composable

---

## Log tags

- Cover/asset generation: `"OT_AssetGenerator"`.
- Embedded server: `"OpenTuneServer"`.
- SMB player hints: `"OpenTunePlayer"` (from `SMB_LOG` in `SmbClient`).

---

## Playback hooks

Implement `OpenTunePlaybackHooks` from `:player`. HTTP-library: `EmbyPlaybackHooks` (in legacy `:providers:emby`). File-share: `SmbPlaybackHooks` in `:content:providers:smb` (revokes stream tokens on dispose).
