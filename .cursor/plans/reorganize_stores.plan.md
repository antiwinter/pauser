# Unified Config + Media State Persistence

## Decisions

| Topic | Decision |
|---|---|
| `StoreContracts.kt` | Deleted entirely, no replacement |
| `ConfigContracts.kt` | Deleted; `ProviderConfigBackend` replaced by new `OpenTuneProvider` shape |
| Provider field spec | `getFieldsSpec(): List<ServerFieldSpec>` — single method, same fields for add and edit; `display_name` absent |
| Validation | `validateFields(values): ValidationResult(hash: String, displayName: String)` — provider connects, authenticates, derives stable hash and display name; no store access |
| `display_name` | Returned by `validateFields`, stored in `ServerEntity.displayName` (app-only label); NOT in `fieldsJson`, NOT in `getFieldsSpec()` |
| Serdes | App owns JSON ↔ `Map<String,String>` round-trip; key set = `getFieldsSpec()` field IDs, preserved verbatim |
| `sourceId` | `"${providerId}_${hash}"` String, computed by app; PK of `ServerEntity`; used in routes and `media_state` |
| `ServerEntity` PK | `sourceId: String` (no autoGenerate Long); `OnConflict.ABORT` on insert → "server already exists" |
| `createInstance` | `createInstance(values: Map<String, String>): OpenTuneProviderInstance` — no sourceId param; instance is identity-free |
| `OpenTuneProviderInstance.sourceId` | Removed — instance has no identity fields; app registry maps `sourceId → instance` externally |
| Instance registry | Owned by `OpenTuneApplication`; `getOrCreate(sourceId)` with `Mutex`; lazy DB lookup on first route resolve; home populates eagerly |
| Edit → hash change | App: validate → compute newSourceId → if changed: insert new `ServerEntity`, delete old `ServerEntity` + cascade-delete `media_state` WHERE `sourceId = old`, swap registry — insert before delete to avoid brief gap |
| Edit → hash same | App: update `fieldsJson` + `displayName` in existing row, update registry instance |
| Server removal | App deletes `ServerEntity`, deletes `media_state` WHERE `sourceId = sourceId`, removes from registry |
| `CatalogBindingDeps` / `PlaybackResolveDeps` | Deleted |
| `CatalogBindingPlugin` / `MediaCatalogSource` | Deleted; replaced by `OpenTuneProviderInstance` methods |
| `:player` persistence | `OpenTunePlayerScreen` takes `UserMediaStateStore` + `MediaStateKey` as direct params; NOT via `PlaybackSpec` |
| `PlaybackSpec` | No persistence fields; pure playback contract from `:provider-api` |
| `:player` module dep | `:player` depends on `:storage` for `UserMediaStateStore`; `:storage` does NOT depend on `:player` |
| `UserMediaStateStore` | Interface in `:storage`; `RoomMediaStateStore` implements it |
| `MediaDetailModel` | Keeps remote `isFavorite` / `resumePositionMs`; app merges with local state in `DetailRoute` |
| `media_state` PK | `(providerId, sourceId: String, itemId)` |
| `OpenTunePlaybackResumeStore` | Deleted |
| `AddServerDraftStore` | Deleted; draft storage replaced by DataStore in `:storage` |
| Favorites nav | `observeAllFavorites()` snapshots carry `providerId + sourceId + itemId`; routes constructed as `browse/{providerId}/{sourceId}/{itemId}` |

---

## Plan

Collapse three persistence mechanisms (SharedPreferences resume store, Room favorites + progress tables, JSON draft file) into two clean surfaces — a single `media_state` Room table and a DataStore-backed draft store — all owned by `:app`/`:storage`. Providers become zero-persistence factories: they receive a `Map<String,String>` at construction and return protocol results only. `:player` is the sole local writer for playback state via `UserMediaStateStore` passed as a direct composable parameter.

---

### Phase 1 — Storage layer: replace entities and DAOs

1. In `storage/src/main/java/com/opentune/storage/ServerEntities.kt`:
   - Change `ServerEntity` PK from autoGenerate `Long id` to `@PrimaryKey val sourceId: String`; keep `providerId`, `displayName`, `fieldsJson`, `createdAtEpochMs`, `updatedAtEpochMs`
   - Add `MediaStateEntity`:
     ```kotlin
     @Entity(tableName = "media_state", primaryKeys = ["providerId", "sourceId", "itemId"])
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
   - Remove `FavoriteEntity` and `PlaybackProgressEntity`

2. In `storage/src/main/java/com/opentune/storage/Daos.kt`:
   - Update `ServerDao`: change all queries referencing `Long id` to `String sourceId`; add `deleteBySourceId(sourceId: String)`
   - Add `MediaStateDao`:
     - Partial `@Query UPDATE`: `upsertPosition`, `upsertSpeed`, `upsertFavorite`, `upsertCoverThumb`
     - `@Insert(onConflict = REPLACE) fun upsert(entity: MediaStateEntity)`
     - `suspend fun get(providerId, sourceId, itemId): MediaStateEntity?`
     - `fun observeForSource(providerId, sourceId): Flow<List<MediaStateEntity>>`
     - `fun observeAllFavorites(): Flow<List<MediaStateEntity>>` — WHERE `isFavorite = 1`
     - `suspend fun deleteBySource(sourceId: String)` — for server removal cascade
     - `suspend fun delete(providerId, sourceId, itemId)`
   - Remove `FavoriteDao` and `PlaybackProgressDao`

3. In `storage/src/main/java/com/opentune/storage/OpenTuneDatabase.kt`: bump version to **3**, replace `FavoriteEntity`/`PlaybackProgressEntity` with `MediaStateEntity`, remove obsolete DAO accessors, keep `fallbackToDestructiveMigration()`

---

### Phase 2 — Rework `:provider-api` contracts

4. Delete `provider-api/.../provider/StoreContracts.kt` entirely.

5. Delete `provider-api/.../provider/ConfigContracts.kt` (`ProviderConfigBackend`). `ServerFieldSpec`, `ServerFieldKind`, `OpenTuneProviderIds`, `SubmitResult` move to or stay in `ProviderContracts.kt` / a new `FieldContracts.kt`.

6. In `provider-api/.../provider/ProviderContracts.kt`, reshape `OpenTuneProvider`:
   ```kotlin
   interface OpenTuneProvider {
       val providerId: String                          // matches OpenTuneProviderIds constant
       fun getFieldsSpec(): List<ServerFieldSpec>      // single spec for add and edit; no display_name field
       suspend fun validateFields(values: Map<String, String>): ValidationResult
       fun createInstance(values: Map<String, String>): OpenTuneProviderInstance
   }

   sealed class ValidationResult {
       data class Success(val hash: String, val displayName: String) : ValidationResult()
       data class Error(val message: String) : ValidationResult()
   }
   ```

7. Add `OpenTuneProviderInstance` (same file or `InstanceContracts.kt`):
   ```kotlin
   interface OpenTuneProviderInstance {
       suspend fun loadBrowsePage(location: String, page: Int): BrowsePageResult
       suspend fun searchItems(scopeLocation: String, query: String, page: Int): BrowsePageResult
       suspend fun loadDetail(itemRef: String): MediaDetailModel
       suspend fun resolvePlayback(itemRef: String, startMs: Long, context: Context): PlaybackSpec
   }
   ```
   No identity fields, no store parameters.

8. In `provider-api/.../provider/CatalogContracts.kt`:
   - Add `MediaCover.LocalFile(absolutePath: String)` variant
   - Keep `isFavorite`, `resumePositionMs` on `MediaDetailModel` as remote-sourced values
   - Delete `CatalogBindingDeps`, `CatalogBindingPlugin`, `MediaCatalogSource`

9. In `provider-api/.../provider/PlaybackContracts.kt`:
   - Delete `PlaybackResolveDeps`, `PlaybackResumeAccessor`, `progressPersistenceKey`
   - `PlaybackSpec` stays as-is (pure playback contract); no persistence fields added

---

### Phase 3 — `:storage` wiring

10. In `storage/.../StorageBindings.kt`, replace `OpenTuneStorageBindings(serverStore, favoritesStore, progressStore)` with `OpenTuneStorageBindings(serverDao, mediaStateStore, appConfigStore)`:
    - `serverDao`: updated `RoomServerDao` (app-only access)
    - `mediaStateStore`: `RoomMediaStateStore` implementing `UserMediaStateStore` (see below)
    - `appConfigStore`: `DataStoreAppConfigStore` (Phase 4)
    - Factory: `OpenTuneStorageBindings.create(database: OpenTuneDatabase, context: Context)`

11. Add `UserMediaStateStore` interface in `:storage`:
    ```kotlin
    interface UserMediaStateStore {
        suspend fun get(providerId: String, sourceId: String, itemId: String): MediaStateSnapshot?
        suspend fun upsertPosition(providerId: String, sourceId: String, itemId: String, positionMs: Long)
        suspend fun upsertSpeed(providerId: String, sourceId: String, itemId: String, speed: Float)
        suspend fun upsertFavorite(providerId: String, sourceId: String, itemId: String, isFavorite: Boolean, title: String?, type: String?)
        suspend fun upsertCoverThumb(providerId: String, sourceId: String, itemId: String, path: String?)
        fun observeForSource(providerId: String, sourceId: String): Flow<List<MediaStateSnapshot>>
        fun observeAllFavorites(): Flow<List<MediaStateSnapshot>>
        suspend fun deleteBySource(sourceId: String)
    }
    data class MediaStateSnapshot(
        val providerId: String, val sourceId: String, val itemId: String,
        val positionMs: Long, val playbackSpeed: Float,
        val isFavorite: Boolean, val title: String?, val type: String?, val coverThumbPath: String?,
    )
    ```
    `RoomMediaStateStore` implements this backed by `MediaStateDao`.

---

### Phase 4 — DataStore for draft storage

12. In `gradle/libs.versions.toml`, add `datastore = "1.1.1"` and `androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }`.

13. In `storage/build.gradle.kts`, add `implementation(libs.androidx.datastore.preferences)`.

14. Implement `DataStoreAppConfigStore` in `:storage`: drafts stored as JSON strings keyed by `draft_{providerId}`. API: `loadDraft(providerId)`, `saveDraft(providerId, values)`, `clearDraft(providerId)`. Exposed via `OpenTuneStorageBindings.appConfigStore`.

---

### Phase 5 — Thumbnail disk cache

15. Add `ThumbnailDiskCache` in `storage/.../thumb/`: deterministic path from SHA-256 prefix of `"${providerId}|${sourceId}|${itemId}"`, `put(bytes)`, `exists()`, `delete()`, `absolutePath()`. Called only from `:app`.

16. In `app/.../ui/catalog/MediaEntryComponent.kt` and any other cover renderer, add branch for `MediaCover.LocalFile(path)` → `AsyncImage(model = File(path))` via Coil.

---

### Phase 6 — Provider modules (emby, smb)

17. Reshape `EmbyProvider` to implement `OpenTuneProvider`:
    - `getFieldsSpec()` — single field list (url, username, password); no `display_name`; consolidates current `serverAddFields` and `serverEditFields`
    - `validateFields(values)` — authenticates with Emby API, returns `ValidationResult.Success(hash = sha256(baseUrl + userId), displayName = serverName)` or `Error`
    - `createInstance(values)` → `EmbyProviderInstance(/* parsed values */)`
    - Delete `EmbyServerFields.kt` and `EmbyConfigBackend`

18. `EmbyProviderInstance` implements `OpenTuneProviderInstance`:
    - `loadBrowsePage`, `searchItems`, `loadDetail` — Emby catalog logic from parsed values; no store references
    - `resolvePlayback` — builds `PlaybackSpec` with `EmbyPlaybackHooks`; no store references

19. `EmbyPlaybackHooks`: remove `progressStore` constructor param and `progressStore.upsert(...)` in `onStop`. Keep only Emby HTTP session reporting.

20. Delete `EmbyBrowseBinding`, `EmbyDetailBinding`, `EmbySearchBinding` (all replaced by `EmbyProviderInstance`).

21. Same reshape for `SmbProvider` → `SmbProviderInstance`; `SmbPlaybackHooks`: no store references.

---

### Phase 7 — App: instance registry

22. In `app/.../OpenTuneApplication.kt`:
    - Remove `addServerDraftStore` field
    - Update `storageBindings` to `OpenTuneStorageBindings.create(database, applicationContext)`
    - Add `instanceRegistry: ProviderInstanceRegistry` — owns `Map<String, OpenTuneProviderInstance>` with `Mutex`; exposes `suspend fun getOrCreate(sourceId: String): OpenTuneProviderInstance?` (lazy DB lookup: reads `ServerEntity`, deserializes `fieldsJson` to map, calls `provider.createInstance(values)`)

---

### Phase 8 — App: server config repository

23. In `app/.../providers/ServerConfigRepository.kt`:
    - Draft methods → `storageBindings.appConfigStore`
    - `submitAdd(providerId, values)`:
      1. `provider.validateFields(values)` → `ValidationResult.Success(hash, displayName)`
      2. Compute `sourceId = "${providerId}_${hash}"`
      3. `storageBindings.serverDao.insert(ServerEntity(sourceId, providerId, displayName, fieldsJson, ...))` with `OnConflict.ABORT` → on conflict return "server already exists"
      4. `instanceRegistry.createAndRegister(sourceId, values)`
    - `submitEdit(sourceId, providerId, values)`:
      1. `provider.validateFields(values)` → `Success(newHash, newDisplayName)`
      2. Compute `newSourceId = "${providerId}_${newHash}"`
      3. If `newSourceId == sourceId`: update `displayName` + `fieldsJson` in existing row; update instance in registry
      4. If `newSourceId != sourceId`: insert new `ServerEntity` → delete old `media_state` (cascade) → delete old `ServerEntity` → swap registry (remove old, create new)
    - `removeServer(sourceId)`:
      1. `storageBindings.mediaStateStore.deleteBySource(sourceId)`
      2. `storageBindings.serverDao.deleteBySourceId(sourceId)`
      3. `instanceRegistry.remove(sourceId)`
    - Delete `app/.../drafts/AddServerDraftStore.kt`

---

### Phase 9 — App: home screen

24. In `app/.../ui/home/HomeRoute.kt`:
    - Observe `storageBindings.serverDao.observeAll()` to list servers
    - Display `ServerEntity.displayName` + `ServerEntity.providerId` (type icon via `OpenTuneProviderIds`)
    - On launch, eagerly populate `instanceRegistry` from observed server list
    - "Add server": `providerRegistry.registeredProviders()` → list provider types

---

### Phase 10 — App: catalog routes

25. In `BrowseRoute`, `SearchRoute`, `DetailRoute`:
    - Get instance via `instanceRegistry.getOrCreate(sourceId)` (lazy safe)
    - Call catalog methods on instance directly; no `CatalogBindingPlugin`, no deps
    - After `loadDetail`, read `storageBindings.mediaStateStore.get(providerId, sourceId, itemId)` and merge `isFavorite`, `resumePositionMs`, `coverThumbPath` with `MediaDetailModel` values (prefer local for position, merge for favorite)
    - Favorite toggle: `mediaStateStore.upsertFavorite(...)` + optimistic local update

26. In `app/.../ui/catalog/PlayerRoute.kt`:
    - Get instance via `instanceRegistry.getOrCreate(sourceId)`
    - Read resume: `storageBindings.mediaStateStore.get(providerId, sourceId, itemRef)?.positionMs`
    - Call `instance.resolvePlayback(itemRef, startMs, context)` → `PlaybackSpec`
    - Pass `storageBindings.mediaStateStore` and `MediaStateKey(providerId, sourceId, itemRef)` as separate params to `OpenTunePlayerScreen`
    - Drop all `OpenTunePlaybackResumeStore` usage

---

### Phase 11 — `:player` uses `UserMediaStateStore` directly

27. `OpenTunePlayerScreen` signature change:
    ```kotlin
    @Composable fun OpenTunePlayerScreen(
        spec: PlaybackSpec,
        mediaStateStore: UserMediaStateStore,
        mediaStateKey: MediaStateKey,
        onExit: () -> Unit,
    )
    ```

28. In `OpenTunePlayerScreen`:
    - Speed read on spec change: `mediaStateStore.get(...)?.playbackSpeed ?: 1f`
    - Speed write (DisposableEffect): `mediaStateStore.upsertSpeed(..., speed)`
    - `shutdown()`: `mediaStateStore.upsertPosition(..., positionMs)` **before** `spec.hooks.onStop(positionMs)` (local-first)

29. Delete `player/.../OpenTunePlaybackResumeStore.kt`.

30. Add `data class MediaStateKey(val providerId: String, val sourceId: String, val itemRef: String)` in `:storage` (or a shared location both `:app` and `:player` can see without a cycle).

---

### Phase 12 — AGENTS.md

31. Update `AGENTS.md`:
    - Contracts: `OpenTuneProvider` (factory: `getFieldsSpec`, `validateFields`, `createInstance`) + `OpenTuneProviderInstance` (protocol methods only)
    - No persistence interfaces in `:provider-api`; providers are store-blind
    - `sourceId` = `"${providerId}_${hash}"`, computed by app, String PK
    - `MediaCover.LocalFile`; thumbnail rule: disk cache in `:storage`, written by app only
    - Playback: local-first via `UserMediaStateStore` passed directly to `OpenTunePlayerScreen`
    - `:player` depends on `:storage`; `:storage` does not depend on `:player`

---

## Verification

```
./gradlew :storage:kspDebugKotlin :provider-api:compileDebugKotlin :providers:emby:compileDebugKotlin :providers:smb:compileDebugKotlin :player:compileDebugKotlin :app:compileDebugKotlin
```

Grep gates (all must return 0 results):
- `OpenTunePlaybackResumeStore` anywhere
- `AddServerDraftStore` anywhere
- `StoreContracts` anywhere
- `ProviderConfigBackend` anywhere
- `CatalogBindingDeps|PlaybackResolveDeps|CatalogBindingPlugin` anywhere
- `FavoritesStore|ProgressStore|ServerStore` anywhere
- `progressStore|favoritesStore` inside `providers/`
- `\.upsert\|ProgressStore` inside `EmbyPlaybackHooks.kt`
- Any `:storage` import inside `providers/emby/` or `providers/smb/`
- `sourceId.*Long|Long.*sourceId` in `ServerEntity` (sourceId must be String)
