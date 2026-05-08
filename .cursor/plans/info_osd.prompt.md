# Plan: Info OSD + inline decode fallback

**TL;DR**: Delete `DecodeFallback.kt`. Inline a single `onPlayerError` listener directly into the screen. Add a top-bar info OSD showing `displayTitle`, duration, video/audio MIME with ⚠️ on disabled tracks, toggled by UP×3 (show) / UP×1 (hide).

---

## Phase 1 — `OpenTuneTvPlayerView`: new `onDpadUp` hook

`onDpadKey != null` already intercepts all DPAD keys for menu / subtitle-adjust modes. `onDpadUp` fires **only when `onDpadKey == null`** (normal transport mode), so the info OSD never fights other OSDs:

1. Add `var onDpadUp: (() -> Unit)? = null` to `OpenTuneTvPlayerView.kt`
2. In `dispatchKeyEvent`, after the existing `onDpadKey` routing block, add: if `onDpadKey == null` and `keyCode == KEYCODE_DPAD_UP` and `onDpadUp != null` → call `onDpadUp()`, then `showController()`, return true
3. Add `onDpadUp: (() -> Unit)? = null` param to `OpenTunePlayerView.kt` and thread it to `tv.onDpadUp`

---

## Phase 2 — Inline decode error handling

**File**: `OpenTunePlayerScreen.kt`

4. Remove all `audio.*` imports except `rememberAudioController`. Move the log tag as a private const `PLAYER_LOG = "OpenTunePlayer"`.
5. Replace `failedAudioCodec: String?` / `failedVideoCodec: String?` with `audioDisabled: Boolean` / `videoDisabled: Boolean`.
6. Add `videoMime: String?` / `audioMime: String?` state vars.
7. Extend `LaunchedEffect(instanceKey)` reset block to also reset all four new vars.
8. Replace the two `DisposableEffect` fallback blocks with **one** that installs a single `Player.Listener` implementing:
   - `onTracksChanged(tracks)` → update `videoMime` / `audioMime` from the selected track groups
   - `onPlayerError(error)` → walk cause chain inline; two `AtomicBoolean` gates; audio failure → `TRACK_TYPE_AUDIO` disabled + `audioDisabled = true` + re-prepare; video failure → `TRACK_TYPE_VIDEO` disabled + `videoDisabled = true` + re-prepare; else → `Log.e`. All ExoPlayer mutations via `mainHandler.post`.

---

## Phase 3 — Info OSD + UP toggle

**New file `player/.../player/InfoOsd.kt`** — follows the `rememberXxx` controller pattern of `rememberPlayerMenu`, `rememberSubtitleController`, etc.:

9. `rememberInfoOsdController(instanceKey, spec, videoMime, videoDisabled, audioMime, audioDisabled)` owns all OSD state:
   - `showInfoOsd: Boolean` and `upPressCount: Int` (reset when `instanceKey` changes)
   - `LaunchedEffect(upPressCount)`: if count > 0, delay 2 s, reset to 0 (consecutive-press timeout)
   - `onDpadUp: () -> Unit` — if OSD showing → hide + reset count; else → increment; on 3 → show + reset
   - `Osd()` composable — top-bar `Row` (semi-transparent black, `TopCenter` aligned), only rendered when visible:
     - `spec.displayTitle` — white
     - duration formatted as `H:MM:SS` / `M:SS` / `"?"` — gray
     - `videoMime` (white) + ` ⚠️` if `videoDisabled`
     - `audioMime` (white) + ` ⚠️` if `audioDisabled`
     - `null` MIME values are omitted

**In `OpenTunePlayerScreen.kt`:**

10. Call `val infoOsd = rememberInfoOsdController(instanceKey, spec, videoMime, videoDisabled, audioMime, audioDisabled)`.
11. Pass `infoOsd.onDpadUp` to `OpenTunePlayerView`.
12. Replace the full-screen black error overlay with `infoOsd.Osd()` in the UI `Box`.

---

## Phase 4 — Delete `DecodeFallback.kt`

14. Delete `player/src/main/java/com/opentune/player/audio/DecodeFallback.kt`.

---

## Relevant files

- `player/src/main/java/com/opentune/player/OpenTuneTvPlayerView.kt` — `onDpadUp` var + dispatch
- `player/src/main/java/com/opentune/player/OpenTunePlayerView.kt` — thread `onDpadUp` param
- `player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt` — fallback logic (phase 2) + `rememberInfoOsdController` call (phase 3)
- `player/src/main/java/com/opentune/player/InfoOsd.kt` — NEW: `rememberInfoOsdController` + all OSD state + `Osd()` composable
- `player/src/main/java/com/opentune/player/audio/DecodeFallback.kt` — DELETE

## Verification

1. `get_errors` on all three modified files + `AudioController.kt`
2. Confirm `DecodeFallback.kt` gone; grep for `FallbackMedia` / `createVideoDecodeFallbackListener` returns nothing

---

## Decisions

- `FallbackMedia` sealed class gone — error handler calls `spec.mediaSourceFactory.create()` inline
- Full-screen black error overlay removed — the info OSD ⚠️ replaces it
- `onDpadUp` priority is lower than `onDpadKey`, so menu and subtitle-adjust modes are unaffected
- 2-second timeout resets the UP press count so the gesture requires consecutive presses
