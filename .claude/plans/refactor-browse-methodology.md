# Plan: Refactor Browse Methodology (optimize-provider-interface.md §4)

## Context

Per `optimize-provider-interface.md` §4:
> if browse a view, whose collection type is movies, use `{recursive, type = 'Movie'}` to list only movies

Currently the Emby `listEntry` JS function always calls `getItems` non-recursively, returning the folder hierarchy. For a "Movies" library, this means the user sees folders instead of a flat movie list.

The fix: introduce a `QueryOptions` data class that replaces the scattered sort/filter params on `listEntry`, add `recursive` and `filterByType` to it, and have `BrowseRoute` construct the right options when it detects a movie-collection library root.

SMB provider stays dumb — returns folders and files with filenames only, no change.

---

## Phase 1 — Add `collectionType` to `EntryInfo`

**File:** `content/contract/src/main/java/com/opentune/content/contract/CatalogContracts.kt`

Add to `EntryInfo`:
```kotlin
val collectionType: String? = null,
```

**File:** `content/providers/js/src/main/java/com/opentune/provider/js/JsClient.kt` — `parseListItem`

```kotlin
val collectionType = obj["collectionType"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    ?: obj["CollectionType"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
```

**File:** `app/src/main/assets/providers/emby.js` — `m()` mapping function

Add to the returned object literal in `m()`:
```js
collectionType: e.CollectionType?.toLowerCase() ?? null,
```

This makes library-root items carry `collectionType: "movies"`, `"tvshows"`, etc.

---

## Phase 2 — Introduce `QueryOptions` and update `listEntry`

**File:** `content/contract/src/main/java/com/opentune/content/contract/CatalogContracts.kt`

Define:
```kotlin
data class QueryOptions(
    val sortBy: SortField? = null,
    val sortOrder: SortOrder = SortOrder.Ascending,
    val recursive: Boolean = false,
    val filterByType: String? = null,  // e.g. "Movie", "Episode"
)
```

**File:** `content/contract/src/main/java/com/opentune/content/contract/ProviderContracts.kt`

Replace the current signature:
```kotlin
// OLD
abstract suspend fun listEntry(
    location: String?, startIndex: Int, limit: Int,
    sortBy: SortField? = null, sortOrder: SortOrder = SortOrder.Ascending
): EntryList
```
with:
```kotlin
// NEW
abstract suspend fun listEntry(
    location: String?,
    startIndex: Int,
    limit: Int,
    options: QueryOptions = QueryOptions(),
): EntryList
```

Update all call sites (BrowseRoute, SearchRoute, getTaggedEntries wrappers, etc.) to pass `options = QueryOptions(sortBy=…, sortOrder=…)`.

---

## Phase 3 — JsClient.listEntry — forward `QueryOptions` to JS

**File:** `content/providers/js/src/main/java/com/opentune/provider/js/JsClient.kt`

```kotlin
override suspend fun listEntry(
    location: String?, startIndex: Int, limit: Int, options: QueryOptions
): EntryList {
    val args = buildJsonObject {
        if (location != null) put("location", location) else put("location", JsonNull)
        put("startIndex", startIndex)
        put("limit", limit)
        options.sortBy?.let { put("sortBy", it.name) }
        put("sortOrder", options.sortOrder.name)
        put("recursive", options.recursive)
        options.filterByType?.let { put("filterByType", it) }
    }
    // ... rest unchanged
}
```

---

## Phase 4 — Emby JS bundle: branch on `recursive`/`filterByType`

**File:** `app/src/main/assets/providers/emby.js`

1. `getItems` must accept and forward `Recursive` and `IncludeItemTypes`:
```js
async getItems(e) {
    return i(this.url(`Users/${this.userId}/Items`, {
        ParentId: e.parentId,
        Recursive: e.recursive ?? false,
        IncludeItemTypes: e.includeItemTypes ?? undefined,
        SearchTerm: e.searchTerm,
        SortBy: e.sortBy ?? "SortName",
        StartIndex: e.startIndex,
        Limit: e.limit,
        Fields: e.fields,
    }), this.accessToken)
}
```

2. In `listEntry`, branch on the new query options:
```js
listEntry: async e => {
    const {location, startIndex, limit, recursive, filterByType} = e;
    const result = await n.getItems({
        parentId: location,
        recursive: recursive ?? false,
        includeItemTypes: filterByType ?? undefined,
        startIndex, limit, fields: r,
    });
    return {items: result.Items.map(x => m(x, baseUrl, accessToken)).filter(Boolean),
            totalCount: result.TotalRecordCount};
}
```

This replaces the current hardcoded non-recursive path. Non-movie libraries continue to work because `recursive=false` and `filterByType=undefined` match the old behavior.

---

## Phase 5 — BrowseRoute: detect movie-collection, build QueryOptions

**File:** `content/ui/src/main/java/com/opentune/content/ui/catalog/BrowseRoute.kt`

When a library-root `EntryInfo` has `collectionType == "movies"`, construct:
```kotlin
val queryOptions = when (item.collectionType) {
    "movies" -> QueryOptions(recursive = true, filterByType = "Movie")
    else -> QueryOptions()
}
```

Pass `queryOptions` through the navigation arg (encode as query param on the route URL) or derive it on entry by inspecting the `EntryInfo` of the folder being opened.

**Recommended approach** (no nav-arg changes): when `BrowseRoute` receives `location`, it first calls `client.getEntries(listOf(location))` to fetch the folder's own metadata. If `collectionType == "movies"`, it uses `QueryOptions(recursive=true, filterByType="Movie")` for all subsequent `loadPage` calls. This is a single extra fetch on folder entry and avoids threading query options through the route graph.

`loadPage` lambda becomes:
```kotlin
loadPage = { start, limit ->
    client.listEntry(location, start, limit, queryOptions)
}
```

Navigation into sub-folders from a movie-library view: since a flat movie list has no `Folder` items, this is a non-issue in practice.

---

## Phase 6 — SMB provider: no change

`SmbClient.listEntry` implements the new signature with `options: QueryOptions = QueryOptions()` but ignores `recursive` and `filterByType` — it returns folders and files with filenames only. This is intentional.

---

## Critical Files Summary

| File | Change |
|---|---|
| `content/contract/.../CatalogContracts.kt` | Add `collectionType: String?` to `EntryInfo`; add `QueryOptions` data class |
| `content/contract/.../ProviderContracts.kt` | Replace `sortBy`/`sortOrder` params with `options: QueryOptions` on `listEntry` |
| `content/providers/js/.../JsClient.kt` | Implement new `listEntry` signature; forward `recursive`/`filterByType` to JS args; parse `collectionType` in `parseListItem` |
| `content/providers/smb/.../SmbClient.kt` | Implement new `listEntry` signature (no behavior change) |
| `app/src/main/assets/providers/emby.js` | `m()` maps `CollectionType`; `getItems()` accepts `Recursive`/`IncludeItemTypes`; `listEntry` uses query options |
| `content/ui/.../catalog/BrowseRoute.kt` | Detect `collectionType` on folder entry, build `QueryOptions`, pass to `loadPage` |
| `AGENTS.md` | Plans go in `<project>/.claude/plans/` ✓ (already done) |

---

## Verification

1. `./gradlew assembleDebug` — clean build, all call sites updated
2. On Emby/Jellyfin: navigate to a **Movies** library root → flat list of movies (no folders)
3. On Emby/Jellyfin: navigate to a **TV Shows** library root → folders (series) as before
4. On Emby/Jellyfin: navigate to a **Music** or mixed library → existing folder behavior unchanged
5. SMB share browsing unchanged
6. Search still works (search path unaffected)
