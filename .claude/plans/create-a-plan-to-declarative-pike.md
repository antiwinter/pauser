# Plan: Lift storage context out of player composable signatures via CompositionLocal

## Context

Storage-related keys (`entryStateStore`, `entryStateKey`, `parentStateKey`, `seriesStateKey`, `seriesSeasonNumber`, `seriesEpisodeNumber`, `appConfigStore`) are currently threaded as explicit params through `OpenTunePlayer` → `TvPlayer`/`PadPlayer` → `rememberPlaybackEngine`. The player composables are pure UI and shouldn't carry storage concerns — only `rememberPlaybackEngine` and the controllers actually use them. The fix is a `CompositionLocal` bundle provided once by `PlayerRoute` and consumed directly by the engine layer.

---

## Approach: `LocalPlaybackStorageContext` CompositionLocal

### 1. New file — `player/.../PlaybackStorageContext.kt`

```kotlin
data class PlaybackStorageContext(
    val entryStateStore: EntryStateStore,
    val entryStateKey: EntryStateKey,
    val parentStateKey: EntryStateKey? = null,
    val seriesStateKey: EntryStateKey? = null,
    val seriesSeasonNumber: Int? = null,
    val seriesEpisodeNumber: Int? = null,
    val appConfigStore: AppConfigStore? = null,
)

val LocalPlaybackStorageContext = compositionLocalOf<PlaybackStorageContext> {
    error("No PlaybackStorageContext provided")
}
```

### 2. `PlayerRoute.kt` — provide the context, stop passing storage params

```kotlin
CompositionLocalProvider(
    LocalPlaybackStorageContext provides PlaybackStorageContext(
        entryStateStore = app.storageBindings.entryStateStore,
        entryStateKey = stateKey,
        parentStateKey = parentKey,
        seriesStateKey = seriesKey,
        seriesSeasonNumber = entryInfo?.seasonNumber,
        seriesEpisodeNumber = entryInfo?.indexNumber,
        appConfigStore = app.storageBindings.appConfigStore,
    )
) {
    PlayerShell {
        OpenTunePlayer(
            spec = spec!!,
            startMs = startMs,
            onExit = onExit,
            initialSubtitleTrackId = initialSubtitleTrackId,
            initialAudioTrackId = initialAudioTrackId,
            initialSubtitleOffsetFraction = initialSubtitlePrefs.offsetFraction,
            initialSubtitleSizeScale = initialSubtitlePrefs.sizeScale,
        )
    }
}
```

### 3. `OpenTunePlayer` / `TvPlayer` / `PadPlayer` — remove all storage params

These composables drop `entryStateStore`, `entryStateKey`, `parentStateKey`, `seriesStateKey`, `seriesSeasonNumber`, `seriesEpisodeNumber`, `appConfigStore`. Their signatures become:

```kotlin
fun OpenTunePlayer(
    spec: PlaybackSpec,
    startMs: Long = 0L,
    onExit: () -> Unit,
    initialSubtitleTrackId: String? = null,
    initialAudioTrackId: String? = null,
    initialSubtitleOffsetFraction: Float = 0f,
    initialSubtitleSizeScale: Float = 1f,
)
```

### 4. `rememberPlaybackEngine` — read from `LocalPlaybackStorageContext`

```kotlin
@Composable
internal fun rememberPlaybackEngine(
    spec: PlaybackSpec,
    startMs: Long,
    initialSubtitleTrackId: String?,
    initialAudioTrackId: String?,
    initialSubtitleOffsetFraction: Float,
    initialSubtitleSizeScale: Float,
): PlaybackEngine {
    val ctx = LocalPlaybackStorageContext.current
    // use ctx.entryStateStore, ctx.entryStateKey, ctx.parentStateKey, etc.
}
```

`PlaybackEngine` class constructor and `SubtitleController`/`AudioController` constructors are unchanged — they already receive the keys as constructor params from `rememberPlaybackEngine`. Only the call sites that thread them through the composable chain are removed.

---

## Files to modify

| File | Change |
|------|--------|
| [PlaybackStorageContext.kt](player/src/main/java/com/opentune/player/PlaybackStorageContext.kt) | **New file** — `PlaybackStorageContext` data class + `LocalPlaybackStorageContext` |
| [PlayerRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/PlayerRoute.kt) | Wrap player call in `CompositionLocalProvider`; remove storage params from `OpenTunePlayer` call |
| [OpenTunePlayer.kt](player/src/main/java/com/opentune/player/OpenTunePlayer.kt) | Remove storage params; forward only playback params to `TvPlayer`/`PadPlayer` |
| [TvPlayer.kt](player/src/main/java/com/opentune/player/ui/tv/TvPlayer.kt) | Remove storage params; forward only playback params to `rememberPlaybackEngine` |
| [PadPlayer.kt](player/src/main/java/com/opentune/player/ui/pad/PadPlayer.kt) | Same as `TvPlayer` |
| [PlaybackEngine.kt](player/src/main/java/com/opentune/player/engine/PlaybackEngine.kt) | `rememberPlaybackEngine` reads `LocalPlaybackStorageContext.current` instead of accepting storage params |

`PlaybackEngine` class, `SubtitleController`, and `AudioController` constructors are **not changed** — they already receive keys as constructor args from `rememberPlaybackEngine`.

---

## Verification

1. `./gradlew assembleDebug` — clean build, no unresolved references.
2. Play an episode → subtitle/audio track inheritance still works (reads from parent/series keys).
3. Track change during playback → writes to episode + parent + series keys.
4. Series progress (`positionMs` packed encoding) still saved on tick and release.
