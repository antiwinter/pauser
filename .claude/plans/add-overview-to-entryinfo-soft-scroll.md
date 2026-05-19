# Plan: Add Overview to EntryInfo + Digipak Detail View

## Context

Emby has "Folder" entries that wrap movies/episodes and carry the same metadata (overview, backdrop, rating) as the playable inside them. Currently these map to `EntryType.Folder` and trigger a browse-list view, which is redundant. We want to:

1. Show these Folders as `EntryType.Digipak` and render a detail view with their metadata
2. Add `overview` and `childCount` to `EntryInfo`
3. Remove the auto-nav optimization (folder with 1 playable → skip to playable's detail)
4. All playable types (Playable, Episode, Other) go directly to player — never detail
5. Only Series and Digipak enter detail; rendering decision is based on type + childCount

Series detail view stays unchanged.

## Changes

### 1. Contract: Add `Digipak` type, `overview`, `childCount` fields

**File**: [CatalogContracts.kt](contracts/src/main/java/com/opentune/provider/CatalogContracts.kt)
- Add `Digipak` to `EntryType` enum
- Add `overview: String? = null` to `EntryInfo`
- Add `childCount: Int? = null` to `EntryInfo`

**File**: [types.ts](providers-ts/utils/types.ts)
- Add `'Digipak'` to `EntryType` union
- Add `overview?: string | null` to `EntryInfo`
- Add `childCount?: number | null` to `EntryInfo`

### 2. Emby DTO: Add ChildCount field

**File**: [Items.kt](providers/emby/src/main/java/com/opentune/emby/dto/Items.kt)
- Add `@SerialName("ChildCount") val childCount: Int? = null` to `BaseItemDto`

**File**: [dto.ts](providers-ts/providers/emby/dto.ts)
- Add `ChildCount?: number` field

### 3. Emby Provider: Map "Folder" → Digipak, pass overview + childCount

**File**: [EmbyProviderInstance.kt](providers/emby/src/main/java/com/opentune/emby/EmbyProviderInstance.kt)
- Remove `"Folder"` from `CONTAINER_TYPES` (line 21)
- Add `"Folder" -> EntryType.Digipak` case in `toListItem()` type mapping
- Add `overview = overview, childCount = childCount` to `EntryInfo()` constructor

**File**: [mapper.ts](providers-ts/providers/emby/mapper.ts)
- Remove `'Folder'` from `CONTAINER_TYPES`
- Add `else if (type === 'Folder') entryType = 'Digipak'`
- Add `overview: item.Overview ?? null, childCount: item.ChildCount ?? null` to return object

### 4. Emby browse fields: request ChildCount

**File**: [EmbyApi.kt](providers/emby/src/main/java/com/opentune/emby/EmbyApi.kt)
- Add `ChildCount` to `BROWSE_FIELDS` constant

### 5. Remove auto-nav optimization

**File**: [BrowseRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/BrowseRoute.kt) (line 82-98)
- Replace `onOpenBrowseLocation` with a simple `nav.navigate(Routes.browse(protocol, endpointId, folderId))` — no more single-playable probe

### 6. BrowseScreen + SearchScreen: Update click routing

**File**: [BrowseScreen.kt](app/src/main/java/com/opentune/app/ui/catalog/BrowseScreen.kt) (line 152-160)
- Add `onOpenPlayer: (String, Long?) -> Unit` parameter
- Update the click `when` block:
  - `Folder, Season` → `onOpenBrowseLocation(item.id)` (unchanged)
  - `Series, Digipak` → `onOpenDetail(item.id)` (Digipak is new)
  - `Playable, Episode, Other` → `onOpenPlayer(item.id, item.userData?.positionMs)` (was `onOpenDetail`, now direct play)
  - `Image` → `onOpenImageViewer(item.id)` (unchanged)

**File**: [BrowseRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/BrowseRoute.kt)
- Wire `onOpenPlayer = { raw, startMs -> nav.navigate(Routes.player(protocol, endpointId, raw, startMs ?: 0L)) }`

**File**: [SearchScreen.kt](app/src/main/java/com/opentune/app/ui/catalog/SearchScreen.kt) (line 100-109)
- Add `onOpenPlayer: (String, Long?) -> Unit` parameter
- Update the click `when` block identically:
  - `Folder, Season` → `onOpenBrowse(item.id)` (unchanged)
  - `Series, Digipak` → `onOpenDetail(item.id)` (Digipak is new)
  - `Playable, Episode, Other` → `onOpenPlayer(item.id, item.userData?.positionMs)`
  - `Image` → `onOpenImageViewer(item.id)` (unchanged)

**File**: [SearchRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/SearchRoute.kt)
- Wire `onOpenPlayer = { raw, startMs -> nav.navigate(Routes.player(protocol, endpointId, raw, startMs ?: 0L)) }`

### 7. DetailRoute: Fetch children for all entries

**File**: [DetailRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/DetailRoute.kt)
- Remove the `!d.isMedia` guard — fetch children unconditionally after loading detail
- Pass the children list to DetailScreen
- For single-child case (`totalCount == 1`), keep that child's EntryInfo ready so Play button can target it
- Pass `childCount` from loaded result's `totalCount`

### 8. DetailScreen: Render by type + childCount

**File**: [DetailScreen.kt](app/src/main/java/com/opentune/app/ui/catalog/DetailScreen.kt)
- Add `children: List<EntryInfo>` parameter (flat children for both Series and Digipak)
- Add `childCount: Int? = null` parameter
- Add `singleChild: EntryInfo? = null` parameter (for single-child play)
- Add `onPlayDirect: (EntryInfo) -> Unit` callback (plays the single child)
- Add `onSelectChild: (EntryInfo) -> Unit` callback (for child thumb clicks)
- Rendering logic:
  - **Series** (`childCount > 1`): season selector row + episode thumbnail row + pagination (existing behavior, but driven by `children` list)
  - **Digipak with childCount > 1**: show children as `ThumbEntryComponent` row, clicking → `onSelectChild` → player
  - **Single child (childCount <= 1)**: show Play/Resume button calling `onPlayDirect` with `singleChild`

## File Summary

| File | Change |
|------|--------|
| [CatalogContracts.kt](contracts/src/main/java/com/opentune/provider/CatalogContracts.kt) | Add `Digipak` enum, `overview`, `childCount` fields |
| [types.ts](providers-ts/utils/types.ts) | Add `'Digipak'` type, `overview`, `childCount` fields |
| [Items.kt](providers/emby/src/main/java/com/opentune/emby/dto/Items.kt) | Add `childCount` to `BaseItemDto` |
| [dto.ts](providers-ts/providers/emby/dto.ts) | Add `ChildCount` field |
| [EmbyProviderInstance.kt](providers/emby/src/main/java/com/opentune/emby/EmbyProviderInstance.kt) | Map Folder→Digipak, pass overview + childCount |
| [mapper.ts](providers-ts/providers/emby/mapper.ts) | Map Folder→Digipak, pass overview + childCount |
| [EmbyApi.kt](providers/emby/src/main/java/com/opentune/emby/EmbyApi.kt) | Add ChildCount to BROWSE_FIELDS |
| [BrowseRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/BrowseRoute.kt) | Remove auto-nav, add onOpenPlayer |
| [BrowseScreen.kt](app/src/main/java/com/opentune/app/ui/catalog/BrowseScreen.kt) | Route playable→player, Digipak→detail |
| [SearchRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/SearchRoute.kt) | Add onOpenPlayer wiring |
| [SearchScreen.kt](app/src/main/java/com/opentune/app/ui/catalog/SearchScreen.kt) | Route playable→player, Digipak→detail |
| [DetailRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/DetailRoute.kt) | Fetch children unconditionally, pass child info |
| [DetailScreen.kt](app/src/main/java/com/opentune/app/ui/catalog/DetailScreen.kt) | Render by type + childCount |

## Verification

1. Build: `./gradlew assembleDebug` — should compile without errors
2. Browse: clicking Folder item → navigates to detail (not browse deeper)
3. Browse: clicking Playable/Episode/Other → goes to player directly
4. Digipak detail with single child → shows Play button
5. Digipak detail with multiple children → shows thumbnail row
6. Series detail → unchanged (seasons + episodes + pagination)
