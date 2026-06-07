# Pre-buffering on Detail Screen

## Problem
- Movie detail → Play → ExoPlayer resolves PlaybackSpec + prepares → user waits 5-15s before video starts
- Series/Digipak → select episode → same wait per selection
- **Solution:** Start pre-buffering as soon as detail/browse screen shows, keep player alive while on the screen

## Architecture change: Remove PlayerRoute

PlayerRoute is a separate nav destination. We're replacing it with a **player overlay** pattern:

- PlayerController (NavHost-scoped) owns the ExoPlayer
- Any route (BrowseRoute, DetailRoute) renders PlayerSurface as a full-screen overlay when `controller.exoPlayer != null`
- BACK on player → pause + hide overlay, return to underlying screen
- PlayerRoute.kt + ContentRoutes.PLAYER composable → deleted
- Debug API `/navigate route=player` → calls `controller.bufferItem()` directly

## Changes by file

### 1. `PlayerController.kt` — debounce + StateFlow

- Add `bufferItem(ref, client, startMs)` — cancels previous debounce job → delay 800ms → resolve spec + prepare
- Add `playbackState: StateFlow<Int>` via ExoPlayer `Player.Listener`
- `play()` / `pause()` already exist
- `release()` removes listener, cancels debounce job, releases ExoPlayer
- Debounce defaults to 800ms; can pass 0 for "no debounce" (e.g. from Browse direct play)

### 2. `DetailRoute.kt` — pre-buffer + overlay

**Movie:**
- After `loadEntry()` completes → `controller.bufferItem(itemRefDecoded, client, resumeMs)`
- Play buttons always enabled
- Play → `controller.play()` + show PlayerSurface overlay (`embeddedPlayerRef = itemRefDecoded`)
- Back from overlay → `controller.pause()` + hide overlay (player stays alive)
- `DisposableEffect` onDispose → `controller.release()` when leaving detail

**Series:**
- After `loadEpisodes()` → resolve current episode via `entryState.positionMs` + `decodeSeriesProgress()` → `controller.bufferItem()`
- Episode selection → `controller.bufferItem(newEpisode.id, client, newMs)` — debounce handled internally
- Back from overlay → same as Movie (pause, hide, player stays alive)

**Digipak:**
- After `loadDigipakChildren()` → find child with resume position → `controller.bufferItem()`
- Child selection → `controller.bufferItem(newChild.id, client, newMs)`

### 3. `BrowseRoute.kt` — add PlayerSurface overlay

- Add `playerController` parameter
- When `playerController.exoPlayer != null` → render PlayerSurface full-screen
- `onOpenBrowse` player: `controller.bufferItem(ref, client, startMs ?: 0L)`
- Back on player → `controller.pause()` + hide overlay

### 4. `PlayerSurface.kt` — Loading OSD

- `exoPlayer == null` → "Loading…" text (user can wait or press Back)
- `exoPlayer != null` but buffering → centered spinner
- Ready → video with OSD controls
- BackHandler → pause + `onBack()`

### 5. `ContentRoutes.kt` — remove PlayerRoute composable

- Delete the `composable(Routes.PLAYER)` block
- Pass `playerController` to BrowseRoute
- DetailRoute already gets it

### 6. `Routes.kt` — keep PLAYER constant (debug API uses it)

- Keep `Routes.PLAYER` and `Routes.player()` — debug API sends this, NavHost handles it differently now

### 7. `OpenTuneNavHost.kt` — update debug route handler

- `NavCommand.Player` → `controller.bufferItem()` + set state to show player (sharedVm cache, etc.)

## Files to modify

| File | Change |
|------|--------|
| `PlayerController.kt` | Add `bufferItem` with debounce, `playbackState` StateFlow, listener lifecycle |
| `DetailRoute.kt` | Pre-buffer on entry load; debounced episode/child re-buffer; overlay lifecycle |
| `BrowseRoute.kt` | Add PlayerSurface overlay |
| `PlayerRoute.kt` | **DELETE** |
| `ContentRoutes.kt` | Remove PLAYER composable; pass playerController to BrowseRoute |
| `Routes.kt` | Keep PLAYER (debug compat) |
| `OpenTuneNavHost.kt` | Update NavCommand.Player handler to use bufferItem |
| `MovieOverviewScreen.kt` | No change needed |

## Verification

1. Build → install on TV
2. Emby → Movie detail → pre-buffers automatically → Play → instant start
3. Back from player → detail → Play again → instant resume
4. Series detail → episodes load → pre-buffers current → select different episode → debounced re-buffer
5. Browse → select video → player overlay → BACK → back to browse list
6. Debug API → `/navigate route=player` → works via bufferItem
