# OpenTune architecture conventions

When adding or changing TV screens for a **content source**, follow these rules so navigation and code stay easy to grep.

## Draft development (no migrations, no legacy shims)

OpenTune is still **draft / pre-release**. Do **not**:

- Add **backward-compatibility** layers for removed code paths (no dual APIs, no "deprecated but still works" bridges, no version switches to keep old behavior alive).
- Implement **data or navigation migrations** for end users (no Room destructive-migration workarounds solely to preserve old installs).

When something changes, **update call sites and schema directly** and delete the old approach.

---

## Embedded HTTP server (`OpenTuneServer`)

[`OpenTuneServer`](app/src/main/java/com/opentune/app/server/OpenTuneServer.kt) is started in `OpenTuneApplication.onCreate()` and runs for the app's lifetime. It is the **single mechanism** through which any provider byte resource (SMB video, SMB cover, SMB sidecar subtitle) becomes a plain `http://` URL.

- Binds to `0.0.0.0` (all interfaces) on an ephemeral port. Both the local player and LAN clients can reach it.
- Implements [`StreamRegistrar`](contracts/src/main/java/com/opentune/provider/StreamRegistrar.kt) and registers itself with `StreamRegistrarHolder`.
- Token registry: `ConcurrentHashMap<token, (ProviderInstance, itemRef)>`. Each token is a random UUID hex string embedded in the URL path `/stream/<token>`.
- Route `GET /stream/{token}`: looks up token → calls `instance.openStream(itemRef)` → streams bytes, honoring `Range` headers with `206 Partial Content`.
- **Auth by token entropy**: tokens are single-use opaque strings revoked explicitly by the provider.
- All SMB URLs produced for playback and cover extraction are `http://127.0.0.1:<port>/stream/<token>` — loopback only. LAN features (future) will use the device's LAN IP.

### `StreamRegistrar` / `StreamRegistrarHolder`

Defined in [`:contracts`](contracts/src/main/java/com/opentune/provider/StreamRegistrar.kt). Providers call:

```kotlin
val url = StreamRegistrarHolder.get().registerStream(this, itemRef)   // returns http://127.0.0.1:port/stream/{token}
StreamRegistrarHolder.get().revokeToken(url)   // call when done
```

`StreamRegistrarHolder.set(openTuneServer)` is called in `OpenTuneApplication.onCreate()`.

### `ProviderStream`

Defined in [`:contracts`](contracts/src/main/java/com/opentune/provider/ProviderContracts.kt). Random-access stream with explicit `close()`. Used **only** by `OpenTuneServer`'s route handler; no player or UI code calls it directly.

```kotlin
interface ProviderStream {
    suspend fun getSize(): Long
    suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
    fun close()
}
```

`OpenTuneProviderInstance.openStream(itemRef): ProviderStream? = null` — overridden by SMB, default null for Emby/JS.

---

## Providers

### Contracts (`:contracts`)

[`:contracts`](contracts/src/main/java/com/opentune/provider/) contains:

- **`OpenTuneProvider`** — stateless factory. Key members:
  - `val protocol: String` — stable registry key
  - `val providesCover: Boolean` — `true` if catalog list items carry HTTP cover art directly (e.g. Emby); `false` if covers must be extracted from the media stream (e.g. SMB)
  - `fun getFieldsSpec(): List<ServerFieldSpec>`
  - `suspend fun validateFields(values: Map<String, String>): ValidationResult`
  - `fun createInstance(values: Map<String, String>, capabilities: PlatformCapabilities): OpenTuneProviderInstance`
- **`OpenTuneProviderInstance`** — live protocol handle for one configured server. Key members:
  - `suspend fun listEntry(…): EntryList`
  - `suspend fun search(…): List<EntryInfo>`
  - `suspend fun getDetail(itemRef: String): EntryDetail`
  - `suspend fun getPlaybackSpec(itemRef: String, startMs: Long): PlaybackSpec`
  - `suspend fun openStream(itemRef: String): ProviderStream? = null` — opens a random-access stream; called by `OpenTuneServer` per HTTP request. Caller closes the stream. Returns `null` by default.
- **`ProviderStream`** — random-access stream with explicit `close()`. See above.
- **`StreamRegistrar`** / **`StreamRegistrarHolder`** — cross-module service locator for token registration. See above.
- **`PlaybackSpec`** — `url: String` is always non-null (SMB uses a loopback URL from `OpenTuneServer`). No `customMediaSourceFactory`.
- **`OpenTunePlaybackHooks`**, **`ServerFieldSpec`**, **`ValidationResult`**, **`SubmitResult`**.

### Registry

[`OpenTuneProviderRegistry`](app/src/main/java/com/opentune/app/providers/OpenTuneProviderRegistry.kt) on [`OpenTuneApplication`](app/src/main/java/com/opentune/app/OpenTuneApplication.kt) maps `protocol` string → `OpenTuneProvider` instance. Register new backends there only.

### Implementations

| Module | Provider | `providesCover` | `openStream` |
|---|---|---|---|
| `:providers:emby` | `EmbyProvider` / `EmbyProviderInstance` | `true` | not overridden (null) |
| `:providers:smb` | `SmbProvider` / `SmbProviderInstance` | `false` | overridden — opens smbj `DiskShare`, wraps in `SmbProviderStream : ProviderStream` |

**Source-prefixed identifiers** (`Emby*`, `Smb*`) are confined to their respective modules. Do **not** place them under `ui/catalog`, `ui/home`, or `app/.../providers/` (only the neutral registry and [`ServerConfigRepository`](app/src/main/java/com/opentune/app/providers/ServerConfigRepository.kt) reside in `:app`).

Providers **never** import `:storage`.

---

## Storage (`:storage`)

[`:storage`](storage/src/main/java/com/opentune/storage/) owns all persistence. Key types:

- **`ServerEntity`** — `@PrimaryKey val sourceId: String` (`"${providerType}_${hash}"`), `providerType`, `displayName`, `fieldsJson`, timestamps.
- **`MediaStateEntity`** — composite PK `(sourceId, itemId)`. Field `providerType` is stored but is not part of the PK. Notable field:
  - `coverCachePath: String?` — tri-state: `null` = not yet attempted; `MediaStateEntity.COVER_FAILED = "failed"` = extraction failed / never retry; any other value = absolute path to cached thumbnail on disk.
- **`UserMediaStateStore`** / **`RoomMediaStateStore`** — CRUD for `MediaStateEntity`. Method `upsertCoverCache(providerType, sourceId, itemId, path)` persists cover results.
- **`ThumbnailDiskCache`** — stores extracted cover bytes under `cacheDir/<sourceId>/<sha256(itemId).take(16)>`. `deleteBySource(sourceId)` deletes the entire `<sourceId>/` subdirectory. Called only from `:app`, never from providers.
- **`OpenTuneStorageBindings`** — exposes `serverDao`, `mediaStateStore`, `appConfigStore`, `thumbnailDiskCache`. Created by `OpenTuneApplication` and passed to routes via `app.storageBindings`.

**Database version: 5.** Uses `fallbackToDestructiveMigration`.

---

## Cover art

### List covers (`providesCover = false` providers, e.g. SMB)

Cover extraction is handled by [`rememberCoverExtractor`](app/src/main/java/com/opentune/app/ui/catalog/CoverExtractor.kt), a `@Composable` hook used in `BrowseRoute` and `SearchRoute`:

```kotlin
val coverExtractor = rememberCoverExtractor(app, providerType, sourceId, instance, items)
```

`items` is a `SnapshotStateList<EntryInfo>` owned by the route. When a cover is resolved, `rememberCoverExtractor` writes it directly into the list (`items[idx] = items[idx].copy(cover = path)`), which drives recomposition automatically. **No parallel override map; no extra cover props on `MediaEntryComponent`.**

Priority chain per item:
1. `mediaStateStore.get(…)?.coverCachePath` — fast DB lookup
2. `thumbnailDiskCache.get(sourceId, itemId)` — disk stat; re-syncs DB on hit
3. `instance.getPlaybackSpec(itemId, 0)` → `PlaybackSpec` → `MediaMetadataRetriever.setDataSource(spec.url, spec.headers)` → `.embeddedPicture` → `spec.hooks.onDispose()` in `finally`. The same contract the player uses: SMB resolves a loopback URL and `onDispose()` revokes its stream tokens; any future HTTP provider with embedded art works identically at no extra code cost.
4. On failure: write `COVER_FAILED` sentinel to DB, never retried

Extraction is bounded to **4 concurrent jobs** via `Semaphore(4)`. Items with `COVER_FAILED` or an already-resolved cover are skipped immediately.

When `provider.providesCover = true` (Emby), `rememberCoverExtractor` returns a no-op and does no work.

### Detail poster

`DetailScreen` renders `detail.poster` (not `detail.cover`). `MediaArt.None` renders nothing. Posters are not cached on disk.

### Cover clean-up on provider removal / identity change

[`ServerConfigRepository`](app/src/main/java/com/opentune/app/providers/ServerConfigRepository.kt) `removeServer` and the identity-change edit branch both execute, in order:
1. `mediaStateStore.deleteBySource(sourceId)` — deletes all Room rows for the source
2. `thumbnailDiskCache.deleteBySource(sourceId)` — deletes the `<sourceId>/` cache directory
3. `serverDao.deleteBySourceId(sourceId)` — deletes the server record
4. `instanceRegistry.remove(sourceId)` — evicts the live instance

---

## Shared catalog UI

Routes and screens under **`app/.../ui/catalog`**:

| File | Role |
|---|---|
| `BrowseRoute` / `SearchRoute` | Create `mutableStateListOf<EntryInfo>()`, call `rememberCoverExtractor`, pass both to the screen |
| `BrowseScreen` / `SearchScreen` | Accept `SnapshotStateList<EntryInfo>`; populate with `.clear()` + `.addAll()`; call `onItemsLoaded` after each batch |
| `MediaEntryComponent` | Renders `item.cover` directly — no cover override param |
| `DetailRoute` / `DetailScreen` | Load `EntryDetail`, render `detail.poster` |
| `CoverExtractor` | `rememberCoverExtractor` hook + `updateItemCover` helper |

**Player shell:** [`OpenTunePlayerScreen`](player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt) in `:player` takes `PlaybackSpec` only — no SMB/Emby branching.

---

## Navigation route strings

Unified catalog flows (`providerType` values come from `OpenTuneProvider.protocol`):

- `browse/{providerType}/{sourceId}/{location}` — URL-encoded `location` (opaque to Nav)
- `detail/{providerType}/{sourceId}/{itemRef}`
- `player/{providerType}/{sourceId}/{itemRef}/{startMs}`
- `search/{providerType}/{sourceId}/{scopeLocation}`

Server configuration (neutral):

- `provider_add/{providerType}` — `Routes.providerAdd`
- `provider_edit/{providerType}/{sourceId}` — `Routes.providerEdit`

Encode/decode in `Routes` and/or `CatalogNav` only — avoid scattering magic strings. Libraries root token: `CatalogNav.LIBRARIES_ROOT_SEGMENT` = `CatalogRouteTokens.LIBRARIES_ROOT_SEGMENT`.

---

## Server config UI

[`ServerAddRoute`](app/src/main/java/com/opentune/app/ui/config/ServerAddRoute.kt) / [`ServerEditRoute`](app/src/main/java/com/opentune/app/ui/config/ServerEditRoute.kt) under `ui/config`. Driven by `provider.getFieldsSpec()`. Field labels resolve via `strings.xml` + [`ProviderFieldLabels`](app/src/main/java/com/opentune/app/ui/config/ProviderFieldLabels.kt).

---

## Log tags

- Cover extraction: `"OT_CoverExtractor"`.
- Embedded server: `"OpenTuneServer"`.
- Player: `"OpenTunePlayer"` (from `OPEN_TUNE_PLAYER_LOG`); add provider hints in log *messages* if needed.

---

## Playback hooks

Implement `OpenTunePlaybackHooks` from `:contracts`. HTTP-library: `EmbyPlaybackHooks` in `:providers:emby`. File-share: `SmbPlaybackHooks` in `:providers:smb` (revokes stream tokens on dispose).
