# Plan: Buffer config, Mbps overlay, custom progress bar colors

## Context

Three related player enhancements:
1. Persist a pre-buffer duration setting (default 5 min) in `appConfigStore` and wire it into ExoPlayer's `DefaultLoadControl`.
2. Show live network throughput (Mbps) in the top-right of the playback overlay.
3. Replace the stock Media3 `DefaultTimeBar` colors with custom played/buffered/not-buffered colors (blue / light gray / dim gray).

---

## 1. Pre-buffer setting in `appConfigStore`

**File:** `storage/src/main/java/com/opentune/storage/DataStoreAppConfigStore.kt`

Add a new `intPreferencesKey("pre_buffer_ms")` with a default of `300_000` (5 min).

```kotlin
private val preBufferMsKey = intPreferencesKey("pre_buffer_ms")
val DEFAULT_PRE_BUFFER_MS = 5 * 60 * 1000  // 300_000

val preBufferMsFlow: Flow<Int>
    get() = context.appConfigDataStore.data.map { prefs ->
        prefs[preBufferMsKey] ?: DEFAULT_PRE_BUFFER_MS
    }

suspend fun savePreBufferMs(ms: Int) {
    context.appConfigDataStore.edit { it[preBufferMsKey] = ms }
}
```

**Wire into ExoPlayer:**

`OpenTuneExoPlayer.createForBundledSources()` currently takes only `context`. Add a `preBufferMs: Int` parameter and build a `DefaultLoadControl` with it:

```kotlin
fun createForBundledSources(context: Context, preBufferMs: Int): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            preBufferMs,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .build()
    val renderersFactory = DefaultRenderersFactory(context)
        .setMediaCodecSelector(preferHardwareDecodersFirst)
        .setEnableDecoderFallback(true)
    return ExoPlayer.Builder(context, renderersFactory)
        .setLoadControl(loadControl)
        .build()
}
```

**Call site:** `OpenTunePlayerScreen.kt` — read `preBufferMs` from `appConfigStore` before creating the player. Since `appConfigStore` is already available in `PlayerStores`, collect the first value from `preBufferMsFlow` (or use `runBlocking`/`produceState`) before the `remember(instanceKey)` block.

---

## 2. Mbps overlay (top-right)

**Approach:** Add a `DefaultBandwidthMeter` to the player, expose it alongside `exo`, and display it in a Compose overlay.

**File:** `player/src/main/java/com/opentune/player/OpenTuneExoPlayer.kt`

Return both the player and the meter:

```kotlin
data class PlayerWithMeter(val player: ExoPlayer, val bandwidthMeter: DefaultBandwidthMeter)

fun createForBundledSources(context: Context, preBufferMs: Int): PlayerWithMeter {
    val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
    ...
    val player = ExoPlayer.Builder(context, renderersFactory)
        .setLoadControl(loadControl)
        .setBandwidthMeter(bandwidthMeter)
        .build()
    return PlayerWithMeter(player, bandwidthMeter)
}
```

**File:** `player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt`

In the existing progress-tick `LaunchedEffect` loop (lines ~328–349), also read `bandwidthMeter.bitrateEstimate` and store it in a `mutableStateOf<Float>`:

```kotlin
val mbpsState = remember(instanceKey) { mutableStateOf(0f) }

// inside the tick loop:
mbpsState.value = bandwidthMeter.bitrateEstimate / 1_000_000f
```

Add a new Compose overlay in the `Box` (after `infoOsd.Osd()`):

```kotlin
MbpsOverlay(mbps = mbpsState.value)
```

**New file:** `player/src/main/java/com/opentune/player/MbpsOverlay.kt`

```kotlin
@Composable
internal fun MbpsOverlay(mbps: Float) {
    if (mbps <= 0f) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Text(
            text = "%.1f MB/s".format(mbps),
            modifier = Modifier.padding(12.dp),
            color = Color.White,
            fontSize = 13.sp,
        )
    }
}
```

---

## 3. Custom progress bar colors (blue / light gray / dim gray)

The stock `DefaultTimeBar` colors are set via XML attributes on the `PlayerView`. The cleanest approach is to override them in the app's theme or via a custom `exo_styled_progress` style.

**File:** `player/src/main/res/values/colors.xml` (create if absent, or add to existing)

```xml
<color name="opentune_progress_played">#FF2979FF</color>   <!-- blue -->
<color name="opentune_progress_buffered">#FFD0D0D0</color> <!-- light gray -->
<color name="opentune_progress_unplayed">#FF606060</color> <!-- dim gray -->
```

**File:** `player/src/main/res/values/styles.xml` (create if absent, or add to existing)

Override the Media3 `DefaultTimeBar` attributes:

```xml
<style name="OpenTuneTimeBar" parent="ExoStyledControls.TimeBar">
    <item name="played_color">@color/opentune_progress_played</item>
    <item name="buffered_color">@color/opentune_progress_buffered</item>
    <item name="unplayed_color">@color/opentune_progress_unplayed</item>
    <item name="scrubber_color">@color/opentune_progress_played</item>
</style>
```

Apply it in `OpenTuneTvPlayerView` (or `OpenTunePlayerView`) after the `PlayerView` is inflated, by setting the time bar style programmatically, OR by adding `app:time_bar_min_update_interval` and the style reference to the `PlayerView` in the layout XML.

The simplest path: in `OpenTuneTvPlayerView.kt`, after `setControllerLayoutId(R.layout.opentune_player_control_view)`, call:

```kotlin
val timeBar = findViewById<DefaultTimeBar>(R.id.exo_progress)
timeBar?.setPlayedColor(Color.parseColor("#2979FF"))
timeBar?.setBufferedColor(Color.parseColor("#D0D0D0"))
timeBar?.setUnplayedColor(Color.parseColor("#606060"))
timeBar?.setScrubberColor(Color.parseColor("#2979FF"))
```

`DefaultTimeBar` exposes `setPlayedColor`, `setBufferedColor`, `setUnplayedColor`, `setScrubberColor` directly — no XML required.

---

## Files to modify

| File | Change |
|---|---|
| `storage/.../DataStoreAppConfigStore.kt` | Add `preBufferMsKey`, `preBufferMsFlow`, `savePreBufferMs` |
| `player/.../OpenTuneExoPlayer.kt` | Add `preBufferMs` param, `DefaultLoadControl`, `DefaultBandwidthMeter`, return `PlayerWithMeter` |
| `player/.../OpenTunePlayerScreen.kt` | Read `preBufferMs` from store, use `PlayerWithMeter`, poll `bitrateEstimate`, add `MbpsOverlay` |
| `player/.../OpenTuneTvPlayerView.kt` | Set `DefaultTimeBar` colors after inflate |
| `player/src/main/res/values/colors.xml` | Add 3 progress bar color values |

## New files

| File | Purpose |
|---|---|
| `player/.../MbpsOverlay.kt` | Compose overlay showing live Mbps in top-right |

---

## Verification

1. Build and install on device/emulator.
2. Start playback — confirm `DefaultLoadControl` max buffer is 5 min (check via `exo.bufferedPosition` growing up to ~5 min ahead).
3. Confirm Mbps text appears top-right during buffering/playback and updates live.
4. Confirm progress bar shows blue (played), light gray (buffered), dim gray (not buffered).
5. Seek to an unbuffered position — verify buffered segment color updates correctly.
