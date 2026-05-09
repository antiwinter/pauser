# Emby Hierarchical Browse Cache

## Context

Save Emby browse API responses hierarchically in a JSON file on disk. As the user navigates (libraries → folder → subfolder), the JSON tree grows. Each browse action triggers a save.

Example: browsing libraries → `{"lib1": {}, "lib2": {}}`, then browsing into lib2 → `{"lib1": {}, "lib2": {"items": [...raw BaseItemDto...], "children": {"child1": {}, "child2": {}}}}`

## Files to modify

1. **NEW**: `providers/emby/src/main/java/com/opentune/emby/api/EmbyBrowseCache.kt`
2. **MODIFY**: `providers/emby/src/main/java/com/opentune/emby/api/EmbyProviderInstance.kt`
3. **MODIFY**: `providers/emby/src/main/java/com/opentune/emby/api/EmbyProvider.kt`

## Plan

### 1. New `EmbyBrowseCache.kt`

Manages a nested JSON file per server in `context.filesDir/emby_browse/emby_browse_<serverId>.json`.

- `suspend fun setItems(parentId: String, items: List<BaseItemDto>)` — reads JSON, creates/updates the node for `parentId` with `{ items: [...raw DTOs...], children: {childId: {}, ...} }`, writes back
- `suspend fun clear()` — deletes the file
- Uses `kotlinx.serialization.json` (already a dependency) for tree mutation
- Uses `Dispatchers.IO` for all file I/O

### 2. Modify `EmbyProviderInstance.kt`

- Add `context: Context` constructor parameter
- Create lazy `EmbyBrowseCache(context, fields.serverId ?: sha256(fields.baseUrl))`
- In `loadBrowsePage`: after fetching raw items from API, call `browseCache.setItems(cacheParentId, rawItems)` where `cacheParentId` is `"__root__"` for libraries root or the `location` string otherwise

### 3. Modify `EmbyProvider.kt`

- Add `@Volatile private var appContext: Context? = null` field
- In `bootstrap(context)`: store `appContext = context.applicationContext`
- In `createInstance`: pass stored context to `EmbyProviderInstance`

## Dependencies

No new dependencies. `kotlinx.serialization` and Android `Context` are already available.

## Verification

1. `./gradlew :providers:emby:assembleDebug` — build passes
2. Run on device, add Emby server, browse libraries → folders


adb shell run-as com.opentune.app cat files/emby_browse/emby_browse.json > 1.json
adb exec-out run-as com.opentune.app tar c files/emby_browse/images/ | tar xf - -C .