# TV player: ExoPlayer UI, keys, back, speed

## Goal

Customize the Media3 [`PlayerView`](player/src/main/java/com/opentune/player/OpenTunePlayerView.kt) + [`OpenTunePlayerScreen`](player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt) TV experience: lean overlay, D-pad behavior, two-step Back, no duplicate speed/Exit row; correct Emby reporting and local speed persistence.

## Product requirements

- **Transport:** No center row (prev, −15s, play/pause, +15s, next). Use `PlayerView.setShowPreviousButton/RewindButton/FastForwardButton/NextButton(false)`; hide center play/pause via **custom `controller_layout_id`** if the API does not expose a setter (match library layout IDs).
- **Keys:** **DPAD_CENTER** (Confirm) → play/pause. **DPAD_LEFT / RIGHT** → seek back/forward (default ±15s unless aligned with Exo resources). Adjust `setOnKeyListener` so Confirm does not only “show overlay” when you want immediate play/pause.
- **Back:** If controller overlay visible → `hideController()`; else → existing exit (`shutdown` / pop route). Requires `PlayerView` ref + `setControllerVisibilityListener` → Compose state + `BackHandler`.
- **Chrome:** Remove Compose bottom **speed + Exit** row. Use Media3 controller **settings / overflow** for speed and audio (and subtitles). **Exit** via Back only unless you add a minimal extra affordance later.
- **Speed:** Live value = **`exoPlayer.playbackParameters`**. Report **`playbackRate`** to Emby on playing/progress from that value (field is optional in DTO but **still sent** when known). **Persist** speed locally on `onPlaybackParametersChanged`; **apply** persisted rate on player start (Emby + SMB). Pick **global vs per-content** persistence key during implementation.

## Touch points

| Area | Primary files |
|------|----------------|
| `PlayerView` factory, layout, keys, visibility | [`player/.../OpenTunePlayerView.kt`](player/src/main/java/com/opentune/player/OpenTunePlayerView.kt), new `player/src/main/res/layout/` as needed |
| Shell, hooks rate, Back, remove chips | [`player/.../OpenTunePlayerScreen.kt`](player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt) |
| Emby session body | [`app/.../EmbyPlaybackHooks.kt`](app/src/main/java/com/opentune/app/playback/EmbyPlaybackHooks.kt), [`emby-api/.../Sessions.kt`](emby-api/src/main/java/com/opentune/emby/api/dto/Sessions.kt) (types already optional) |
| SMB | [`SmbPlayerRoute`](app/src/main/java/com/opentune/app/ui/smb/SmbPlayerRoute.kt) / [`EmbyPlayerRoute`](app/src/main/java/com/opentune/app/ui/emby/EmbyPlayerRoute.kt) for applying persisted speed when `ExoPlayer` is created |

## Out of scope / constraints

- No second **Menu**-key dialog for speed/tracks unless a custom controller removes Media3’s settings entry (then re-evaluate).
- Follow [`AGENTS.md`](AGENTS.md): Emby vs SMB UI packages, route prefixes, log tags.

## Implementation checklist

- [ ] `OpenTunePlayerView`: hide side transport buttons; custom controller layout if play/pause strip must go; DPAD play/seek; controller visibility listener + retain `PlayerView` for `BackHandler`.
- [ ] `OpenTunePlayerScreen`: delete bottom `Row` (speed + Exit); remove Compose `speed` / `LaunchedEffect(exoPlayer, speed)`; pass **`exoPlayer.playbackParameters.speed`** into hooks for Emby payloads.
- [ ] **Speed persistence:** write on `onPlaybackParametersChanged`; read + `PlaybackParameters` on start in Emby and SMB player paths; decide storage (DataStore vs Room) and key scope (global vs per item/path).
- [ ] `OpenTunePlayerScreen`: **BackHandler** — hide controller first when visible, else exit.
- [ ] **Overlay discovery:** define which keys still show the controller (e.g. Up/Down) so settings/track UI remains reachable.
