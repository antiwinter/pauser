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
- Debug routes (provider/catalog/navigate API) and Gen-art routes installed only when `AppContext` (debug mode) is non-null.

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
  - `abstract suspend fun getPlaybackSpec(itemRef, startMs): PlaybackSpec`
  - `abstract suspend fun getEntries(itemRefs): EntryList`
  - `open suspend fun getTaggedEntries(tag, scopeLocation, startIndex, limit, sortBy, sortOrder): EntryList`
  - `open suspend fun tagEntry(itemRef, tag, value): Unit`
  - `open suspend fun openStream(itemRef): ProviderStream? = null`
  - `open suspend fun getQr(): QrResult.QrReady?` / `open suspend fun pollQr(token): QrResult`
  - **Removed:** `getDetail(itemRef): EntryDetail` — detail fields now live on `EntryInfo` (see `CatalogContracts.kt`: `logo`, `backdrop`, `bitrate`, `year`, `durationMs`, `width`, `height`, `officialRating`, `filename`). No `EntryDetail` type exists.
- **`ProviderStream`** — random-access stream. See above.
- **`StreamRegistrar`** / **`StreamRegistrarHolder`** — cross-module service locator for token registration. See above.
- **`OpenTuneProviderRegistry`** / **`OpenTuneProviderRegistryHolder`** — protocol → `OpenTuneProvider` lookup.
- **`EndpointClientRegistryHolder`** / **`EndpointClientAccess`** — endpointId → `EndpointClient` lifecycle (getOrCreate, registerHandle, update, remove, buildHttpClient).
- **`CatalogContracts.kt`** — `EntryInfo`, `EntryList`, `EntryDetail`, `EntryType`, `EntryUserData`, `SearchQuery`, `QueryOptions`, `SortField`, `SortOrder`, `EntryTag`, `ExternalUrl`, `StreamInfo`, `CatalogRouteTokens`.
- **`PlaybackMimeTypes`** — container format → MIME type mapping.

### Registry

[`OpenTuneProviderRegistry`](content/contract/src/main/java/com/opentune/content/contract/OpenTuneProviderRegistry.kt) maps `protocol` string → `OpenTuneProvider` instance. Providers register via `OpenTuneProviderLoader` SPI (`META-INF/services/com.opentune.content.contract.OpenTuneProviderLoader`).

[`EndpointClientRegistry`](app/src/main/java/com/opentune/app/providers/EndpointClientRegistry.kt) in `:app` manages the `EndpointClient` lifecycle — builds clients with shared `DiskCache`, `OkHttpClient` (optionally routed through a proxy), and per-endpoint `ImageLoader`. Set via `EndpointClientRegistryHolder`.

**Access interfaces** (`content/contract/src/main/java/com/opentune/content/contract/RegistryHolders.kt`):
- `OpenTuneProviderAccess` / `OpenTuneProviderRegistryHolder` — `getProvider(protocol)`, `getProviders()`, `set(registry)`
- `EndpointClientAccess` / `EndpointClientRegistryHolder` — `getOrCreate(endpointId, entity)`, `registerHandle(endpointId, client)`, `update(endpointId, entity)`, `remove(endpointId)`, `buildHttpClient(proxy?, headers?)`

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

- **`PlaybackSpec`** — `url: String` is always non-null (SMB uses a loopback URL from `OpenTuneServer`). Contains `url`, `headers`, `mimeType`, `hooks: OpenTunePlaybackHooks`, `subtitleTracks: List<SubtitleTrack>`, `httpClient: OkHttpClient`, `mediaCodecs: List<MediaCodecInfo>`. No `title`, `durationMs`, or `bitrate` (moved to `EntryInfo`). No `customMediaSourceFactory`.
- **`OpenTunePlaybackHooks`** — `onPlaybackReady`, `onProgressTick(isPaused: Boolean = false)`, `onStop`, `onDispose`, `fun progressIntervalMs(): Long`. SMB implementation revokes stream tokens on dispose.
- **`SubtitleTrack`** — `trackId`, `label`, `language`, `isDefault`, `isForced`, `externalRef`.

---

## Storage (`:storage`)

[`:storage`](storage/src/main/java/com/opentune/storage/) owns all persistence. Key types:

- **`EndpointEntity`** — `@PrimaryKey val endpointId: String` (`"${providerType}_${hash}"`), `protocol`, `displayName`, `fieldsJson`, `proxyId?`, timestamps.
- **`ProxyEntity`** — `@PrimaryKey val id: String`, `proxyType`, `displayName`, `fieldsJson`, `createdAtEpochMs`.
- **`EntryStateEntity`** — composite PK `(endpointId, itemId)`. Field `protocol` is stored but is not part of the PK. Tracks `positionMs`, `playbackSpeed`, `isFavorite`, `title`, `type`, `selectedSubtitleTrackId`, `selectedAudioTrackId`, `updatedAtEpochMs`.
- **`EndpointDao`** / **`ProxyDao`** — Room DAOs for CRUD.
- **`EntryStateStore`** — CRUD for `EntryStateEntity` (interface; Room-backed).
- **`AppPrefsStore`** — app-level preferences (proxy settings, subtitle prefs, drafts, title language, pre-buffer duration).
- **`OpenTuneStorageBindings`** — exposes `endpointDao`, `entryStateStore`, `appConfigStore`, `proxyDao`. Created by `OpenTuneApplication` and passed to routes.

**No `OssCache` / blob cache** in current storage. Cover art is served directly from provider URLs (HTTP covers) or extracted on-demand.

**Database version:** check [`OpenTuneDatabase`](storage/src/main/java/com/opentune/storage/OpenTuneDatabase.kt) for current version. Uses `fallbackToDestructiveMigration`.

---

## Cover art

Cover art is generated **server-side** on-demand by `GenartRoutes.kt`. Clients fetch covers via:

```
GET /genart/{type}/{version}/{endpointId}/{itemId}
```

The server uses `com.opentune.genart.GenArt.generateCover` to extract embedded artwork from the media stream. Generated covers are served as HTTP image responses and cached client-side by Coil. No client-side cover extraction (`MediaMetadataRetriever`, `OssCache`, `Semaphore`-bounded jobs) exists.

When `provider.providesArt = true` (Emby), covers come directly from the provider's HTTP API URLs embedded in `EntryInfo.cover`.

### Cover clean-up on endpoint removal / identity change

[`EndpointConfigRepository`](app/src/main/java/com/opentune/app/providers/EndpointConfigRepository.kt) `removeEndpoint` and the identity-change edit branch both execute, in order:
1. `entryStateStore.deleteByEndpoint(endpointId)` — deletes all Room rows for the endpoint
2. `endpointDao.delete(endpointId)` — deletes the endpoint record
3. `instanceRegistry.remove(endpointId)` — evicts the live instance

No explicit cover file deletion is needed — server-side generated covers are ephemeral.

---

## Shared catalog UI

Routes and screens under **`content/ui/src/main/java/com/opentune/content/ui/catalog/`** (subdirectories):

| File | Role |
|---|---|
| `catalog/browse/` BrowseRoute / BrowseScreen / BrowseViewModel | List entries; populate `EntryInfo` list; cover art URLs come from `EntryInfo.cover` (provider HTTP) or server-side gen-art |
| `catalog/search/` SearchRoute / SearchScreen | Search entries |
| `catalog/components/` MediaEntryComponent / ThumbEntryComponent | Render entries; `item.cover` from `EntryInfo.cover` |
| `catalog/detail/` DetailRoute / DetailViewModel / DetailHeader / DetailOverviewShell + type-specific screens (MovieDetailRoute, SeriesDetailRoute, DigipakDetailRoute) | Load `EntryInfo` directly (no `EntryDetail`), render detail fields |
| `catalog/player/` PlayerController | Orchestrates playback lifecycle |
| `CatalogNav` | Navigation helpers; `LIBRARIES_ROOT_SEGMENT` = `CatalogRouteTokens.LIBRARIES_ROOT_SEGMENT` |

**Player shell:** `:player` module defines `PlayerSurfaceController` interface ([`player/src/main/java/com/opentune/player/PlayerSurfaceController.kt`](player/src/main/java/com/opentune/player/PlayerSurfaceController.kt)) with platform implementations: `TvPlayerSurface` ([`player/src/main/java/com/opentune/player/ui/tv/TvPlayerSurface.kt`](player/src/main/java/com/opentune/player/ui/tv/TvPlayerSurface.kt)) for TV and `PadPlayerSurface` ([`player/src/main/java/com/opentune/player/ui/pad/PadPlayerSurface.kt`](player/src/main/java/com/opentune/player/ui/pad/PadPlayerSurface.kt)) for tablets. The `PlayerController` in `:content:ui` drives playback via these surfaces — no SMB/Emby branching.

---

## Navigation route strings

Unified catalog flows (`provider` values come from `OpenTuneProvider.protocol`):

- `browse/{provider}/{endpointId}/{id}` — `{id}` is the entry location/id
- `detail/{provider}/{endpointId}/{itemRef}/{id}` — `{id}` is URL-encoded serialized `EntryInfo` JSON
- `player/{provider}/{endpointId}/{itemRef}/{startMs}/{id}` — `{id}` is URL-encoded serialized `EntryInfo` JSON
- `search/{provider}/{endpointId}/{scopeLocation}`

Endpoint configuration (neutral):

- `endpoint_add/{protocol}` — `Routes.endpointAdd`
- `provider_edit/{protocol}?endpointId={endpointId}` — `Routes.providerEdit` (query string, not path param)

Encode/decode in `Routes` and/or `CatalogNav` only — avoid scattering magic strings. Libraries root token: `CatalogNav.LIBRARIES_ROOT_SEGMENT`.

---

## Server config UI

[`EndpointAddRoute`](app/src/main/java/com/opentune/app/ui/config/EndpointAddRoute.kt) / [`EndpointEditRoute`](app/src/main/java/com/opentune/app/ui/config/EndpointEditRoute.kt) under `ui/config`. Driven by `provider.getFieldsSpec()`. Field labels resolve via `strings.xml` + [`ProviderFieldLabels`](app/src/main/java/com/opentune/app/ui/config/ProviderFieldLabels.kt).

---

## Gen-art routes

[`GenartRoutes.kt`](server/src/main/java/com/opentune/server/GenartRoutes.kt) in `:server` exposes:

```
GET /genart/{type}/{version}/{endpointId}/{itemId}
```

- `type`: `"browse"` (grid thumbnails) or `"detail"` (poster/backdrop)
- `version`: cover generation version string (bump to bust caches)
- Uses `com.opentune.genart.GenArt.generateCover` to extract embedded artwork from the media stream
- Returns the image bytes directly; no server-side disk caching
- Installed alongside debug routes; requires non-null `AppContext`

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

- Embedded server: `"OpenTuneServer"`.
- Gen-art cover generation: `"GenartRoutes"`.
- SMB player hints: `"OpenTunePlayer"` (from `SMB_LOG` in `SmbClient`).
- Debug routes: `"OT_DebugRoutes"`.

---

## Playback hooks

Implement `OpenTunePlaybackHooks` from `:player`. HTTP-library: `EmbyPlaybackHooks` (in legacy `:providers:emby`). File-share: `SmbPlaybackHooks` in `:content:providers:smb` (revokes stream tokens on dispose).
