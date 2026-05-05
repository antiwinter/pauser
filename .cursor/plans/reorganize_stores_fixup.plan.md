# Fixup: AGENTS.md, cover/poster model, ThumbnailDiskCache, cover wiring

## 1. Rewrite AGENTS.md

Replace the stale provider/storage section with the new architecture. Key changes:

- **Contracts**: `OpenTuneProvider` (factory: `getFieldsSpec`, `validateFields`, `createInstance`, `bootstrap`) + `OpenTuneProviderInstance` (protocol methods only, no identity/store fields) in `:provider-api`
- **No persistence interfaces** in `:provider-api`; providers are store-blind
- **`sourceId`** = `"${providerType}_${hash}"`, computed by app, String PK of `ServerEntity`; `providerType` column kept in `MediaStateEntity` for cascade deletes but is **not** part of the PK — PK is `(sourceId, itemId)`
- **Registry**: `OpenTuneProviderRegistry` maps `providerType → OpenTuneProvider`; `ProviderInstanceRegistry` maps `sourceId → OpenTuneProviderInstance` (lazy DB lookup, Mutex-backed)
- **Storage**: `ServerEntity` (PK `sourceId: String`), `MediaStateEntity` (composite PK `sourceId, itemId`; has `providerType` column for cascade), `UserMediaStateStore`, `DataStoreAppConfigStore` (drafts), `OpenTuneStorageBindings.create()`
- **Cover caching**: `coverCachePath` in `MediaStateEntity` for locally-extracted browse covers; only written when `OpenTuneProvider.providesCover == false`
- **Playback**: local-first via `UserMediaStateStore` passed directly to `OpenTunePlayerScreen`; `:player` depends on `:storage`
- **Catalog UI**: shared screens in `app/.../ui/catalog`; routes use `providerType/{sourceId}/{segment}`; `MediaCatalogBinding` / `CatalogBindingPlugin` / binding files deleted
- **Server config UI**: `ServerAddRoute` / `ServerEditRoute` in `ui/config`, driven by `getFieldsSpec()` (single spec for add and edit)

Remove all references to: `CatalogBindingPlugin`, `MediaCatalogSource`, `ServerStore`, `FavoritesStore`, `ProgressStore`, `ProviderConfigBackend`, `StoreContracts`, `OpenTuneProviderIds`, `MediaCatalogBinding`, `catalogBindingDeps()`.

## 2. Update cover/poster model

### 2a. `MediaDetailModel` — add `poster`, keep `cover`

```kotlin
data class MediaDetailModel(
    val itemKey: String,
    val title: String,
    val synopsis: String?,
    /** Browse-list thumbnail. Not rendered on detail screen; kept for future use. */
    val cover: MediaArt,
    /** Hero/portrait artwork for the detail screen. None -> UI falls back to a fixed drawable. */
    val poster: MediaArt,
    val canPlay: Boolean,
    val resumePositionMs: Long,
    val favoriteSupported: Boolean,
    val isFavorite: Boolean,
)
```

- `DetailScreen` renders `poster`; if `poster == MediaArt.None` it shows a fixed generic `DrawableRes`
- `cover` is carried through but not rendered on the detail screen

### 2b. `OpenTuneProvider.providesCover: Boolean`

Add to the `OpenTuneProvider` interface:

```kotlin
/**
 * True if this provider supplies remote cover URLs for browse items (e.g. HTTP library with
 * image endpoints). When true the app renders covers directly and never writes to coverCachePath.
 * When false the app extracts cover bytes lazily via [OpenTuneProviderInstance.withStream] and
 * caches them locally in [com.opentune.storage.MediaStateEntity.coverCachePath].
 */
val providesCover: Boolean
```

- `EmbyProvider`: `override val providesCover = true`
- `SmbProvider`: `override val providesCover = false`

### 2c. `OpenTuneProviderInstance.withStream`

Add to the `OpenTuneProviderInstance` interface:

```kotlin
/**
 * Opens a random-access byte stream for [itemRef], invokes [block] with it, then closes it
 * automatically — even if [block] throws or the coroutine is cancelled. Used by the app to
 * extract embedded cover art via [android.media.MediaMetadataRetriever] when
 * [OpenTuneProvider.providesCover] is false.
 * Returns null if this provider does not support streaming (default); [block] is not called.
 */
suspend fun <T> withStream(itemRef: String, block: suspend (ItemStream) -> T): T? = null
```

Add `ItemStream` to `:provider-api`:

```kotlin
interface ItemStream {
    /** Read up to [size] bytes at [position] into [buffer] at [offset]. Returns bytes read, or -1 at EOF. */
    suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
    suspend fun getSize(): Long
}
```

`ItemStream` does not extend `Closeable` — closing is handled internally by `withStream`, never by the caller.

- `SmbProviderInstance` implements `withStream`: opens the smbj file handle, calls `block`, closes in `finally`
- `EmbyProviderInstance` leaves the default `null`

### 2d. Rename `coverThumbPath` -> `coverCachePath` in storage; add sentinel

In `MediaStateEntity`, `MediaStateSnapshot`, `Daos`, `RoomMediaStateStore`, `UserMediaStateStore`:
- rename field/column `coverThumbPath` -> `coverCachePath`
- bump database version

The field is tri-state, encoded in a single `String?` column:

| Value | Meaning |
|---|---|
| `null` | Not yet attempted — extraction should be tried |
| `MediaStateEntity.COVER_FAILED` (`"failed"`) | Extraction was attempted and produced no art — do not retry |
| any other string | Absolute path to the cached image file |

Define the sentinel as a companion constant:

```kotlin
companion object {
    const val COVER_FAILED = "failed"
}
```

Real paths always start with `/` on Android, so there is no collision risk with the sentinel value.

## 3. Fix `MediaStateEntity` PK

Remove `providerType` from the composite PK; PK becomes `(sourceId, itemId)`.
Keep `providerType` as a plain column (used for cascade-delete queries like "remove all state for emby servers").
Update `Daos` SQL and all callers that pass `providerType` as a PK component.

## 4. Implement `ThumbnailDiskCache`

Add `ThumbnailDiskCache` in `storage/src/main/java/com/opentune/storage/thumb/`:

- Deterministic file path from SHA-256 prefix of `"${sourceId}|${itemId}"`
- `put(sourceId: String, itemId: String, bytes: ByteArray): String` — writes PNG/JPEG bytes, returns `absolutePath`
- `get(sourceId: String, itemId: String): String?` — returns absolute path if cached, else null
- `delete(sourceId: String)` — removes all cached covers for a server (called on server removal)

Called only from `:app`. Not wired into the browse critical path yet in this phase — just the cache surface.

## 5. Wire cover extraction in BrowseRoute / SearchRoute

For every `MediaListItem` in a loaded page, cover display follows two branches based on `providesCover`:

**`providesCover == true` (e.g. Emby):**
- If `cover` is `MediaArt.Http` — render it directly, no local work.
- If `cover` is `MediaArt.None` — render the fixed generic `DrawableRes` immediately. Do **not** attempt extraction.

**`providesCover == false` (e.g. SMB):**
- The item always starts rendering with the fixed generic `DrawableRes` as the default.
- Check `mediaStateStore` for an existing `coverCachePath`:
  - If present: immediately swap to `MediaArt.LocalFile(path)` — no extraction needed.
  - If absent: launch a background extraction job (bounded by a `Semaphore(4)` to limit concurrent reads):
    - `instance.withStream(item.id) { stream -> ... }` — closing is automatic; wrap `stream` in `ItemStreamMediaDataSource : MediaDataSource` (in `:app`) inside the lambda
    - `MediaMetadataRetriever.setDataSource(dataSource)` -> `getEmbeddedPicture()`
    - On success: `ThumbnailDiskCache.put(...)` -> `mediaStateStore.upsertCoverCachePath(...)` -> update displayed item to `MediaArt.LocalFile(path)`
    - On `null` return (provider doesn't support streaming) or failed extraction (including `getEmbeddedPicture()` returning `null`): write `COVER_FAILED` sentinel to `mediaStateStore` and leave item showing the generic `DrawableRes` — no retry on future visits

`ItemStreamMediaDataSource` bridges `ItemStream` to `android.media.MediaDataSource` using `runBlocking` (acceptable; called off main thread by the retriever).

## 6. Wire poster in DetailRoute

After `loadDetail` returns, `DetailScreen` receives `detail.poster`. No merge from `mediaState` needed — poster is always provider-supplied or `None` -> fixed drawable. No caching of posters.