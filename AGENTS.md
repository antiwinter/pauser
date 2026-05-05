# OpenTune architecture conventions

When adding or changing TV screens for a **content source**, follow these rules so navigation and code stay easy to grep.

## Draft development (no migrations, no legacy shims)

OpenTune is still **draft / pre-release**. Do **not**:

- Add **backward-compatibility** layers for removed code paths (no dual APIs, no "deprecated but still works" bridges, no version switches to keep old behavior alive).
- Implement **data or navigation migrations** for end users (no Room destructive-migration workarounds solely to preserve old installs).

When something changes, **update call sites and schema directly** and delete the old approach.

---

## Providers

### Contracts (`:provider-api`)

[`:provider-api`](provider-api/src/main/java/com/opentune/provider/) contains:

- **`OpenTuneProvider`** — stateless factory. Key members:
  - `val providerType: String` — stable registry key
  - `val providesCover: Boolean` — `true` if catalog list items carry HTTP cover art directly (e.g. Emby); `false` if covers must be extracted from the media stream (e.g. SMB)
  - `fun getFieldsSpec(): List<ServerFieldSpec>`
  - `suspend fun validateFields(values: Map<String, String>): ValidationResult`
  - `fun createInstance(values: Map<String, String>): OpenTuneProviderInstance`
  - `fun bootstrap(context: Context) {}`
- **`OpenTuneProviderInstance`** — live protocol handle for one configured server. Key members:
  - `suspend fun loadBrowsePage(…): BrowsePageResult`
  - `suspend fun searchItems(…): List<MediaListItem>`
  - `suspend fun loadDetail(itemRef: String): MediaDetailModel`
  - `suspend fun resolvePlayback(…): PlaybackSpec`
  - `suspend fun <T> withStream(itemRef: String, block: suspend (ItemStream) -> T): T? = null` — opens a random-access byte stream for `itemRef`, calls `block`, then closes. Returns `null` by default (providers that do not support streaming leave this unoverridden). The `ItemStream` lifecycle is owned entirely by `withStream`; callers must not close it.
- **`ItemStream`** — pure-Kotlin random-access stream interface (`readAt`, `getSize`). No `Closeable`; closed by `withStream`.
- **`MediaArt`** — `Http(url)`, `DrawableRes(resId)`, `LocalFile(absolutePath)`, `None`.
- **`MediaListItem`** — `id`, `title`, `kind`, `cover: MediaArt`. The `cover` field is mutable at the list-item level: `CoverExtractor` replaces it in-place via `SnapshotStateList` copy when an extracted thumbnail is ready.
- **`MediaDetailModel`** — includes both `cover: MediaArt` (thumbnail / fallback) and `poster: MediaArt` (full-size art). `DetailScreen` renders `poster`; list screens render `cover`.
- **`PlaybackSpec`**, **`OpenTunePlaybackHooks`**, **`ServerFieldSpec`**, **`ValidationResult`**, **`SubmitResult`**.

### Registry

[`OpenTuneProviderRegistry`](app/src/main/java/com/opentune/app/providers/OpenTuneProviderRegistry.kt) on [`OpenTuneApplication`](app/src/main/java/com/opentune/app/OpenTuneApplication.kt) maps `providerType` string → `OpenTuneProvider` instance. Register new backends there only.

### Implementations

| Module | Provider | `providesCover` | `withStream` |
|---|---|---|---|
| `:providers:emby` | `EmbyProvider` / `EmbyProviderInstance` | `true` | not overridden (returns null) |
| `:providers:smb` | `SmbProvider` / `SmbProviderInstance` | `false` | overridden — opens smbj `DiskShare`, wraps in `SmbItemStream : ItemStream` |

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

`items` is a `SnapshotStateList<MediaListItem>` owned by the route. When a cover is resolved, `rememberCoverExtractor` writes it directly into the list (`items[idx] = items[idx].copy(cover = art)`), which drives recomposition automatically. **No parallel override map; no extra cover props on `MediaEntryComponent`.**

Priority chain per item:
1. `mediaStateStore.get(…)?.coverCachePath` — fast DB lookup
2. `thumbnailDiskCache.get(sourceId, itemId)` — disk stat; re-syncs DB on hit
3. `instance.withStream(itemId) { … }` → `ItemStreamMediaDataSource` → `MediaMetadataRetriever.embeddedPicture`
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
| `BrowseRoute` / `SearchRoute` | Create `mutableStateListOf<MediaListItem>()`, call `rememberCoverExtractor`, pass both to the screen |
| `BrowseScreen` / `SearchScreen` | Accept `SnapshotStateList<MediaListItem>`; populate with `.clear()` + `.addAll()`; call `onItemsLoaded` after each batch |
| `MediaEntryComponent` | Renders `item.cover` directly — no cover override param |
| `DetailRoute` / `DetailScreen` | Load `MediaDetailModel`, render `detail.poster` |
| `CoverExtractor` | `rememberCoverExtractor` hook + `updateItemCover` helper |
| `ItemStreamMediaDataSource` | Bridges `ItemStream` → `android.media.MediaDataSource` for `MediaMetadataRetriever` (API 23+, uses `runBlocking` off the main thread) |

**Player shell:** [`OpenTunePlayerScreen`](player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt) in `:player` takes `PlaybackSpec` only — no SMB/Emby branching.

---

## Navigation route strings

Unified catalog flows (`providerType` values come from `OpenTuneProvider.providerType`):

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
- Player: `"OpenTunePlayer"` (from `OPEN_TUNE_PLAYER_LOG`); add provider hints in log *messages* if needed.

---

## Playback hooks

Implement `OpenTunePlaybackHooks` from `:provider-api`. HTTP-library: `EmbyPlaybackHooks` in `:providers:emby`. File-share: `SmbPlaybackHooks` in `:providers:smb`.
