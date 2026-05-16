# Plan: Fix Player OSD, Menu, and Subtitle Issues

**TL;DR:** Six targeted changes across `InfoOsd.kt`, `OpenTunePlayerScreen.kt`, `OpenTuneTvPlayerView.kt`, `PlayerMenu.kt`, and the subtitle view setup. The `infoOSD` merge is the biggest change — it replaces the triple-press mechanism with visibility tied to `PlayerView`'s controller lifecycle. The menu OSD-bleedthrough bug is caused by `showController()` being called after menu close; fixing it means making menu key handling return *without* calling `showController`. The two-phase hide is the Media3 controller's own behavior which needs to be disabled.

---

## Steps

### 1. Merge InfoOSD + MbpsOverlay into playbackOverlay — `InfoOsd.kt`

- Remove the triple-press `countState` / `toggle()` mechanism entirely.
- Change `InfoOsd` to expose `show()` and `hide()` instead of `toggle()`.
- The `Osd()` composable renders in `Alignment.TopCenter` as it does today — content stays the same (title, duration, video/audio codec info) plus absorb `MbpsOverlay`'s Mbps reading into the same row.
- `showState` is now driven externally by the controller visibility callbacks (step 2), not by triple-press.

### 2. Wire InfoOSD visibility to PlayerView controller — `OpenTunePlayerView.kt`

- Add a `onControllerVisibilityChanged: ((Boolean) -> Unit)?` callback to `OpenTunePlayerView`.
- In the `update` block of the `AndroidView`, set `tv.setControllerVisibilityListener { visibility -> onControllerVisibilityChanged?.invoke(visibility == View.VISIBLE) }` (in addition to the existing log listener).
- In `OpenTunePlayerScreen.kt`, pass `onControllerVisibilityChanged = { visible -> if (visible) infoOsd.show() else infoOsd.hide() }`.
- Remove the `infoOsd.toggle()` call from the `onKey` DPAD_UP branch. The UP key already calls `showController()` via `super.dispatchKeyEvent`; that propagation is enough.
- Remove `MbpsOverlay` from the `Box` and add its value as a parameter to `InfoOsd`.

### 3. Pause→play OSD suppression — `OpenTuneTvPlayerView.kt`

- Root cause: in `dispatchKeyEvent`, the OK/ENTER/CENTER key unconditionally calls `showController()` after toggling play/pause — whether the player is resuming OR pausing. This means pressing OK to resume from pause always brings up the OSD.
- Fix: in the play/pause toggle branch of `dispatchKeyEvent`, only call `showController()` when the action is **pausing** (`p.isPlaying` is true before the toggle). When resuming from pause (`!p.isPlaying`), do **not** call `showController()`.
- Same rule applies to `KEYCODE_MEDIA_PLAY` — do not call `showController()` on play.
- `KEYCODE_MEDIA_PAUSE` / `KEYCODE_MEDIA_STOP` keep calling `showController()` (pausing → user wants to see the OSD).
- Covers both cases: OSD hidden and OSD in countdown — neither is disrupted when resuming.

### 4. Fix menu keys leaking to OSD — `PlayerMenu.kt`

- Root cause: CENTER/ENTER **ACTION_DOWN** sets `depth = 0`, which clears `isOpen`. The corresponding **ACTION_UP** then arrives with `isOpen = false`, bypasses the interceptor, and falls through to `super.dispatchKeyEvent()` inside `OpenTuneTvPlayerView`, where Media3's `PlayerView` calls `showController()`.
- Fix: **close the menu on ACTION_UP, not ACTION_DOWN**. In `handleKeyEvent`, on confirm key ACTION_DOWN: call `entry.onSelect()` but keep `depth` unchanged (menu stays open). On confirm key ACTION_UP: set `depth = 0`. Since `depth > 0` when ACTION_UP arrives, the interceptor still owns it, consumes it, then closes — nothing leaks to `super.dispatchKeyEvent()`.
- Same applies to the LEFT/DPAD_LEFT "go back one level" key if it also has an ACTION_UP leak.
- No flag, no extra state — the existing `if (event.action != KeyEvent.ACTION_DOWN) return true` guard in `handleKeyEvent` already consumes all ACTION_UPs while the menu is open; the only change is deferring `depth = 0` to that UP.

### 5. Menu scrolling + BACK support — `PlayerMenu.kt`

- **BACK key**: Verify `KEYCODE_BACK` is handled through `BackHandler` → `menu.handleBack()` path in `OpenTunePlayerScreen`. If `KEYCODE_BACK` also arrives through `dispatchKeyEvent`, add it to `handleKey()` in `MenuOverlay`.
- **Scrolling**: Replace `entries.forEachIndexed` in the depth=1 branch with a `LazyColumn` bounded to `max = MENU_MAX_VISIBLE_ITEMS * ITEM_HEIGHT_DP` (6 items × 44dp = 264dp). Use `rememberLazyListState()` with `LaunchedEffect(topIndex) { listState.scrollToItem(topIndex) }` to keep cursor in view.
- Also add `LaunchedEffect(subIndex)` for depth=2's existing `LazyColumn` to auto-scroll to `subIndex`.
- Constants: `MENU_MAX_VISIBLE_ITEMS = 6`, `ITEM_HEIGHT_DP = 44`.

### 6. Fix two-phase hide — `OpenTunePlayerView.kt`

- Media3's `PlayerControlView` animates to a minimal progress bar before fully hiding — this is the "two-phase" behavior.
- Fix: in the `ControllerVisibilityListener`, when `visibility` transitions from `VISIBLE` to anything else, immediately call `pv.hideController()` if the controller is not already fully hidden. This skips the intermediate shrink phase.
- Alternative: call `view.setControllerShowTimeoutMs(-1)` and manage show/hide manually via the OSD suppression logic from step 3, calling `hideController()` directly on timeout via a `LaunchedEffect`.

### 7. Subtitle background padding + rounded corners — `OpenTunePlayerView.kt`

- In the `AndroidView` `update` block, call `sv.setPadding(paddingPx, 0, paddingPx, 0)` with equal left/right padding (currently 0).
- For rounded corners: set `sv.background = GradientDrawable().apply { cornerRadius = 8.dp.toPx(); setColor(Color.BLACK.copy(alpha=0.6).toArgb()) }`, then `sv.clipToOutline = true` and `sv.outlineProvider = ViewOutlineProvider.BACKGROUND`.

---

## Verification

- Build + install debug: `.\gradlew assembleDebug` → `adb install -r … && adb shell am start -n …`
- Manual: open player → press UP/DOWN/seek → confirm OSD shows with info bar at top (title, duration, codec, Mbps)
- Manual: let OSD auto-hide → press play from pause → confirm OSD does NOT reappear
- Manual: press pause → press play while OSD is still visible in countdown → confirm OSD stays visible (not suppressed)
- Manual: open menu → navigate with UP/DOWN/BACK → confirm OSD not triggered; BACK closes menu level by level
- Manual: open menu with long track list → confirm scrolling window of 6 entries with cursor auto-scroll
- Manual: let controller timeout → confirm single-step hide (no shrink-to-bar intermediate phase)
- Manual: check subtitle cue boxes for uniform horizontal padding and rounded corners

---

## Decisions

- InfoOSD trigger: unified with playbackOverlay (no more triple-press)
- Hiding = "in countdown OR fully gone" — both cases suppress OSD on pause→play
- Menu keys are fully independent from OSD (no suppress logic; fix the leak at source)
- Buffering-while-paused: observe manually, no code change
