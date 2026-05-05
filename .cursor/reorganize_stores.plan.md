# Unified Config + Media State Persistence

## Agreed Decisions

| Topic | Decision |
|---|---|
| `StoreContracts.kt` | Deleted entirely, no replacement ports |
| `ProviderConfigBackend` | Deleted; absorbed into `OpenTuneProvider` factory |
| Provider API shape | `getFieldsSpec()` + `validateFields(values): ValidationResult` + `createInstance(values: Map<String,String>): OpenTuneProviderInstance` |
| `addFields()`/`editFields()` | Replaced by single `getFieldsSpec()` |
| `validateAdd`/`validateEdit` | Replaced by single `validateFields(values)` |
| `display_name` | App-only (`ServerEntity` column); NOT in `fieldsJson`; NOT returned by provider |
| `ValidationResult` | `data class ValidationResult(val hash: String, val displayName: String)` |
| `sourceId` PK | `String` = `"${providerId}_${hash}"`, computed by app from `ValidationResult.hash` |
| `ServerEntity` PK | `@PrimaryKey val sourceId: String` (replaces autoGenerate Long) |
| Serdes | App does all `Map<String,String>` <-> JSON; provider never touches JSON |
| Server clash | `OnConflict.ABORT` -> surface "server already exists" to UI |
| Edit -> hash unchanged | Update `displayName` in place; keep `media_state` rows |
| Edit -> hash changed | Delete old `ServerEntity` + cascade-delete `media_state WHERE sourceId = old`; insert new; evict + recreate instance |
| Server removal | Delete `ServerEntity` + cascade-delete all `media_state` for that `sourceId` |
| `CatalogBindingDeps`/`PlaybackResolveDeps` | Both deleted |
| Instance lifecycle | Created at home screen, held in app-level registry keyed by `sourceId: String`; evicted on server edit/remove |
| `UserMediaStateStore` | Lives in `:storage`, NOT in `:provider-api` |
| `:player` dependency | `:player` depends on `:storage` for `UserMediaStateStore` |
| `OpenTunePlayerScreen` signature | `(spec: PlaybackSpec, mediaState: UserMediaStateStore, mediaStateKey: MediaStateKey, onExit: () -> Unit)` — store/key are direct params, NOT in `PlaybackSpec` |
| `PlaybackSpec` | No persistence fields; pure playback contract |
| `MediaDetailModel` | Keeps remote `isFavorite`/`resumePositionMs`; app merges with local `media_state` in `DetailRoute` |
| `media_state` composite PK | `(providerId: String, sourceId: String, itemId: String)` |
| `OpenTunePlaybackResumeStore` | Deleted |
| `AddServerDraftStore` | Deleted -> DataStore in `:storage` |

---

## Plan

Collapse three persistence mechanisms (SharedPreferences resume store, Room favorites + progress tables, JSON draft file) into two clean surfaces — a single `media_state` Room table and a DataStore-backed draft store — all owned by `:app`/`:storage`. Providers become zero-persistence factories: they receive a `Map<String,String>` at construction (deserialized by app from `fieldsJson`) and return protocol results only. `:player` is the sole local writer for playback state and receives the store + key as direct composable parameters.

---

### Phase 1 — Storage layer: replace entities and DAOs

1. In `storage/src/main/java/com/opentune/storage/ServerEntities.kt`:
   - Replace autoGenerate Long PK with content-addressed String PK:
     ```kotlin
     @Entity(tableName = "servers")
     data class ServerEntity(
         @PrimaryKey val sourceId: String,   // "${providerId}_${hash}"
         val providerId: String,
         val displayName: String,            // app-only, not in fieldsJson
         val fieldsJson: String,             // opaque JSON blob, deserialized by app
     )
     ```
   - Add `MediaStateEntity`:
     ```kotlin
     @Entity(
         tableName = "media_state",
         primaryKeys = ["providerId", "sourceId", "itemId"],
         foreignKeys = [ForeignKey(
             entity = ServerEntity::class,
             parentColumns = ["sourceId"],
             childColumns = ["sourceId"],
             onDelete = ForeignKey.CASCADE,
         )],
     )
     data class MediaStateEntity(
         val providerId: String,
         val sourceId: String,
         val itemId: String,
         val positionMs: Long = 0L,
         val playbackSpeed: Float = 1f,
         val isFavorite: Boolean = false,
         val title: String? = null,
         val type: String? = null,
         val coverThumbPath: String? = null,
         val updatedAtEpochMs: Long,
     )
     ```
   - Remove `FavoriteEntity` and `PlaybackProgressEntity`.

2. In `storage/src/main/java/com/opentune/storage/Daos.kt`, add `MediaStateDao`:
   - Partial `@Query UPDATE` methods: `upsertPosition`, `upsertSpeed`, `upsertFavorite`, `upsertCoverThumb`
   - Full `@Insert(onConflict = REPLACE) fun upsert(entity: MediaStateEntity)`
   - `suspend fun get(providerId, sourceId, itemId): MediaStateEntity?`
   - `fun observeForSource(providerId, sourceId): Flow<List<MediaStateEntity>>`
   - `fun observeAllFavorites(): Flow<List<MediaStateEntity>>` — WHERE `isFavorite = 1`
   - `suspend fun deleteForSource(sourceId: String)` — used on server removal / edit hash change
   - `suspend fun delete(providerId, sourceId, itemId)`

   Remove `FavoriteDao` and `PlaybackProgressDao`.

3. In `storage/src/main/java/com/opentune/storage/OpenTuneDatabase.kt`:
   - Bump version to **3**
   - Replace `FavoriteEntity`/`PlaybackProgressEntity` with `MediaStateEntity` and updated `ServerEntity` in entities list
   - Expose `mediaStateDao()`, remove `favoriteDao()` and `playbackProgressDao()`
   - Keep `fallbackToDestructiveMigration()`

---

### Phase 2 — Rework `:provider-api` contracts

4. Delete `provider-api/src/main/java/com/opentune/provider/StoreContracts.kt` entirely. No replacement.

5. In `provider-api/src/main/java/com/opentune/provider/ConfigContracts.kt`:
   - Delete `ProviderConfigBackend` interface and `SubmitResult` sealed class
   - Retain `ServerFieldSpec`, `ServerFieldKind`, `OpenTuneProviderIds` (these stay in this file)

6. In `provider-api/src/main/java/com/opentune/provider/ProviderContracts.kt`, reshape `OpenTuneProvider` into a **factory**:
   ```kotlin
   data class ValidationResult(val hash: String, val displayName: String)

   interface OpenTuneProvider {
       val providerId: String                    // matches OpenTuneProviderIds constant
       fun getFieldsSpec(): List<ServerFieldSpec>
       // Network auth only; no store. Returns ValidationResult on success or throws/returns error.
       suspend fun validateFields(values: Map<String, String>): ValidationResult
       // values is already-validated Map (not JSON); provider parses its own fields.
       fun createInstance(values: Map<String, String>): OpenTuneProviderInstance
   }
   ```

7. Add `OpenTuneProviderInstance` to `:provider-api`:
   ```kotlin
   interface OpenTuneProviderInstance {
       val sourceId: String
       suspend fun loadBrowsePage(location: String, page: Int): BrowsePageResult
       suspend fun searchItems(scopeLocation: String, query: String, page: Int): BrowsePageResult
       suspend fun loadDetail(itemRef: String): MediaDetailModel
       suspend fun resolvePlayback(itemRef: String, startMs: Long, context: Context): PlaybackSpec
   }
   ```
   No store/state parameters anywhere.

8. In `provider-api/src/main/java/com/opentune/provider/CatalogContracts.kt`:
   - Add `MediaCover.LocalFile(absolutePath: String)` variant
   - Keep `isFavorite` and `resumePositionMs` on `MediaDetailModel` (remote/provider values; app merges with local state)
   - Delete `CatalogBindingDeps`, `CatalogBindingPlugin`, `MediaCatalogSource` (replaced by `OpenTuneProviderInstance`)

9. In `provider-api/src/main/java/com/opentune/provider/PlaybackContracts.kt`:
   - Delete `PlaybackResolveDeps`, `PlaybackResumeAccessor`
   - Delete `progressPersistenceKey` function
   - `PlaybackSpec` has **no** persistence fields — pure playback contract

---

### Phase 3 — `UserMediaStateStore` and `MediaStateKey` in `:storage`

10. Add to `storage/src/main/java/com/opentune/storage/`:
    ```kotlin
    data class MediaStateKey(val providerId: String, val sourceId: String, val itemId: String)

    data class MediaStateSnapshot(
        val itemId: String,
        val positionMs: Long,
        val playbackSpeed: Float,
        val isFavorite: Boolean,
        val title: String?,
        val type: String?,
        val coverThumbPath: String?,
    )

    interface UserMediaStateStore {
        suspend fun get(key: MediaStateKey): MediaStateSnapshot?
        suspend fun upsertPosition(key: MediaStateKey, positionMs: Long)
        suspend fun upsertSpeed(key: MediaStateKey, speed: Float)
        suspend fun upsertFavorite(key: MediaStateKey, isFavorite: Boolean, title: String?, type: String?)
        suspend fun upsertCoverThumb(key: MediaStateKey, path: String?)
        fun observeForSource(providerId: String, sourceId: String): Flow<List<MediaStateSnapshot>>
        fun observeAllFavorites(): Flow<List<MediaStateSnapshot>>
    }
    ```

---

### Phase 4 — `:storage` wiring

11. In `storage/src/main/java/com/opentune/storage/StorageBindings.kt`, replace `OpenTuneStorageBindings(serverStore, favoritesStore, progressStore)` with `OpenTuneStorageBindings(serverDao, mediaStateStore, appConfigStore)`:
    - `RoomServerDao`: insert/update/delete/observe — app-only, not exposed via any `:provider-api` interface
    - `RoomMediaStateStore : UserMediaStateStore` backed by `MediaStateDao`
    - `DataStoreAppConfigStore`: DataStore-backed draft storage (see Phase 5)
    - Factory: `OpenTuneStorageBindings.create(database: OpenTuneDatabase, context: Context)`

---

### Phase 5 — DataStore for draft storage

12. In `gradle/libs.versions.toml`, add `datastore = "1.1.1"` and library alias `androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }`.

13. In `storage/build.gradle.kts`, add `implementation(libs.androidx.datastore.preferences)`.

14. Implement `DataStoreAppConfigStore` in `:storage`: draft maps stored as JSON strings keyed by `draft_{providerId}`. API: `loadDraft(providerId)`, `saveDraft(providerId, values)`, `clearDraft(providerId)`. Exposed via `OpenTuneStorageBindings.appConfigStore`.

---

### Phase 6 — Thumbnail disk cache

15. Add `ThumbnailDiskCache` in `storage/src/main/java/com/opentune/storage/thumb/`: deterministic path from SHA-256 prefix of `"${providerId}|${sourceId}|${itemId}"`, `put(bytes)`, `exists()`, `delete()`, `absolutePath()`. Called only from `:app`.

16. In `app/src/main/java/com/opentune/app/ui/catalog/MediaEntryComponent.kt` and any other cover renderer, add branch for `MediaCover.LocalFile(path)` -> `AsyncImage(model = File(path))` via Coil.

---

### Phase 7 — Provider modules (emby, smb)

17. Reshape `EmbyProvider` to implement `OpenTuneProvider`:
    - `getFieldsSpec()` — replaces `addFields()` + `editFields()`
    - `validateFields(values)` — network auth, returns `ValidationResult(hash, displayName)`; no store writes
    - `createInstance(values: Map<String,String>)` -> `EmbyProviderInstance(/* parsed config */)`

18. `EmbyProviderInstance` implements `OpenTuneProviderInstance`:
    - `loadBrowsePage`, `searchItems`, `loadDetail` — current Emby catalog logic, only parsed config; no store references
    - `resolvePlayback` — builds `PlaybackSpec` with `EmbyPlaybackHooks`; no store references

19. `EmbyPlaybackHooks`: remove `progressStore` constructor param and `progressStore.upsert(...)` in `onStop`. Keep only Emby HTTP session reporting (`reportPlaying`, `reportProgress`, `reportStopped`).

20. Delete `EmbyConfigBackend`. Delete `CatalogBindingPlugin` implementations (`EmbyBrowseBinding`, `EmbyDetailBinding`, etc.) — replaced by `EmbyProviderInstance` methods.

21. Same reshape for `SmbProvider` -> `SmbProviderInstance`. `SmbPlaybackHooks`: no store references.

---

### Phase 8 — App wiring

22. In `app/src/main/java/com/opentune/app/OpenTuneApplication.kt`:
    - Remove `addServerDraftStore: AddServerDraftStore` field
    - Update `storageBindings` to `OpenTuneStorageBindings.create(database, applicationContext)`
    - `providerRegistry` stays as-is; instance registry (below) is separate
    - Add `instanceRegistry: Map<String, OpenTuneProviderInstance>` (mutable, writable on home screen)

23. In `app/src/main/java/com/opentune/app/providers/ServerConfigRepository.kt`:
    - Draft methods -> `app.storageBindings.appConfigStore`
    - `submitAdd(providerId, values)`:
      1. `provider.validateFields(values)` -> `ValidationResult(hash, displayName)`
      2. Compute `sourceId = "${providerId}_${hash}"`
      3. `storageBindings.serverDao.insert(ServerEntity(sourceId, providerId, displayName, fieldsJson))` with `OnConflict.ABORT` -> show "server already exists" on failure
    - `submitEdit(providerId, sourceId, values)`:
      1. `provider.validateFields(values)` -> `ValidationResult(newHash, newDisplayName)`
      2. If `newHash == oldHash`: `serverDao.updateDisplayName(sourceId, newDisplayName)`
      3. If `newHash != oldHash`:
         - Compute `newSourceId = "${providerId}_${newHash}"`
         - `serverDao.insert(ServerEntity(newSourceId, ...))` with `OnConflict.ABORT`
         - `serverDao.delete(oldSourceId)` — FK CASCADE deletes `media_state` rows automatically
         - Evict old instance from registry; create new instance
    - `removeServer(sourceId)`: `serverDao.delete(sourceId)` — FK CASCADE handles `media_state`
    - Delete `app/src/main/java/com/opentune/app/drafts/AddServerDraftStore.kt`

24. **Instance lifecycle** in `HomeRoute` / home ViewModel:
    - On load: for each `ServerEntity` in `serverDao.observeAll()`, if `instanceRegistry[sourceId] == null`, deserialize `fieldsJson` -> `Map<String,String>`, call `provider.createInstance(values)`, store in registry
    - On server removal: evict from registry
    - `BrowseRoute`, `DetailRoute`, `SearchRoute`, `PlayerRoute` look up `instanceRegistry[sourceId]` — never call `createInstance` themselves

25. In `BrowseRoute`, `SearchRoute`, `DetailRoute`:
    - Get `OpenTuneProviderInstance` from `instanceRegistry[sourceId]`
    - Call catalog methods directly — no `CatalogBindingPlugin`, no `CatalogBindingDeps`
    - After `loadDetail`, read `storageBindings.mediaStateStore.get(key)` and merge `isFavorite`, `resumePositionMs`, `coverThumbPath` into local enriched state
    - Favorite toggle: `mediaStateStore.upsertFavorite(key, ...)` + optimistic local update

26. In `app/src/main/java/com/opentune/app/ui/catalog/PlayerRoute.kt`:
    - Get `OpenTuneProviderInstance` from `instanceRegistry[sourceId]`
    - Read resume: `storageBindings.mediaStateStore.get(key)?.positionMs ?: 0L`
    - Call `instance.resolvePlayback(itemRef, startMs, context)` -> `PlaybackSpec`
    - Pass `PlaybackSpec`, `storageBindings.mediaStateStore`, and `MediaStateKey(providerId, sourceId, itemRef)` as separate args to `OpenTunePlayerScreen`
    - Drop all `OpenTunePlaybackResumeStore` usage

---

### Phase 9 — `:player` uses `UserMediaStateStore` as direct param

27. In `player/build.gradle.kts`, add `implementation(project(":storage"))`.

28. In `player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt`, update signature:
    ```kotlin
    @Composable
    fun OpenTunePlayerScreen(
        spec: PlaybackSpec,
        mediaState: UserMediaStateStore,
        mediaStateKey: MediaStateKey,
        onExit: () -> Unit,
    )
    ```
    - Speed read on spec change: `mediaState.get(mediaStateKey)?.playbackSpeed ?: 1f`
    - Speed write (DisposableEffect): `mediaState.upsertSpeed(mediaStateKey, speed)`
    - `shutdown()`: call `mediaState.upsertPosition(mediaStateKey, positionMs)` **before** `spec.hooks.onStop(positionMs)` (local-first, crash-safe)

29. Delete `player/src/main/java/com/opentune/player/OpenTunePlaybackResumeStore.kt`.

---

### Phase 10 — Home screen

30. In `app/src/main/java/com/opentune/app/ui/home/HomeRoute.kt`:
    - Observe `storageBindings.serverDao.observeAll()` to list added servers
    - Display `ServerEntity.displayName` + `ServerEntity.providerId` (type label/icon via `OpenTuneProviderIds`)
    - "Add server": `providerRegistry.registeredProviders()` -> list all registered `OpenTuneProvider` types
    - Instantiate missing instances into registry on each emission (see Phase 8 step 24)

---

### Phase 11 — AGENTS.md

31. Update `AGENTS.md`:
    - Contracts: replace `StoreContracts.kt` / `CatalogBindingPlugin` / `PlaybackResolveDeps` mentions with `createInstance` pattern (`OpenTuneProvider` factory + `OpenTuneProviderInstance`)
    - Providers are store-blind — no read or write of any store; `createInstance` takes `Map<String,String>` (not JSON)
    - Add `MediaCover.LocalFile`; clarify thumbnail rule (disk cache in `:storage`, written by `:app` only)
    - Note `UserMediaStateStore` / `MediaStateKey` live in `:storage`; `:player` depends on `:storage`
    - `OpenTunePlayerScreen` signature: store + key are direct params, not embedded in `PlaybackSpec`

---

## Verification

```
./gradlew :storage:kspDebugKotlin :provider-api:compileDebugKotlin :providers:emby:compileDebugKotlin :providers:smb:compileDebugKotlin :player:compileDebugKotlin :app:compileDebugKotlin
```

Grep gates (all must return 0 results):
- `OpenTunePlaybackResumeStore` anywhere
- `AddServerDraftStore` anywhere
- `StoreContracts` anywhere
- `CatalogBindingDeps|PlaybackResolveDeps|CatalogBindingPlugin` anywhere
- `FavoritesStore|ProgressStore|ServerStore` anywhere
- `progressStore|favoritesStore` inside `providers/`
- `\.upsert\|ProgressStore` inside `EmbyPlaybackHooks.kt`
- Any `:storage` import inside `providers/emby/` or `providers/smb/`
- `userMediaState|mediaStateKey` inside `PlaybackSpec` (must NOT appear there)
