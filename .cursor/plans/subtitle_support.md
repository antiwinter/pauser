# Plan: Subtitle Support

## Phase 1 — Provider API (`:provider-api`)

1. Add `SubtitleTrack` data class to `CatalogContracts.kt`:
   - `trackId: String` (stable; Emby = stream index string e.g. `"3"`; SMB = sidecar file path)
   - `label: String`, `language: String?`, `isDefault: Boolean`, `isForced: Boolean`
   - `externalRef: String?` — `null` = embedded (ExoPlayer selects natively via `trackSelectionParameters`); non-null = requires re-prepare (Emby: HTTP URL string; SMB: file path fed to `resolveExternalSubtitle`)

2. Add two fields to `PlaybackSpec` in `PlaybackContracts.kt`:
   - `subtitleTracks: List<SubtitleTrack> = emptyList()` — all known subtitle tracks for this item (embedded + external); populated by `resolvePlayback`
   - `resolveExternalSubtitle: (suspend (subtitleRef: String) -> Uri?)? = null` — called by the player when selecting a non-HTTP external track (SMB only); lambda uses `withStream` internally to download the subtitle file to a local cache and returns a `file://` URI; Emby leaves this null (HTTP URLs are used directly)

   No change to the `resolvePlayback` signature — subtitle data is bundled into `PlaybackSpec`.

---

## Phase 2 — Storage (`:storage`)

3. Add 1 field to `MediaStateEntity` in `ServerEntities.kt`:
   - `selectedSubtitleTrackId: String? = null` (per-item last-chosen track)

4. Bump DB version 5 → 6 (`fallbackToDestructiveMigration` — no migration needed per AGENTS.md).

5. Add `updateSubtitleTrack` to `MediaStateDao` in `Daos.kt`:
   ```sql
   UPDATE media_state SET selectedSubtitleTrackId=:id, updatedAtEpochMs=:now WHERE ...
   ```

6. Add `selectedSubtitleTrackId` to `MediaStateSnapshot`; add `upsertSubtitleTrack` extension on `UserMediaStateStore` in `MediaStateContracts.kt`. Implement in `RoomMediaStateStore`.

7. Add global subtitle offset/scale to `DataStoreAppConfigStore` in `:storage`:
   - `floatPreferencesKey("subtitle_offset_fraction")` — vertical shift; default `0f`
   - `floatPreferencesKey("subtitle_size_scale")` — text size multiplier; default `1f`
   - Expose `suspend fun loadSubtitlePrefs(): SubtitlePrefs` and `suspend fun saveSubtitlePrefs(prefs: SubtitlePrefs)` (simple data class `SubtitlePrefs(offsetFraction: Float, sizeScale: Float)`)

---

## Phase 3 — Emby (`providers/emby`)

8. In `resolvePlayback` in `EmbyProviderInstance`, after fetching `PlaybackInfoResponse`:
   - Filter `source.mediaStreams` where `type == "Subtitle"`
   - Map to `SubtitleTrack`; for `isExternal == true` tracks set `externalRef` to the Emby subtitle stream URL: `{baseUrl}/Videos/{itemId}/Subtitles/{streamIndex}/Stream.{ext}?api_key={accessToken}`; for embedded tracks `externalRef = null`
   - Set `subtitleTracks` on the returned `PlaybackSpec`; leave `resolveExternalSubtitle = null`

---

## Phase 4 — SMB (`providers/smb`)

9. In `resolvePlayback` in `SmbProviderInstance`:
   - Derive parent folder from `itemRef`; call SMB list on that folder
   - Filter entries by subtitle extensions: `.srt`, `.ass`, `.ssa`, `.vtt`, `.sub`
   - Map to `SubtitleTrack` with `externalRef = smbFilePath`; label = filename
   - Set `subtitleTracks` on `PlaybackSpec`
   - Set `resolveExternalSubtitle` lambda: `{ ref -> withStream(ref) { stream -> stream.readAllBytes().writeToCacheFile(sourceId, ref) } }` — returns `Uri.fromFile(cachedFile)` or null on failure. Cache path: `cacheDir/subtitles/<sourceId>/<sha256(ref).take(16)>.<ext>`

---

## Phase 5 — Player view layer (`:player`)

10. In `OpenTuneTvPlayerView`, replace `openExoSettingsMenu()` with a `var settingsMenuCallback: (() -> Unit)? = null`:
    - MENU key calls `settingsMenuCallback` instead of the exo settings popup
    - Add `var isSubtitleAdjustActive: Boolean = false`; when true, intercept DPAD UP/DOWN/LEFT/RIGHT in `dispatchKeyEvent` and forward to `var subtitleAdjustCallback: ((keyCode: Int) -> Unit)? = null` (skips seek and controller show)

11. In `OpenTunePlayerView` (Compose wrapper):
    - New params: `onSettingsMenuRequested: () -> Unit`, `onSubtitleAdjust: (keyCode: Int) -> Unit`, `isSubtitleAdjustActive: Boolean`, `subtitleTranslationYPx: Float`, `subtitleSizeScale: Float`
    - In `factory`/`update`: wire callbacks; apply `view.subtitleView?.translationY = subtitleTranslationYPx`; apply `view.subtitleView?.setFractionalTextSize(0.0533f * subtitleSizeScale, false)`

---

## Phase 6 — Player screen logic (`:player`)

12. New parameters on `OpenTunePlayerScreen`:
    - `initialSubtitleTrackId: String?`
    - `initialSubtitleOffsetFraction: Float`
    - `initialSubtitleSizeScale: Float`
    - (subtitle tracks come from `spec.subtitleTracks`)

13. New state vars: `showSettingsMenu`, `isSubtitleAdjustActive`, `subtitleOffsetFraction`, `subtitleSizeScale`, `activeSubtitleTrackId`. Init from `initial*` params.

14. **Custom settings overlay** (shown when `showSettingsMenu`): full-screen semi-transparent Compose overlay. Three top-level entries navigable by DPAD up/down, CENTER to enter:
    - **Subtitles** → subtitle sub-list (see item 15)
    - **Audio track** → lists `exo.currentTracks` groups of type `TRACK_TYPE_AUDIO`; CENTER selects via `exo.trackSelectionParameters`
    - **Playback speed** → speed picker (0.25×, 0.5×, 0.75×, 1×, 1.25×, 1.5×, 2×); applies via `exo.playbackParameters`

    BACK closes overlay and returns focus to player.

15. **Subtitle sub-list** (within settings overlay):
    - Entries: "Off" + embedded text tracks from `exo.currentTracks` (type `TRACK_TYPE_TEXT`) + external tracks from `spec.subtitleTracks`
    - Selecting embedded track: `exo.trackSelectionParameters` with `setPreferredTextLanguage` / override
    - Selecting external HTTP track (Emby): add `MediaItem.SubtitleConfiguration(Uri.parse(track.externalRef))` and re-prepare at current position
    - Selecting external non-HTTP track (SMB): call `spec.resolveExternalSubtitle(track.trackId)` to get `file://` URI, then re-prepare
    - "Adjust Position & Size" entry at bottom (only when a subtitle is active) → exits settings overlay, enters adjust mode
    - Persist selected track via `upsertSubtitleTrack(key, trackId)` on IO

16. **Adjust mode indicator overlay**: semi-transparent bar showing `↑↓ Move  ←→ Size` hint. DPAD events routed via `subtitleAdjustCallback` in `OpenTuneTvPlayerView`. CENTER or BACK exits adjust mode; offset/scale auto-persist via `appConfigStore.saveSubtitlePrefs(...)` on IO.

---

## Phase 7 — Player Route (`:app`)

17. In `PlayerRoute.kt`:
    - Read saved `selectedSubtitleTrackId` from `mediaStateStore.get(...)`
    - Read `subtitleOffsetFraction` and `subtitleSizeScale` from `appConfigStore.loadSubtitlePrefs()`
    - Pass `initialSubtitleTrackId`, `initialSubtitleOffsetFraction`, `initialSubtitleSizeScale` to `OpenTunePlayerScreen`
    - (Subtitle track list is already in `spec.subtitleTracks` — no extra call needed)

---

## Phase 8 — Strings

18. Add to `app/src/main/res/values/strings.xml`:
    - `subtitle_track_none` — "Off"
    - `player_settings_subtitles` — "Subtitles"
    - `player_settings_audio` — "Audio track"
    - `player_settings_speed` — "Playback speed"
    - `subtitle_adjust_hint` — "↑↓ Move   ←→ Size"
    - `subtitle_adjust_mode_label` — "Adjusting subtitles"

---

## Relevant Files

- `provider-api/src/main/java/com/opentune/provider/CatalogContracts.kt`
- `provider-api/src/main/java/com/opentune/provider/PlaybackContracts.kt`
- `storage/src/main/java/com/opentune/storage/ServerEntities.kt`
- `storage/src/main/java/com/opentune/storage/OpenTuneDatabase.kt`
- `storage/src/main/java/com/opentune/storage/Daos.kt`
- `storage/src/main/java/com/opentune/storage/MediaStateContracts.kt`
- `storage/src/main/java/com/opentune/storage/RoomMediaStateStore.kt`
- `storage/src/main/java/com/opentune/storage/DataStoreAppConfigStore.kt`
- `providers/emby/src/main/java/com/opentune/emby/api/EmbyProviderInstance.kt`
- `providers/smb/src/main/java/com/opentune/smb/SmbProviderInstance.kt`
- `player/src/main/java/com/opentune/player/OpenTuneTvPlayerView.kt`
- `player/src/main/java/com/opentune/player/OpenTunePlayerView.kt`
- `player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt`
- `app/src/main/java/com/opentune/app/ui/catalog/PlayerRoute.kt`
- `app/src/main/res/values/strings.xml`

---

## Decisions & Scope

- No `loadSubtitles` method on `OpenTuneProviderInstance` — subtitle tracks are bundled into `PlaybackSpec.subtitleTracks` by `resolvePlayback`
- No change to the `resolvePlayback` signature
- SMB uses `withStream` + local cache (`cacheDir/subtitles/…`) for subtitle download; no second `resolvePlayback` call
- Emby external subtitles use HTTP URL directly; ExoPlayer's `OkHttpDataSource.Factory` fetches them
- MENU key triggers a fully custom Compose settings overlay replacing the exo settings popup entirely; provides Subtitles, Audio track, Playback speed
- Global subtitle offset/scale stored in `DataStoreAppConfigStore` (two `floatPreferencesKey` entries); per-item selected track stored in `MediaStateEntity`
- Adjust mode uses `SubtitleView.translationY` for offset, `setFractionalTextSize` for size — no custom renderer needed
- DB version 5 → 6 with destructive migration (fine per AGENTS.md — draft/pre-release)

### Excluded (deferred)
- Emby subtitle burn-in via transcoding (`subtitleStreamIndex` in `PlaybackInfoRequest`)
- Global subtitle offset/scale UI in app settings (only accessible via adjust mode in player for now)
