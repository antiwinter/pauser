# Plan: Fix Subtitle Position Persist, Bottom Alignment, and Series-Level Track Persistence

## Three Issues

1. **Subtitle position/size not persisting**: User must adjust every time. The save happens via `scope.launch(Dispatchers.IO)` in `confirmAdjust()`, which can be silently cancelled if the player/screen is dismissed before the IO coroutine completes. Fix: use `NonCancellable` to guarantee the save persists.

2. **Subtitle top-anchored instead of bottom-anchored**: The subtitle view's top edge is at the chosen position, lines grow downward. Fix: apply a negative translationY offset equal to the view's rendered height so the bottom edge anchors at the chosen position.

3. **Track selection not persisting across episodes in a series**: Currently per-episode. Fix: add `seriesId` to the data flow, store/retrieve tracks at the series level for episodes.

---

## Fix 1: Subtitle Position/Size Persistence

**Root cause**: `confirmAdjust()` in `SubtitleController.kt` (line 101-108) uses `scope.launch(Dispatchers.IO)` to save. If the user exits the player immediately after adjusting, the scope is disposed and the save coroutine is cancelled before writing to DataStore.

**Fix**: In `SubtitleController.kt`, wrap the save in `withContext(NonCancellable)` so it always completes:

```kotlin
// SubtitleController.kt, confirmAdjust()
private fun confirmAdjust() {
    isAdjustActiveState.value = false
    val offset = offsetFractionState.value
    val scale = sizeScaleState.value
    scope.launch {
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                stores.appConfigStore?.saveSubtitlePrefs(SubtitlePrefs(offset, scale))
            }
        }
    }
}
```

Also add an import for `kotlinx.coroutines.NonCancellable`.

---

## Fix 2: Subtitle Bottom Alignment

**Root cause**: `sv.translationY = subtitleTranslationYPx` (OpenTunePlayerView.kt:76) shifts the view downward from its default position. The view's top edge lands at that Y coordinate. Multi-line subtitles grow downward from there.

**Fix**: After the subtitle view renders, measure its height and subtract it from `translationY` so the bottom edge anchors at the target position.

In `OpenTunePlayerView.kt`, in the `update` block of `AndroidView`, after setting the scale:

```kotlin
// After setting scale, measure the rendered subtitle view height and
// offset by it so the bottom edge (not top) anchors at the target position.
sv.post {
    val scaledHeight = sv.measuredHeight
    if (scaledHeight > 0) {
        sv.translationY = subtitleTranslationYPx - scaledHeight
    }
}
```

However, since `subtitleTranslationYPx` is passed as a parameter to the composable and changes trigger `update`, we need to handle the fact that `measuredHeight` changes when `scaleX/scaleY` change. The cleanest approach is to use a `ViewTreeObserver.OnGlobalLayoutListener` or simply compute it in the `update` block after `requestLayout()`:

```kotlin
sv.translationY = subtitleTranslationYPx
sv.scaleX = subtitleSizeScale
sv.scaleY = subtitleSizeScale
// ... style set ...
sv.requestLayout()

// Measure after layout pass to get scaled height, then anchor to bottom
sv.viewTreeObserver.addOnGlobalLayoutListener(
    object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            sv.viewTreeObserver.removeOnGlobalLayoutListener(this)
            val h = sv.height
            if (h > 0) {
                sv.translationY = subtitleTranslationYPx - h
            }
        }
    }
)
sv.invalidate()
```

Actually, `post {}` is simpler and more reliable — it runs after the layout pass:

```kotlin
sv.scaleX = subtitleSizeScale
sv.scaleY = subtitleSizeScale
sv.setStyle(...)
sv.setPadding(hPad, 0, hPad, 0)
sv.requestLayout()

// Bottom-anchor: after layout, shift up by the rendered height
sv.post {
    val h = sv.height
    if (h > 0) {
        sv.translationY = subtitleTranslationYPx - h
    }
}
sv.invalidate()
```

**Files**: `player/src/main/java/com/opentune/player/OpenTunePlayerView.kt` (update block, ~lines 71-93)

---

## Fix 3: Series-Level Track Persistence

### 3a. Add `SeriesId` to Emby DTO

**File**: `providers/emby/src/main/java/com/opentune/emby/dto/Items.kt`

Add `@SerialName("SeriesId") val seriesId: String? = null` to `BaseItemDto`.

### 3b. Thread `seriesId` through `EntryInfo`

**File**: `contracts/src/main/java/com/opentune/provider/CatalogContracts.kt`

Add `val seriesId: String? = null` to `EntryInfo` data class.

### 3c. Map seriesId in Emby provider

**File**: `providers/emby/src/main/java/com/opentune/emby/EmbyProviderInstance.kt`

In `toListItem()`, add `seriesId = item.seriesId` to the `EntryInfo` construction.

### 3d. Pass `seriesId` through navigation

**File**: `app/src/main/java/com/opentune/app/navigation/Routes.kt`

Find the `player()` route function. Add an optional `seriesId: String? = null` parameter. The route pattern becomes something like:
```kotlin
fun player(protocol: String, sourceId: String, itemRef: String, startMs: Long = 0L, seriesId: String? = null): String
```

### 3e. Extend `MediaStateKey` with optional `seriesId`

**File**: `contracts/src/main/java/com/opentune/storage/MediaStateContracts.kt`

Add `val seriesId: String? = null` to `MediaStateKey`. Update extension functions.

### 3f. Add `seriesId` column to MediaStateEntity

**File**: `storage/src/main/java/com/opentune/storage/ServerEntities.kt`

Add `val seriesId: String? = null` to `MediaStateEntity`.

### 3g. Add DAO methods for series-level track lookup

**File**: `storage/src/main/java/com/opentune/storage/Daos.kt`

Add a new query that looks up track by seriesId as fallback:
```kotlin
@Query("SELECT * FROM media_state WHERE protocol = :protocol AND sourceId = :sourceId AND seriesId = :seriesId LIMIT 1")
suspend fun getBySeries(protocol: String, sourceId: String, seriesId: String): MediaStateEntity?

@Query("UPDATE media_state SET selectedSubtitleTrackId = :id, updatedAtEpochMs = :now WHERE protocol = :protocol AND sourceId = :sourceId AND seriesId = :seriesId")
suspend fun updateSubtitleTrackBySeries(protocol: String, sourceId: String, seriesId: String, id: String?, now: Long)

@Query("UPDATE media_state SET selectedAudioTrackId = :id, updatedAtEpochMs = :now WHERE protocol = :protocol AND sourceId = :sourceId AND seriesId = :seriesId")
suspend fun updateAudioTrackBySeries(protocol: String, sourceId: String, seriesId: String, id: String?, now: Long)
```

### 3h. Ensure row with seriesId in RoomMediaStateStore

**File**: `storage/src/main/java/com/opentune/storage/RoomMediaStateStore.kt`

Update `ensureRow` to accept and store `seriesId`. Add series-level upsert methods.

### 3i. Add series-level persistence methods to UserMediaStateStore

**File**: `contracts/src/main/java/com/opentune/storage/MediaStateContracts.kt`

Add to the interface:
```kotlin
suspend fun getSeriesTrack(protocol: String, sourceId: String, seriesId: String): MediaStateSnapshot?
suspend fun upsertSeriesSubtitleTrack(protocol: String, sourceId: String, seriesId: String, trackId: String?)
suspend fun upsertSeriesAudioTrack(protocol: String, sourceId: String, seriesId: String, trackId: String?)
```

### 3j. Pass seriesId from PlayerRoute to the player

**File**: `app/src/main/java/com/opentune/app/ui/catalog/PlayerRoute.kt`

Accept `seriesId` parameter. When loading initial tracks, if the item is an episode (has seriesId), also check series-level state as fallback.

### 3k. Wire seriesId in PlayerRoute / navigation

**File**: `app/src/main/java/com/opentune/app/navigation/Routes.kt`

Update the player route parsing to extract `seriesId` from the route.

### 3l. Wire seriesId from DetailRoute when navigating to player

**File**: `app/src/main/java/com/opentune/app/ui/catalog/DetailRoute.kt`

When calling `nav.navigate(Routes.player(...))` for an episode, pass `episode.seriesId`.

### 3m. Update OpenTunePlayerScreen to load/save series tracks

**File**: `player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt`

Pass `seriesId` to subtitle/audio controllers. When `seriesId` is non-null, load series-level track as initial value and save to series-level on selection.

---

## Files Modified Summary

| File | Change |
|------|--------|
| `player/src/main/java/com/opentune/player/subtitle/SubtitleController.kt` | Fix 1: NonCancellable save |
| `player/src/main/java/com/opentune/player/OpenTunePlayerView.kt` | Fix 2: Bottom-anchor via measured height offset |
| `providers/emby/src/main/java/com/opentune/emby/dto/Items.kt` | Fix 3: Add SeriesId field |
| `contracts/src/main/java/com/opentune/provider/CatalogContracts.kt` | Fix 3: Add seriesId to EntryInfo |
| `providers/emby/src/main/java/com/opentune/emby/EmbyProviderInstance.kt` | Fix 3: Map seriesId in toListItem |
| `app/src/main/java/com/opentune/app/navigation/Routes.kt` | Fix 3: Add seriesId param to player route |
| `contracts/src/main/java/com/opentune/storage/MediaStateContracts.kt` | Fix 3: Add seriesId to MediaStateKey + interface methods |
| `storage/src/main/java/com/opentune/storage/ServerEntities.kt` | Fix 3: Add seriesId column |
| `storage/src/main/java/com/opentune/storage/Daos.kt` | Fix 3: Add getBySeries and update-by-series queries |
| `storage/src/main/java/com/opentune/storage/RoomMediaStateStore.kt` | Fix 3: Implement series-level methods |
| `app/src/main/java/com/opentune/app/ui/catalog/PlayerRoute.kt` | Fix 3: Accept seriesId, load series-level tracks |
| `app/src/main/java/com/opentune/app/ui/catalog/DetailRoute.kt` | Fix 3: Pass seriesId when navigating to player |
| `player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt` | Fix 3: Wire seriesId to controllers |
| `player/src/main/java/com/opentune/player/audio/AudioController.kt` | Fix 3: Accept seriesId, save to series-level |
| `storage/src/main/java/com/opentune/storage/OpenTuneDatabase.kt` | Fix 3: Bump DB version |

---

## DB Migration

Bump `OpenTuneDatabase` version from 8 to 9. Since we're adding a nullable column with default, we can use a simple ALTER TABLE migration:

```kotlin
@Database(..., version = 9)
abstract class OpenTuneDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_state ADD COLUMN seriesId TEXT")
            }
        }
    }
    // ...
}
```

---

## Verification

1. Adjust subtitle position, confirm with BACK, exit player, re-enter → position should persist (Fix 1)
2. Single-line subtitle: bottom of text should be at the chosen position, not top (Fix 2)
3. Two-line subtitle: bottom of the second line should be at the chosen position, growing upward (Fix 2)
4. Select subtitle track on Episode 1 → Episode 2 of same series should auto-select same track (Fix 3)
5. Select audio track on Episode 1 → Episode 2 of same series should auto-select same audio (Fix 3)
6. Non-episode content (movies, etc.) should continue to work with per-item track persistence (Fix 3)
