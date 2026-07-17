# Progressive Entry Emission - Infrastructure Refactor

## Goal
Unify browse/search logic under the "search is browse" principle. Establish clean progressive emission infrastructure where consumers always merge by itemRef (update existing, append new), independent of provider emission strategy.

## Constraints
- No provider changes in this phase; providers continue returning final `EntryList`
- Preserve existing UI behavior (browse, search, loadMore, focus restoration)
- DetailViewModel and LiveRoute already work correctly (they use `.first { it.isComplete }`); focus on BrowseViewModel

## Contract

### Provider side (unchanged)
- Providers return `EntryList` at end of suspend function
- Optional: call `entryEmitter?.emit()` during execution for progressive updates
- Emission semantics undefined (cumulative/delta/replace) — consumer must handle all

### Consumer side (unified)
- Always merge by `itemRef`: existing items updated, new items appended
- No "append mode" flag — merge is the only semantic
- Collection is always active, driven by query parameter changes

## Phase 1: Core merge infrastructure

### Target
`content/ui/src/main/java/com/insomnia/content/ui/catalog/browse/BrowseViewModel.kt`

### Change
1. Add `mergeByRef()` helper:
   ```kotlin
   private fun mergeByRef(existing: List<EntryInfo>, incoming: List<EntryInfo>): List<EntryInfo> {
       val merged = existing.associateBy { it.ref }.toMutableMap()
       incoming.forEach { merged[it.ref] = it }
       return merged.values.toList()
   }
   ```

2. Replace `collectList()` merge logic:
   ```kotlin
   // OLD: _items.value = if (append) _items.value + injectedItems else injectedItems
   // NEW: _items.value = if (append) mergeByRef(_items.value, injectedItems) else injectedItems
   ```

### Acceptance
- Browse works: navigate folders, items display
- LoadMore works: pagination appends without duplicates
- Focus restoration works: return from detail, focus restored
- If provider re-emits same itemRef with updated userData, UI shows updated value (no duplicates)

## Phase 2: Eliminate search branching

### Target
`content/ui/src/main/java/com/insomnia/content/ui/catalog/browse/BrowseViewModel.kt`

### Change
1. Remove `load()`, `applySearch()`, `collectGlobalSearch()` — keep only `collectList()`

2. Rename `collectList()` → `startCollection()`:
   ```kotlin
   private fun startCollection(
       c: CachingEndpointClient,
       location: String?,
       options: QueryOptions,
   ) {
       viewModelScope.launch {
           _loading.value = true
           _error.value = null
           _items.value = emptyList()  // Clear for fresh query
           _totalCount.value = 0
           _currentStartIndex.value = 0
           
           runCatching {
               c.listEntry(location, 0, PAGE_SIZE, options)
                   .collect { emission ->
                       val injected = ArtUrlInjector.apply(emission.items, c.protocol, endpointId!!)
                       _items.value = mergeByRef(_items.value, injected)
                       _totalCount.value = emission.totalCount ?: _items.value.size
                   }
           }.onSuccess {
               _loading.value = false
           }.onFailure { e ->
               _error.value = e.message
               _loading.value = false
               Timber.e(e, "startCollection failed")
           }
       }
   }
   ```

3. Store active query params:
   ```kotlin
   private val _activeLocation = MutableStateFlow<String?>(location)
   private val _activeOptions = MutableStateFlow(QueryOptions())
   private val _currentStartIndex = MutableStateFlow(0)
   ```

4. Rewrite `initialize()` to start collection:
   ```kotlin
   fun initialize(endpointId: String) {
       if (this.endpointId != null) return
       viewModelScope.launch {
           // ... get client ...
           startCollection(c, _activeLocation.value, _activeOptions.value)
       }
   }
   ```

5. Rewrite `applySearch()`:
   ```kotlin
   fun applySearch(term: String, scope: SearchScope) {
       val c = _client.value ?: return
       val newLocation = if (scope == SearchScope.CurrentFolder) _activeLocation.value else null
       val newOptions = QueryOptions(searchTerm = term.trim(), recursive = true)
       
       _activeLocation.value = newLocation
       _activeOptions.value = newOptions
       
       startCollection(c, newLocation, newOptions)
   }
   ```

### Acceptance
- Browse folders works
- Search (current folder) works: shows filtered results from current location
- Search (all) works: shows results from null location with recursive=true
- LoadMore works in all modes
- No `collectGlobalSearch` cross-endpoint search (defer to later if needed)

## Phase 3: Reactive pagination

### Target
`content/ui/src/main/java/com/insomnia/content/ui/catalog/browse/BrowseViewModel.kt`

### Change
1. Make `loadMore()` append to existing results (not replace):
   ```kotlin
   fun loadMore() {
       val c = _client.value ?: return
       if (_loading.value) return
       
       val nextIndex = _currentStartIndex.value + PAGE_SIZE
       _currentStartIndex.value = nextIndex
       
       viewModelScope.launch {
           _loading.value = true
           runCatching {
               c.listEntry(_activeLocation.value, nextIndex, PAGE_SIZE, _activeOptions.value)
                   .collect { emission ->
                       val injected = ArtUrlInjector.apply(emission.items, c.protocol, endpointId!!)
                       _items.value = mergeByRef(_items.value, injected)
                       _totalCount.value = emission.totalCount ?: _items.value.size
                   }
           }.onSuccess {
               _loading.value = false
           }.onFailure { e ->
               Timber.e(e, "loadMore failed")
               _loading.value = false
           }
       }
   }
   ```

### Acceptance
- Scroll to end triggers loadMore
- Items append without duplicates
- totalCount stable across pages

## Phase 4: Targeted entry refresh

### Target
`content/ui/src/main/java/com/insomnia/content/ui/catalog/browse/BrowseViewModel.kt`

### Change
1. Replace `refresh()` with `refreshEntry()`:
   ```kotlin
   fun refreshEntry(itemRef: String) {
       val c = _client.value ?: return
       viewModelScope.launch {
           runCatching {
               c.getEntries(listOf(itemRef))
                   .first { it.isComplete }
                   .items
                   .firstOrNull()
           }.onSuccess { updated ->
               if (updated != null) {
                   _items.value = _items.value.map { 
                       if (it.ref == itemRef) updated else it 
                   }
                   Timber.d("refreshEntry: updated $itemRef")
               }
           }.onFailure { e ->
               Timber.e(e, "refreshEntry($itemRef) failed")
           }
       }
   }
   ```

2. Update `BrowseRoute.kt`:
   ```kotlin
   PlayerStopEffect(playerController) {
       val lastRef = viewModel.lastFocusedItemRef.value
       if (lastRef != null) {
           viewModel.refreshEntry(lastRef)
       }
   }
   ```

3. Remove the `LaunchedEffect(Unit) { viewModel.refresh() }` call — fresh BrowseViewModel already loads via `initialize()`

### Acceptance
- Navigate to browse, play video, exit player
- Only the played video's entry updates (progress bar / favorite when UI supports it)
- Other entries unchanged
- No full page re-fetch

## Phase 5: Cleanup

### Target
- Remove dead code: `load()`, old `refresh()`, `collectGlobalSearch()`, `_lastFocusedItemRef` (if unused after Phase 4)
- Inline single-use helpers if any
- Update comments to reflect "search is browse" principle

### Acceptance
- Code compiles
- No unused imports or variables
- Comments accurate
