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
- Change `onOpenDetail: (String) -> Unit` to `onOpenDetail: (String, Int?) -> Unit` (now carries `item.childCount`)
- Add `onOpenPlayer: (String, Long?) -> Unit` parameter
- Update the click `when` block:
  - `Folder, Season` → `onOpenBrowseLocation(item.id)` (unchanged)
  - `Series, Digipak` → `onOpenDetail(item.id, item.childCount)` (Digipak is new, both pass childCount)
  - `Playable, Episode, Other` → `onOpenPlayer(item.id, item.userData?.positionMs)` (was `onOpenDetail`, now direct play)
  - `Image` → `onOpenImageViewer(item.id)` (unchanged)

**File**: [BrowseRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/BrowseRoute.kt)
- Update `onOpenDetail = { raw, childCount -> nav.navigate(Routes.detail(protocol, endpointId, raw, childCount)) }`
- Wire `onOpenPlayer = { raw, startMs -> nav.navigate(Routes.player(protocol, endpointId, raw, startMs ?: 0L)) }`

**File**: [SearchScreen.kt](app/src/main/java/com/opentune/app/ui/catalog/SearchScreen.kt) (line 100-109)
- Change `onOpenDetail: (String) -> Unit` to `onOpenDetail: (String, Int?) -> Unit`
- Add `onOpenPlayer: (String, Long?) -> Unit` parameter
- Update the click `when` block identically:
  - `Folder, Season` → `onOpenBrowse(item.id)` (unchanged)
  - `Series, Digipak` → `onOpenDetail(item.id, item.childCount)`
  - `Playable, Episode, Other` → `onOpenPlayer(item.id, item.userData?.positionMs)`
  - `Image` → `onOpenImageViewer(item.id)` (unchanged)

**File**: [SearchRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/SearchRoute.kt)
- Update `onOpenDetail = { raw, childCount -> nav.navigate(Routes.detail(protocol, endpointId, raw, childCount)) }`
- Wire `onOpenPlayer = { raw, startMs -> nav.navigate(Routes.player(protocol, endpointId, raw, startMs ?: 0L)) }`

### 7. Navigation: Pass full EntryInfo as JSON in detail route

**File**: [OpenTuneNavHost.kt](app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt)
- Update `Routes.DETAIL` to `detail/{provider}/{endpointId}/{itemRef}/{infoJson}`
- Update `Routes.detail(protocol, endpointId, itemRef, infoJson: String)` builder — encode EntryInfo as JSON
- Add `infoJson` nav argument (StringType) to the DETAIL composable block
- Decode EntryInfo from JSON and pass to `DetailRoute`
- `NavCommand.Detail` wiring: pass `null`/empty for infoJson (debug path)

**File**: [Routes.kt](app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt) (same file)
- Add helper: `fun EntryInfo.toJson(): String` using `kotlinx.serialization.json.Json`
- `infoJson` is URL-encoded in the route builder

### 8. DetailRoute: Accept EntryInfo, pass to DetailScreen

**File**: [DetailRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/DetailRoute.kt)
- Accept `initialInfo: EntryInfo?` from route params (decoded from JSON)
- Remove the `!d.isMedia` child-fetch branch entirely — children loading moves into DetailScreen
- Pass `initialInfo` to `DetailScreen` for immediate rendering (cover, childCount, title, rating)
- DetailRoute handles: detail fetch (EntryDetail from getDetail), favorite toggle, back, player nav

### 9. DetailScreen: Layout from EntryInfo, fetch detail + children internally

**File**: [DetailScreen.kt](app/src/main/java/com/opentune/app/ui/catalog/DetailScreen.kt)
- Add `initialInfo: EntryInfo?` parameter — drives layout decision and immediate rendering
- Internal state: `detail`, `seasons` (Series), `children` (Digipak), `loading`, `error`
- LaunchedEffect: fetch `getDetail(itemRef)` + `listEntry(itemRef, ...)` based on `initialInfo.type`:
  - Series: load seasons (limit 500), then episodes on season select (existing logic moved here)
  - Digipak `childCount > 1`: load all children flat
  - Digipak `childCount <= 1`: load single child EntryInfo for play button
- Rendering:
  - If `initialInfo` available: render backdrop/title/overview immediately while detail loads
  - Series: season selector + episode row + pagination (existing behavior)
  - Digipak `childCount > 1`: child thumbnail row, click → play that child
  - Digipak `childCount <= 1`: Play/Resume button → play single child

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
| [DetailRoute.kt](app/src/main/java/com/opentune/app/ui/catalog/DetailRoute.kt) | Thin wrapper: accept childCount, pass to DetailScreen |
| [DetailScreen.kt](app/src/main/java/com/opentune/app/ui/catalog/DetailScreen.kt) | Layout from childCount, fetch detail+children internally |

## Verification

1. Build: `./gradlew assembleDebug` — should compile without errors
2. Browse: clicking Folder item → navigates to detail (not browse deeper)
3. Browse: clicking Playable/Episode/Other → goes to player directly
4. Digipak detail with single child → shows Play button
5. Digipak detail with multiple children → shows thumbnail row
6. Series detail → unchanged (seasons + episodes + pagination)
