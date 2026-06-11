# Focus Management Optimization Plan

## Context
Three requirements:
1. Focus the initially selected entry in detail screens (bypass Play buttons)
2. Restore focus on last selected entry when navigating back to detail screens
3. Restore focus on last selected entry when navigating back to browse/search screens
4. Auto-select correct season and pagination page based on saved progress

## Implementation Plan

### Part 0: Browse/Search focus restoration

**File:** `BrowseViewModel.kt`

Add tracking of last selected item:
```kotlin
private val _lastSelectedItemId = MutableStateFlow<String?>(null)
val lastSelectedItemId: StateFlow<String?> = _lastSelectedItemId.asStateFlow()

fun setLastSelectedItem(id: String) {
    _lastSelectedItemId.value = id
}
```

**File:** `BrowseRoute.kt`

When onOpenDetail/onOpenBrowseLocation is called, track the item ID:
```kotlin
onOpenDetail = { item ->
    viewModel.setLastSelectedItem(item.id)
    sharedVm.cache(item)
    nav.navigate(...)
}
```

**File:** `BrowseScreen.kt`

Add `initialFocusId` parameter and FocusRequester logic similar to EpisodeRow:
```kotlin
@Composable
fun BrowseScreen(
    ...
    initialFocusId: String? = null,  // For focus restoration on back navigation
) {
    val initialFocusRequester = remember { FocusRequester() }
    val initialFocusIndex = items.indexOfFirst { it.id == initialFocusId }
    
    LaunchedEffect(items.size, initialFocusId) {
        if (initialFocusIndex >= 0) {
            gridState.scrollToItem(initialFocusIndex / COLUMNS, initialFocusIndex % COLUMNS)
            initialFocusRequester.requestFocus()
        }
    }
    
    LazyVerticalGrid(...) {
        items(items, key = { it.id }) { item ->
            val isInitialFocus = item.id == initialFocusId
            MediaEntryComponent(
                item = item,
                modifier = if (isInitialFocus) Modifier.focusRequester(initialFocusRequester) else Modifier,
                ...
            )
        }
    }
}
```

Same pattern for SearchViewModel, SearchRoute, SearchScreen.

### Part 1: Calculate and set correct pagination page

### Part 1: Calculate and set correct pagination page

**File:** `SeriesDetailRoute.kt`

When selecting episode based on pendingEpisodeNumber, calculate page:
```kotlin
val episodeIndex = vmEpisodes.indexOfFirst { it.indexNumber == pendingEpisodeNumber }
if (episodeIndex >= 0) {
    val page = episodeIndex / 50
    viewModel.selectEpisodePage(page)
}
```

### Part 2: EpisodeRow with FocusRequester for initial item

**File:** `DetailCommonComponents.kt`

```kotlin
@Composable
fun EpisodeRow(
    episodes: List<EntryInfo>,
    imageLoader: ImageLoader,
    initialScrollIndex: Int = 0,
    requestInitialFocus: Boolean = false,
    onFocusEpisode: ((EntryInfo) -> Unit)? = null,
    onPlayEpisode: (EntryInfo) -> Unit,
) {
    if (episodes.isEmpty()) return
    val listState = rememberLazyListState()
    val initialFocusRequester = remember { FocusRequester() }
    
    LaunchedEffect(episodes.size, initialScrollIndex) {
        if (episodes.isNotEmpty() && initialScrollIndex > 0) {
            listState.scrollToItem(initialScrollIndex.coerceAtMost(episodes.lastIndex))
        }
        if (requestInitialFocus && episodes.isNotEmpty()) {
            kotlinx.coroutines.delay(50)
            initialFocusRequester.requestFocus()
        }
    }
    
    LazyRow(state = listState, ...) {
        items(episodes, key = { it.id }) { episode ->
            val isInitial = episodes.indexOf(episode) == initialScrollIndex.coerceAtMost(episodes.lastIndex)
            ThumbEntryComponent(
                item = episode,
                onClick = { onPlayEpisode(episode) },
                imageLoader = imageLoader,
                modifier = Modifier.width(200.dp)
                    .then(if (isInitial && requestInitialFocus) Modifier.focusRequester(initialFocusRequester) else Modifier),
                onFocus = if (onFocusEpisode != null) {{ onFocusEpisode(episode) }} else null,
            )
        }
    }
}
```

### Part 3: Pass requestInitialFocus from SeriesDetailRoute

**File:** `SeriesDetailRoute.kt` and `SeriesOverviewScreen.kt`

Pass `requestInitialFocus = true` when this is the initial load (not navigation back).

### Part 4: Add selectedChildId to DetailViewModel

**File:** `DetailViewModel.kt`

```kotlin
private val _selectedChildId = MutableStateFlow<String?>(null)
val selectedChildId: StateFlow<String?> = _selectedChildId.asStateFlow()

fun setSelectedChildId(id: String) {
    _selectedChildId.value = id
}
```

### Part 5: Update DigipakDetailRoute and DigipakChildren

Same pattern - track selected child, pass initial scroll index, request initial focus.

## Files to Modify

1. `DetailCommonComponents.kt` - EpisodeRow, DigipakChildren with FocusRequester
2. `SeriesDetailRoute.kt` - Calculate pagination page, pass requestInitialFocus
3. `SeriesOverviewScreen.kt` - Pass requestInitialFocus parameter
4. `DetailViewModel.kt` - Add selectedChildId state
5. `DigipakDetailRoute.kt` - Track selected child, calculate initial index
6. `DigipakOverviewScreen.kt` - Pass initialScrollIndex and requestInitialFocus