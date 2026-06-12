# Focus Management Plan

## Requirements

1. Auto-select the correct series season and episode pagination page from saved progress.
2. On initial detail load, focus the correct default target:
   - Movie detail: focus the primary Play/Resume button.
   - Series detail: resolve the saved-progress episode and focus that episode entry.
   - Digipak detail: focus the first child entry.
3. Restore focus to the last launched/focused entry when navigating back:
   - Player back to detail should restore the detail entry that launched playback.
   - Detail back to browse/search should restore the browse/search item that launched detail.
4. Example behavior:
   - `Browse_A -> focus/click C -> Detail_C -> progress resolves D -> focus E -> Player_E`
   - Back from player should focus `E` on `Detail_C`.
   - Back from detail should focus `C` on `Browse_A`.

## Design Principles

Separate three concepts that are currently easy to conflate:

1. Progress resolution: persisted playback state decides the initial series season/page/episode.
2. Detail focus target: the detail back-stack entry remembers which child entry should be focused.
3. Parent route focus target: the browse/search back-stack entry remembers which item opened the child route.

Use stable entry ids for focus targets. Avoid index-only APIs except as an internal scroll calculation after the id is resolved against the current list.

## Implementation Plan

### Part 1: Resolve Series Progress Before Loading Episodes

Files:
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/SeriesDetailRoute.kt`
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/DetailViewModel.kt`

Current issue:
- `SeriesDetailRoute` tries to match `pendingEpisodeNumber` inside the already loaded `vmEpisodes` page.
- If saved progress points beyond the first page, the episode is not loaded yet and the route falls back to the first episode.

Plan:
1. Decode saved progress into pending season and episode numbers as today.
2. After seasons load, select the saved-progress season if present, otherwise the first season.
3. Before loading episodes, derive the target page from the saved episode number.
   - Initial practical rule: `page = (episodeNumber - 1) / 50` when `episodeNumber > 0`.
   - Keep the fallback behavior for providers with missing/odd numbering: once the page loads, if no exact match exists, use the first episode on that page.
4. Load episodes for the selected season/page.
5. Once the page is loaded, set the detail child focus target to the resolved episode id.

Important:
- Do not clear the detail focus target merely because the player opened and closed.
- Clear/update it only when the user changes season/page or focuses/selects a different entry.

### Part 2: Generalize Detail Child Focus State

File:
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/DetailViewModel.kt`

Current state:
- Series has `selectedEpisodeId`.
- Digipak has no equivalent selected/focused child state.

Plan:
1. Prefer one general id-based state for detail child focus, for example:

```kotlin
private val _focusedChildEntryId = MutableStateFlow<String?>(null)
val focusedChildEntryId: StateFlow<String?> = _focusedChildEntryId.asStateFlow()

fun setFocusedChildEntryId(id: String?) {
    _focusedChildEntryId.value = id
}
```

2. Replace or bridge `selectedEpisodeId` with this generalized state.
   - If renaming is too wide for one change, keep `selectedEpisodeId` temporarily and add digipak-specific state, but the cleaner end state is one child focus target.
3. Update this state when:
   - series progress resolves an episode
   - user focuses an episode
   - user selects/plays an episode
   - digipak initial child is resolved
   - user focuses/selects a digipak child

### Part 3: Add Id-Based Initial Focus to Detail Rows

File:
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/DetailCommonComponents.kt`

Current issue:
- `EpisodeRow` accepts `initialScrollIndex`, but focus intent is really an entry id.
- The old plan proposed `requestInitialFocus`, but that creates ambiguity between first load and back restoration.

Plan:
1. Change `EpisodeRow` to accept `initialFocusId: String?`.
2. Inside the row:
   - find `targetIndex = episodes.indexOfFirst { it.id == initialFocusId }`
   - scroll to `targetIndex` when present
   - attach a `FocusRequester` to the matching item
   - request focus once the item is present
3. Use the same pattern for `DigipakChildren`.
4. Keep `initialScrollIndex` only if needed internally, not as the public route API.

Notes:
- Key the focus effect by `episodes`, `initialFocusId`, and the resolved target index.
- Guard against missing ids and empty lists.
- Avoid arbitrary delays unless Compose focus proves it is necessary; prefer requesting after composition through `LaunchedEffect` tied to the resolved target.

### Part 4: Wire Series Detail Focus

Files:
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/SeriesDetailRoute.kt`
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/SeriesOverviewScreen.kt`

Plan:
1. Collect `focusedChildEntryId` from `DetailViewModel`.
2. Pass it to `SeriesOverviewScreen` as `initialFocusId`.
3. Pass it from `SeriesOverviewScreen` to `EpisodeRow`.
4. In `focusEpisode` and `selectEpisode`, update `focusedChildEntryId` before preparing/playing.
5. On page or season changes initiated by the user, update/clear focus deliberately:
   - Season change: clear current child target, select/load first page, then resolve first episode after load.
   - Page change: clear current child target, load page, then focus the first episode on that page unless another explicit target exists.

Expected behavior:
- Initial load focuses progress episode `D`.
- If user moves focus to `E`, `focusedChildEntryId` becomes `E`.
- Returning from player focuses `E`, not `D`.

### Part 5: Wire Digipak Detail Focus

Files:
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/DigipakDetailRoute.kt`
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/DigipakOverviewScreen.kt`
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/DetailCommonComponents.kt`

Plan:
1. After children load, if no detail child focus target exists, set it to the first child id.
2. Pass `focusedChildEntryId` through `DigipakOverviewScreen` into `DigipakChildren`.
3. In `focusChild` and `selectChild`, update `focusedChildEntryId` before preparing/playing.

Expected behavior:
- Initial digipak load focuses the first child entry.
- Returning from player focuses the child that launched playback.

### Part 6: Focus Movie Primary Action

Files:
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/MovieDetailRoute.kt`
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/MovieOverviewScreen.kt`
- `content/ui/src/main/java/com/opentune/content/ui/catalog/detail/DetailCommonComponents.kt`

Plan:
1. Add optional initial-focus support to the movie primary action area.
2. The target should be:
   - Resume button when `resumeMs > 0`.
   - Play button otherwise.
3. Keep this movie-specific. Do not model movie button focus as a child entry id.

### Part 7: Restore Browse Focus on Return From Detail

Files:
- `content/ui/src/main/java/com/opentune/content/ui/catalog/browse/BrowseViewModel.kt`
- `content/ui/src/main/java/com/opentune/content/ui/catalog/browse/BrowseRoute.kt`
- `content/ui/src/main/java/com/opentune/content/ui/catalog/browse/BrowseScreen.kt`
- `content/ui/src/main/java/com/opentune/content/ui/catalog/components/MediaEntryComponent.kt`

Plan:
1. Add `lastLaunchedItemId` to `BrowseViewModel`.
2. Set `lastLaunchedItemId` immediately before navigating from browse to:
   - nested browse folder
   - detail
   - image viewer/audio unsupported if those should return to the item too
3. Pass `lastLaunchedItemId` to `BrowseScreen` as `initialFocusId`.
4. In `BrowseScreen`, resolve the id against `items`, scroll the grid to the item, attach a `FocusRequester`, and request focus.
5. Add an optional `modifier` is already available on `MediaEntryComponent`, so the grid can attach the focus requester without changing the component API.

Expected behavior:
- Returning from `Detail_C` to `Browse_A` focuses `C`.

### Part 8: Restore Search Focus on Return From Detail

Files:
- `content/ui/src/main/java/com/opentune/content/ui/catalog/search/SearchRoute.kt`
- `content/ui/src/main/java/com/opentune/content/ui/catalog/search/SearchScreen.kt`
- Optional new file: `content/ui/src/main/java/com/opentune/content/ui/catalog/search/SearchViewModel.kt`

Current issue:
- Search currently stores query/results in route-local `remember` state.
- It may survive while the route remains on the back stack, but a ViewModel would make focus restoration and result preservation explicit.

Preferred plan:
1. Introduce a small `SearchViewModel` holding:
   - query
   - results
   - searching/error state if needed
   - `lastLaunchedItemId`
2. Set `lastLaunchedItemId` before navigating from search to browse/detail/player/image/audio routes.
3. Pass `lastLaunchedItemId` into `SearchScreen` as `initialFocusId`.
4. Use the same id-based grid focus helper/pattern as browse.

Minimal plan:
- If avoiding a new ViewModel for now, keep `lastLaunchedItemId` as route-scoped remembered state in `SearchRoute`, but this is less clean than using a ViewModel.

### Part 9: Extract a Small Reusable Grid Focus Helper if Duplication Grows

Files:
- `content/ui/src/main/java/com/opentune/content/ui/catalog/browse/BrowseScreen.kt`
- `content/ui/src/main/java/com/opentune/content/ui/catalog/search/SearchScreen.kt`

Plan:
1. Start with local focus logic in each screen.
2. If browse and search duplicate more than a few lines, extract a small composable helper or function for:
   - resolving target index by id
   - scrolling the grid
   - returning whether an item should receive the `FocusRequester`
3. Keep the abstraction small and Compose-focused; avoid a broad navigation-focus framework.

## Test Plan

Manual verification is important because this is TV focus behavior.

1. Series saved progress:
   - Save progress to an episode beyond page 1.
   - Open series detail.
   - Verify the correct season/page is selected and the correct episode is focused.
2. Series player return:
   - Open series detail with progress episode `D`.
   - Move focus to episode `E` and play it.
   - Press Back from player.
   - Verify detail focuses `E`.
3. Browse return:
   - From browse, focus/click item `C` into detail.
   - Press Back from detail.
   - Verify browse focuses `C`.
4. Search return:
   - Search, focus/click a result into detail.
   - Press Back from detail.
   - Verify search focuses that result and preserves results/query.
5. Movie detail:
   - Open movie with progress: Resume is focused.
   - Open movie without progress: Play is focused.
6. Digipak detail:
   - Initial load focuses the first child.
   - Play a later child and return from player.
   - Verify that later child is focused.

## Files Likely to Change

1. `DetailViewModel.kt` - generalized detail child focus target and series page selection helpers.
2. `SeriesDetailRoute.kt` - saved progress season/page/episode resolution and focus target updates.
3. `SeriesOverviewScreen.kt` - pass id-based focus target to `EpisodeRow`.
4. `DetailCommonComponents.kt` - id-based `EpisodeRow`, `DigipakChildren`, and movie primary button focus support.
5. `DigipakDetailRoute.kt` - initialize/update digipak child focus target.
6. `DigipakOverviewScreen.kt` - pass digipak focus target.
7. `MovieDetailRoute.kt` / `MovieOverviewScreen.kt` - primary action focus support.
8. `BrowseViewModel.kt` - browse parent focus target.
9. `BrowseRoute.kt` / `BrowseScreen.kt` - set and apply browse focus target.
10. `SearchRoute.kt` / `SearchScreen.kt` - set and apply search focus target.
11. Optional `SearchViewModel.kt` - cleaner search state and focus persistence.
