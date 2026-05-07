# Plan: Decompose `OpenTunePlayerScreen.kt` into controllers + menu registry

## Current state (what the code looks like now)

`OpenTunePlayerScreen.kt` is a ~600-line monolith. Inside it:

- **10 local `var` state fields** that span three unrelated feature areas: `menuScreen`, `menuTopIndex`, `subtitleMenuIndex`, `audioMenuIndex`, `speedMenuIndex`, `activeSubtitleTrackId`, `subtitleOffsetFraction`, `subtitleSizeScale`, `isSubtitleAdjustActive`, `currentTracks`
- **~150-line `overlayNavCallback` + `overlaySelectCallback`** inline lambdas that switch over `menuScreen` and directly call `exo.trackSelectionParameters`, re-prepare player, write to store — all mixed in one expression
- **Private helpers declared at file top** (`buildTrackLabel`, `languageDisplayName`, `buildExoTrackLabel`, `buildAudioGroupLabel`, `subtitleMimeType`, `PlayerOverlayMenuItem`, `PlayerSettingsOverlay`, `SubtitleAdjustOverlay`, `SPEED_VALUES`, `SubtitleOption`, `PlayerMenuScreen`) that have no reason to live in the screen file
- **`OpenTunePlayerView`** receives `onDpadKey: ((Int) -> Unit)?` which the screen currently assembles with a `when` over `menuScreen` and `isSubtitleAdjustActive` — already one step better than the previous 5-var pattern, but still screen-owned

The playback lifecycle (startup, seek, progress ticks, `shutdown`, audio fallback, `MediaSession`) is separately sound and stays untouched.

---

## Target architecture

```
OpenTunePlayerScreen  (~80 lines, wires + lifecycle only)
  ├── val stores = PlayerStores(mediaStateStore, appConfigStore)
  ├── val subtitleCtrl = rememberSubtitleController(exo, spec, stores, initialXxx)
  ├── val audioCtrl    = rememberAudioController(exo, stores)
  ├── val speedCtrl    = rememberSpeedController(exo, stores)
  ├── val menu         = rememberPlayerMenu(subtitleCtrl.menuEntry, audioCtrl.menuEntry, speedCtrl.menuEntry)
  │
  ├── OpenTunePlayerView(
  │     onDpadKey = menu.onDpadKey   // null when menu closed; routes nav/select when open
  │     subtitleTranslationYPx = subtitleCtrl.translationYPx
  │     subtitleSizeScale = subtitleCtrl.sizeScale
  │   )
  ├── if (menu.isOpen) PlayerMenu(menu)
  ├── subtitleCtrl.AdjustOsd()       // shown only when subtitle adjust active
  └── BackHandler → menu.handleBack() or subtitleCtrl.handleBack() or shutdown
```

---

## Reactive data flow

```
User selects a menu entry
  └─► entry.onSelect(exo, spec, stores)
        ├─► writes selected ID to store  (upsertSubtitleTrack / upsertSpeed / upsertAudioTrack)
        │     └─► OSD / menu re-reads store → selected-dot / active label updates
        └─► controller's LaunchedEffect(store.selectedId) → applies exo.trackSelectionParameters / PlaybackParameters
```

Store is the single source of truth for selected IDs. No `activeSubtitleTrackId` local var anywhere in the screen.

**Exceptions (not round-tripped through IO):**
- `subtitleOffsetFraction` / `subtitleSizeScale` — high-frequency DPAD; held as local `MutableState` in `SubtitleController`, written to `appConfigStore` only on confirm/exit
- `currentTracks` — live ExoPlayer state; held as `StateFlow<Tracks>` in `SubtitleController` / `AudioController` via exo listener, never persisted

---

## State ownership

| State | Lives in | Persisted to |
|---|---|---|
| Selected subtitle track ID | `MediaStateStore` | DB |
| Subtitle offset fraction | `SubtitleController` `MutableState` | `appConfigStore` on confirm |
| Subtitle size scale | `SubtitleController` `MutableState` | `appConfigStore` on confirm |
| Available tracks | `SubtitleController` / `AudioController` `StateFlow<Tracks>` (exo listener) | — |
| Selected speed | `MediaStateStore` | DB |
| Selected audio track | `MediaStateStore` (new `audioTrackId` field) | DB |

---

## `PlayerMenuEntry` model

```kotlin
data class PlayerMenuEntry(
    val label: @Composable () -> String,
    val children: () -> List<PlayerMenuEntry>,   // empty = leaf
    val isSelected: @Composable () -> Boolean = { false },
    val onSelect: (exo: ExoPlayer, spec: PlaybackSpec, stores: PlayerStores) -> Unit = {},
)
```

`PlayerMenu` / `rememberPlayerMenu` renders this tree generically with DPAD nav and exposes:
- `val isOpen: Boolean`
- `val onDpadKey: ((Int) -> Unit)?`  — `null` when closed; non-null intercepts all DPAD when open
- `fun open()` / `fun handleBack(): Boolean`

---

## Files to create / modify

| File | Action |
|---|---|
| `player/.../menu/PlayerMenu.kt` | **Create** — `PlayerMenuEntry` model, `rememberPlayerMenu`, `PlayerMenu` composable, `PlayerMenuItem` row |
| `player/.../subtitle/SubtitleController.kt` | **Create** — `rememberSubtitleController`; owns `currentTracks` StateFlow, `offsetFraction`, `sizeScale`; builds `menuEntry`; exposes `AdjustOsd()`, `translationYPx`, `sizeScale`, `handleBack()` |
| `player/.../subtitle/SubtitleTrackLabels.kt` | **Create** — move `buildTrackLabel`, `languageDisplayName`, `buildExoTrackLabel`, `subtitleMimeType` from screen file |
| `player/.../audio/AudioController.kt` | **Create** — `rememberAudioController`; owns `currentTracks` StateFlow (audio groups); builds `menuEntry` with Auto + per-group children; `buildAudioGroupLabel` moves here |
| `player/.../speed/SpeedController.kt` | **Create** — `rememberSpeedController`; reads saved speed from store on init; builds `menuEntry` with `SPEED_VALUES` children |
| `player/.../PlayerStores.kt` | **Create** — `data class PlayerStores(val mediaStateStore: UserMediaStateStore, val appConfigStore: DataStoreAppConfigStore?)` |
| `player/.../OpenTunePlayerScreen.kt` | **Gut** — delete all 10 state vars, both overlay lambdas, all private helpers above the function; wire controllers + menu; keep playback lifecycle unchanged |
| `storage/.../MediaStateContracts.kt` (+ `Daos.kt`) | **Modify** — add `audioTrackId: String?` to `MediaStateEntity`, add `upsertAudioTrack` extension; bump DB version |

Packages: `com.opentune.player.menu`, `.subtitle`, `.audio`, `.speed` (sub-packages within `:player` module).

---

## Decisions

- **`currentTracks` is not in the store** — live exo state; each controller that needs it holds its own listener + `MutableState<Tracks>`
- **`onDpadKey` stays on `OpenTunePlayerView`** — already the right shape; `menu.onDpadKey` replaces the screen's current `when` expression
- **No generic plugin interface** — screen constructs 3 named controllers explicitly; extension point not needed yet
- **`SubtitleOption` sealed class deleted** — replaced by `List<PlayerMenuEntry>` produced by `SubtitleController`
- **`PlayerMenuScreen` enum deleted** — menu depth/position state moves into `rememberPlayerMenu`
- **DB migration** — `audioTrackId` column added; `fallbackToDestructiveMigration` already set, version bump sufficient

---

## Verification

```bash
./gradlew :player:compileDebugKotlin :storage:compileDebugKotlin
```

Expected: zero errors. `OpenTunePlayerScreen.kt` ~80 lines. All deleted types (`PlayerMenuScreen`, `SubtitleOption`, `SPEED_VALUES`, overlay composables, track label fns) confirmed absent from the screen file.

