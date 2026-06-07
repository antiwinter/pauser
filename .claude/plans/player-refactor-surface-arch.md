# Player Refactor: Global Overlay/Surface Architecture

## Problem

The current architecture has two parallel player ownership models:
- **`PlaybackEngine`** (in `:player`) creates and owns an ExoPlayer for standalone playback
- **`PlayerController`** (in `:content`) creates and owns a separate ExoPlayer for pre-buffer + overlay

`PlayerSurface` in `:content` duplicates almost everything `TvPlayer` does in `:player`:
- PlaybackControllerBar, InfoOsd, track tracking, position tick, key routing, auto-hide timer

Meanwhile, `TvPlayer`, `PadPlayer`, `PlaybackEngine`, and `OpenTunePlayer` exist as a standalone player stack that's no longer the right model for the surface-overlay paradigm.

## Target Architecture

```
:player
  ├── PlayerController        ← owns the long-lived ExoPlayer instance
  │    create() / setMediaSource(spec) / prepare() / play() / pause()
  │    release() / stop() / seekTo() / position / bufferedPosition
  │    + progress tick (position save)
  │    + bandwidth tick (BandwidthTracker.mbps)
  │    + onCleared → save position, release ExoPlayer
  │
  ├── TvPlayerSurface         ← Composable: renders full TV player UI for an ExoPlayer
  │    OpenTuneTvPlayerView + PlaybackControllerBar + InfoOsd + error + back
  │    Transport keys → show/hide controller bar + InfoOsd
  │    Menu key → open subtitle/audio/speed menu (reuse Menu.kt + controllers)
  │    SubtitleController, AudioController, SpeedController wired in
  │
  ├── PadPlayerSurface        ← Composable: renders full Pad player UI for an ExoPlayer
  │    OpenTunePadPlayerView + PlaybackControllerBar + touch tap + back
  │    SubtitleController, AudioController, SpeedController wired in
  │
  ├── controller/             ← KEEP: SubtitleController, AudioController, SpeedController, Menu
  ├── engine/                 ← KEEP: OpenTuneExoPlayer, toMediaSource, BandwidthTracker,
  │    OpenTuneRenderersFactory, DecoderFallback, TrackInfo
  └── ui/                     ← KEEP: ViewHelpers, PlaybackControllerBar, HostEffects
       (DELETED: tv/TvPlayer.kt, tv/TvPlayerView Composable wrapper,
                 pad/PadPlayer.kt, pad/PadPlayerView Composable wrapper,
                 tv/InfoOsd.kt → rendering merged into surfaces,
                 engine/PlaybackEngine.kt → logic moved to PlayerController)

:content
  ├── PlayerController (thin) ← knows itemRef/client/getPlaybackSpec/debounce
  │    delegates to :player PlayerController for ExoPlayer control
  │    setItem() = resolve spec → player.setMediaSource(spec) + debounce
  │    play()/pause()/release() = delegate
  ├── DetailRoute             ← calls TvPlayerSurface for overlay
  └── BrowseRoute             ← calls TvPlayerSurface for overlay
```

## Feature audit: where does everything go?

### Features from PlaybackEngine → new homes

| Feature | Old location | New location | Notes |
|---|---|---|---|
| Create ExoPlayer | `rememberPlaybackEngine` | `PlayerController` | Uses `OpenTuneExoPlayer.createForBundledSources` |
| Prepare + seek + ready-wait | `rememberPlaybackEngine` LaunchedEffect | `PlayerController.prepare()` | No subtitle sidecar path needed here — SubtitleController handles that |
| Position upsert (10s tick) | `rememberPlaybackEngine` LaunchedEffect | `PlayerController` | Same logic, just moved |
| Progress tick (position save) | `rememberPlaybackEngine` | `PlayerController` | Via `spec.hooks.onProgressTick` |
| Speed restore on prepare | `rememberPlaybackEngine` | `PlayerController.prepare()` | Read from entryStateStore, apply to exo |
| Release (save position + dispose) | `PlaybackEngine.release()` | `PlayerController.release()` + `onCleared()` | Save position + release ExoPlayer |
| MediaSession + keep screen on | `PlaybackHostEffects` | **Stays** — Composable used by both surfaces |
| Bandwidth tick (1s update) | `rememberPlaybackEngine` | `PlayerController` or InfoOsd area | InfoOsd reads `BandwidthTracker.mbps` |
| TrackFallbackEffect (video→audio-only) | `DecoderFallback.kt` | **Keep as Composable** — used by TvPlayerSurface |
| TrackInfo (rememberTrackInfo) | `TrackInfo.kt` | **Keep** — used by surfaces for InfoOsd |
| SubtitleController | `SubtitleController.kt` | **Keep** — adapts to external ExoPlayer |
| AudioController | `AudioController.kt` | **Keep** — adapts to external ExoPlayer |
| SpeedController | `SpeedController.kt` | **Keep** — adapts to external ExoPlayer |
| Menu (PlayerMenuEntry, MenuOverlay) | `Menu.kt` | **Keep** — used by TvPlayerSurface |

### InfoOsd handling

The `InfoOsd` class in `tv/InfoOsd.kt` is a Compose class with `show()/hide()/Osd()`. It's currently created via `rememberInfoOsd()` in `TvPlayer`. After refactor:
- `TvPlayerSurface` will own the `InfoOsd` instance directly (no separate remember function)
- Track info comes from `rememberTrackInfo()` (kept in engine/)
- Bandwidth comes from `BandwidthTracker.mbps`
- The `InfoOsd.Osd()` rendering is placed inside the TvPlayerSurface Box
- Visibility toggles on `controllerState != 0` (same pattern as TvPlayer)
- The InfoOsdBar in PlayerSurface.kt (content module) is deleted — duplicate rendering

## Detailed changes by file

### NEW files

#### `player/src/main/java/com/opentune/player/PlayerController.kt`
- **Owns the long-lived ExoPlayer** — created on init, released on `onCleared()`
- API:
  - `player: ExoPlayer` — direct access for Compose surfaces
  - `setMediaSource(spec: PlaybackSpec, startMs: Long)` — replaces media source, calls prepare()
  - `play()` / `pause()` / `stop()` / `seekTo(pos)` / `position` / `bufferedPosition`
  - `release()` — stop + clear current spec (player stays alive)
- Internal:
  - Progress tick LaunchedEffect-style coroutine (position save every 10s)
  - Bandwidth tick coroutine (update `mbpsFlow` every 1s from `BandwidthTracker.mbps`)
  - On `onCleared()`: save position via hooks, release ExoPlayer
- Does NOT know about `itemRef`, `client`, `getPlaybackSpec()` — pure player control

#### `player/src/main/java/com/opentune/player/ui/tv/TvPlayerSurface.kt`
- **Composable** that takes an `ExoPlayer` + `PlaybackStorageContext` + `onBack`
- Renders:
  - `OpenTuneTvPlayerView` (video surface + DPAD key routing)
  - `PlaybackControllerBar` (bottom, AnimatedVisibility on controllerState/isBuffering)
  - `InfoOsd` (top, visible when controllerState != 0)
  - `CircularProgressIndicator` (center, when buffering)
  - Error display (when exo.playerError)
  - Menu overlay (via `rememberMenuOverlay` + subtitle/audio/speed controllers)
  - Subtitle adjust OSD
- Key handling:
  - `onTransportKey` → `controllerState++` (show bar + InfoOsd)
  - `onBack` → hide controller or call `onBack()`
  - Menu key → open menu
  - DPAD navigation for menu + subtitle adjust mode
- Owns: `rememberSubtitleController`, `rememberAudioController`, `rememberSpeedController`
- Owns: `rememberTrackInfo`, `InfoOsd` instance
- Owns: controllerState, position tick, isPlaying/isPaused/isBuffering state
- Calls: `PlaybackHostEffects(exo)` for IME dismiss, keep screen on, MediaSession
- Calls: `TrackFallbackEffect` for decoder resilience

#### `player/src/main/java/com/opentune/player/ui/pad/PadPlayerSurface.kt`
- Same as TvPlayerSurface but for touch/tap input
- Uses `OpenTunePadPlayerView` (no custom key routing)
- `pointerInput` for tap → toggle controller visibility
- Same controllers, bar, effects

### MODIFIED files

#### `content/ui/.../catalog/PlayerController.kt` → thin wrapper
- **Before**: Creates ExoPlayer, manages full lifecycle, knows item/client/getPlaybackSpec
- **After**:
  - Holds a `PlayerController` (from `:player`) as a field
  - Knows: `_lastItemRef`, `_lastClient`, `_lastStartMs`, debounce logic
  - `setItem(ref, client, startMs)` → resolves spec via `client.getPlaybackSpec()` → calls `player.setMediaSource(spec, startMs)` with debounce
  - `play()` → delegates to player controller
  - `pause()` → delegates
  - `release()` → delegates + clears item state
  - Exposes: `exoPlayerFlow` (derived from player's player + playWhenReady), `isPrepared`, `bufferedDurationMs`, `currentItemRef`, `startMs`
- Debounce logic stays the same: first call immediate, subsequent calls debounce 800ms

#### `content/ui/.../catalog/DetailRoute.kt`
- Remove: InfoOsdBar rendering (merged into TvPlayerSurface)
- Remove: status text overlay (debug — can be added back later)
- Overlay shows `TvPlayerSurface(exoPlayer = playerController.player, onBack = { ... })`
- BackHandler on detail screen: release player before navigating back (keep existing)
- Pre-buffer logic unchanged: `setItem` called on Movie/Series/Digipak entry

#### `content/ui/.../catalog/BrowseRoute.kt`
- Overlay shows `TvPlayerSurface(exoPlayer = playerController.player, onBack = { ... })`
- `onOpenPlayer`: calls `playerController.setItem()` + `playerController.play()` (keep existing fix)

### DELETED files

| File | Reason |
|---|---|
| `player/OpenTunePlayer.kt` | UI switcher — nothing imports it; surfaces now called directly |
| `player/ui/tv/TvPlayer.kt` | Standalone player with own ExoPlayer — replaced by TvPlayerSurface |
| `player/ui/pad/PadPlayer.kt` | Standalone pad player — replaced by PadPlayerSurface |
| `player/engine/PlaybackEngine.kt` | ExoPlayer ownership moved to PlayerController; prepare/progress/release logic moved there |
| `player/ui/tv/InfoOsd.kt` | InfoOsd class rendering merged into TvPlayerSurface/PadPlayerSurface |
| `player/ui/tv/TvPlayerView.kt` (internal Composable `TvPlayerView`) | Only used by old TvPlayer; `OpenTuneTvPlayerView` class stays |
| `player/ui/pad/PadPlayerView.kt` (Composable `PadPlayerView`) | Only used by old PadPlayer; `OpenTunePadPlayerView` class stays |
| `player/ui/HostEffects.kt` | **CHECK** — if PlaybackHostEffects is still needed by surfaces, KEEP. If surfaces call its logic directly, DELETE. |
| `content/ui/.../catalog/PlayerSurface.kt` | Full player UI duplicated in content — replaced by TvPlayerSurface from `:player` |

### KEEP files (unchanged)

| File | Reason |
|---|---|
| `player/PlaybackContracts.kt` | Core contracts: PlaybackSpec, SubtitleTrack, OpenTunePlaybackHooks |
| `player/MediaCodecInfo.kt` | Codec info for UI display |
| `player/PlatformInfo.kt` | Device capability probe |
| `player/engine/OpenTuneExoPlayer.kt` | ExoPlayer creation with custom load control |
| `player/engine/OpenTuneRenderersFactory.kt` | AAC Main→LC patch, custom audio renderer |
| `player/engine/BandwidthTracker.kt` | OkHttp bandwidth interceptor |
| `player/engine/PlaybackSpecExt.kt` | `spec.toMediaSource(context)` |
| `player/engine/DecoderFallback.kt` | TrackFallbackEffect for decoder resilience |
| `player/engine/TrackInfo.kt` | rememberTrackInfo Composable |
| `player/controller/SubtitleController.kt` | Subtitle track selection, sidecar, adjust mode |
| `player/controller/AudioController.kt` | Audio track selection |
| `player/controller/SpeedController.kt` | Speed selection |
| `player/controller/Menu.kt` | PlayerMenuEntry, MenuOverlay, menu rendering |
| `player/controller/SubtitleTrackLabels.kt` | Human-readable track labels |
| `player/PlaybackStorageContext.kt` | CompositionLocal for stores/keys |
| `player/ui/ViewHelpers.kt` | configurePlayerViewDefaults, applySubtitleStyle |
| `player/ui/PlaybackControllerBar.kt` | Progress bar, play/pause, time display |
| `player/ui/tv/TvPlayerView.kt` — `OpenTuneTvPlayerView` class | Android View for TV player surface |
| `player/ui/pad/PadPlayerView.kt` — `OpenTunePadPlayerView` class | Android View for Pad player surface |
| `player/res/layout/opentune_player_view.xml` | Layout for OpenTuneTvPlayerView |

## Execution order

1. **Create `:player` PlayerController** — own ExoPlayer, setMediaSource/prepare/play/pause/release, progress tick, bandwidth tick
2. **Create `TvPlayerSurface`** — extract UI assembly from old `TvPlayer` + add track info + InfoOsd + menu + controllers
3. **Create `PadPlayerSurface`** — extract UI assembly from old `PadPlayer` + controllers
4. **Refactor content PlayerController** — thin wrapper delegating to `:player` PlayerController
5. **Update DetailRoute** — use TvPlayerSurface instead of PlayerSurface
6. **Update BrowseRoute** — use TvPlayerSurface instead of PlayerSurface
7. **Delete dead files** — TvPlayer, PadPlayer, PlaybackEngine, PlayerSurface, InfoOsd, old Composable wrappers
8. **Verify** — build, deploy, test:
   - SMB video playback from browse
   - Detail screen pre-buffer + overlay play
   - InfoOsd shows on UP/DOWN
   - Subtitle/audio/speed menu works
   - Subtitle adjust mode works
   - Back from overlay → pause only (can resume)
   - Back from detail → release before navigate (no stale player on browse)
