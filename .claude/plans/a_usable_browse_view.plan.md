# Plan: Usable Browse View

## Phase 1: Refactor CatalogContracts.kt

### 1.1 Add Season/Episode/Series to MediaEntryKind

```kotlin
enum class MediaEntryKind {
    Folder,
    Playable,
    Other,
    Series,    // NEW
    Season,    // NEW
    Episode,   // NEW
}
```

### 1.2 Add fields to MediaListItem

```kotlin
data class MediaListItem(
    val id: String,
    val title: String,
    val kind: MediaEntryKind,
    val cover: MediaArt,
    // NEW fields:
    val userData: MediaUserData?,           // playback position, favorite, played
    val originalTitle: String?,
    val genres: List<String>?,
    val communityRating: Float?,
    val studios: List<String>?,
    val childCount: Int?,                   // for auto-nav: Folder/Series with single child
    val etag: String?,
    val indexNumber: Int?,                  // Season/Episode ordering
)
```

New data class:
```kotlin
data class MediaUserData(
    val positionMs: Long,             // converted from playbackPositionTicks (ticks / 10_000)
    val isFavorite: Boolean,
    val played: Boolean,
)
```

### 1.3 Refactor MediaDetailModel

**Remove:** `itemKey` (caller knows the id), `title` (from MediaListItem), `cover`/`poster` (detail uses backdrop+logo), `favoriteSupported`/`isFavorite` (from MediaListItem.userData), `synopsis` (renamed to `overview`)

**Keep + add:** `overview`, `canPlay`, `logo` (new), `backdropImages` (new, list of image URLs), `bitrate` (new), `externalUrls` (new), `productionYear` (new), `providerIds` (new), `mediaStreams` (new), `etag` (new)

Resume position is NOT in MediaDetailModel — it comes from `MediaListItem.userData?.positionMs` (converted from ticks in the provider). The upper layer (DetailRoute) already manages this via `MediaStateKey`.

```kotlin
data class MediaDetailModel(
    val overview: String?,
    val logo: MediaArt,
    val backdropImages: List<String>,       // backdrop image URLs
    val canPlay: Boolean,
    val bitrate: Int?,
    val externalUrls: List<ExternalUrl>,
    val productionYear: Int?,
    val providerIds: Map<String, String>,
    val mediaStreams: List<MediaStreamInfo>,
    val etag: String?,
)

data class ExternalUrl(
    val name: String,
    val url: String,
)

data class MediaStreamInfo(
    val index: Int,
    val type: String,           // "Video", "Audio", "Subtitle"
    val codec: String?,
    val displayTitle: String?,
    val language: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
)
```

### 1.4 No new OpenTuneProviderInstance methods

Seasons and episodes are fetched via the existing `loadBrowsePage(parentId, ...)` call:
- Series ID → returns list of Season items
- Season ID → returns list of Episode items

No `loadSeasons()` or `loadEpisodes()` needed.

## Phase 2: Update Emby Provider

### 2.1 Update DTOs (Items.kt)

Add fields to `BaseItemDto`:
- `originalTitle`, `communityRating`, `genres`, `studios`, `productionYear`
- `indexNumber`, `childCount`, `etag`, `providerIds`, `externalUrls`
- `mediaSources` (for bitrate), `mediaStreams` (for codec/audio info)
- `logoImageTag` / derive logo from ImageTags

### 2.2 Update EmbyApi.kt

Add `@Query("Fields") fields: String?` to `getItems` and `getItem` endpoints.

Define two constants:
```kotlin
object EmbyFieldSets {
    const val BROWSE_FIELDS = "Id,Name,Type,UserData,CommunityRating,ChildCount,ImageTags,BackdropImageTags,IndexNumber,OriginalTitle"
    const val DETAIL_FIELDS = "Id,Name,Type,Overview,ImageTags,BackdropImageTags,RunTimeTicks,UserData,MediaSources,CommunityRating,Genres,Studios,ProductionYear,ProviderIds,ExternalUrls,OriginalTitle,ChildCount,IndexNumber,Etag,MediaStreams"
}
```

### 2.3 Update EmbyProviderInstance

- `toListItem()`: populate all new MediaListItem fields (userData, rating, genres, etc.)
- `toListItem()`: map Emby `Type` → correct MediaEntryKind (Series/Season/Episode)
- `loadDetail()`: return refactored MediaDetailModel with `&Fields=DETAIL_FIELDS`
- `loadBrowsePage()`: pass `&Fields=BROWSE_FIELDS`
- Seasons/episodes: handled by existing `loadBrowsePage` with the series/season ID as parentId

### 2.4 Update EmbyImageUrls — unified image URL builder

Replace type-specific helpers with a single function:
```kotlin
object EmbyImageUrls {
    fun imageUrl(
        baseUrl: String,
        itemId: String,
        imageType: String,    // "Primary", "Logo", "Backdrop", "Thumb", etc.
        tag: String,
        accessToken: String?,
        maxHeight: Int = 220,
    ): String
}
```

Callers pass the appropriate imageType and tag. This handles Primary (covers), Logo, Backdrop, etc. uniformly.

### 2.5 SMB Provider

No changes. All new MediaListItem fields are nullable with defaults; SMB code compiles unchanged.

## Phase 3: Update App Layer

### 3.1 MediaEntryComponent — rating + favorite on cover

- **Bottom-right of cover image**: CommunityRating badge (e.g., "★ 8.5")
- **Bottom-left of cover image**: Heart icon (filled if `userData?.isFavorite == true`)
- These overlays are inside the cover Box, positioned absolutely
- No need to handle Season/Episode kinds here — those are only rendered inside DetailScreen via ThumbEntryComponent

### 3.2 BrowseScreen + BrowseRoute — auto-nav at all levels

- **Auto-nav**: After any `loadBrowsePage` result returns, check each item. If an item is `Playable` (Movie) or `Folder`/`Series` with `childCount == 1`, immediately navigate to its detail page instead of showing it in the grid. Applies at **all levels**, not just the root.
- **Folder click flow**: When user clicks a `Folder` item:
  1. Load items via `loadBrowsePage(folderId, 0, limit)`
  2. If count > 1 → navigate to BrowseRoute for that folder
  3. If count == 1 → load detail of `items[0]` → navigate to DetailRoute for that child
- **Settings button**: Add a gear icon button next to the Search button in BrowseScreen header. Opens a separate Settings route.

### 3.3 DetailRoute — season/episode flow (no new provider methods)

- When detail is loaded, check if the item kind is `Series`. If so:
  - Call `instance.loadBrowsePage(seriesId, 0, 999)` to get seasons
  - If only 1 season, auto-select it and skip the season selector row
  - If multiple seasons, manage `selectedSeasonIndex` state (default: 0)
- When a season is selected:
  - Call `instance.loadBrowsePage(seasonId, 0, 999)` to get episodes
  - Sort episodes by `indexNumber`
  - Pass episodes list to DetailScreen
- **Resume position**: `MediaListItem.userData?.positionMs` provides the position. DetailRoute resolves this via local `MediaStateKey` store (which takes precedence over remote position).

### 3.4 DetailScreen — backdrop + overlay layout

- **Backdrop as full-screen background**: First backdrop image fills the screen. Gradient overlay from bottom for readability.
- **Content overlay** (bottom of screen, on top of backdrop):
  - Logo image (if available from MediaDetailModel.logo)
  - Rating badges row: `[★ 8.5] [HEVC] [DTS]` — derived from communityRating and mediaStreams
  - Action buttons: `[Like/Heart] [Add to list]` (no Play button for Series; for Movie, show Play/Resume)
  - Overview text
- **For Series** (below the content overlay):
  - **Season selector row** (only if >1 season): Horizontal scrollable text row like **Season 1** Season 2 Season 3. D-pad left/right to select, up/down to move to episode row.
  - **Episode thumbnails row** (ThumbEntryComponent): Horizontal scrollable row of small thumbnails. Each shows episode thumbnail + "E1 Title" label below. Ordered by indexNumber. D-pad left/right to select, enter to play.
- **For Movie**: Show Play/Resume buttons based on `MediaListItem.userData?.positionMs` (> 0 → show Resume + Play; 0 → show Play only).

### 3.5 SearchScreen

- Update to display rating + favorite overlays on search results (same as MediaEntryComponent)

### 3.6 New: ThumbEntryComponent

- Horizontal row component for episode thumbnails
- Each cell: small cover image (16:9 aspect), episode number + title label below
- D-pad focusable per cell
- Used only inside DetailScreen for episode display

### 3.7 New: SettingsScreen (separate route)

- Simple settings page with:
  - Title Language toggle: "Local Title" / "Original Title" (en-US labels)
- Accessed via gear button in BrowseScreen header

## Phase 4: titleLang Configuration

### 4.1 Add to DataStoreAppConfigStore

```kotlin
enum class TitleLang { Local, Original }

// New methods:
suspend fun loadTitleLang(): TitleLang
suspend fun saveTitleLang(value: TitleLang)
```

Default = `Local`. When `Original`, show `originalTitle` instead of `title` in all screens.

### 4.2 Apply titleLang in UI

- MediaEntryComponent: resolve title based on config
- DetailScreen: resolve title based on config
- BrowseRoute/DetailRoute/SearchScreen: read config on composition and pass through

## Files Modified

| File | Changes |
|------|---------|
| `provider-api/.../CatalogContracts.kt` | MediaEntryKind+, MediaListItem+, MediaDetailModel refactor, new data classes |
| `providers/emby/.../dto/Items.kt` | New fields in BaseItemDto |
| `providers/emby/.../EmbyApi.kt` | Fields query param, EmbyFieldSets constants |
| `providers/emby/.../EmbyImageUrls.kt` | Unified imageUrl() function |
| `providers/emby/.../EmbyProviderInstance.kt` | All method updates for new fields, use &Fields= |
| `app/.../catalog/BrowseScreen.kt` | Settings button, rating/favorite on covers |
| `app/.../catalog/BrowseRoute.kt` | Auto-nav logic at all levels |
| `app/.../catalog/DetailScreen.kt` | Full redesign: backdrop, logo, rows, ThumbEntryComponent |
| `app/.../catalog/DetailRoute.kt` | Season/episode loading via loadBrowsePage, positionMs from userData |
| `app/.../catalog/MediaEntryComponent.kt` | Rating + favorite overlays on cover |
| `app/.../catalog/SearchScreen.kt` | Rating + favorite display |
| `app/.../catalog/SettingsScreen.kt` | **NEW** — title language settings |
| `app/.../catalog/CoverExtractor.kt` | Minor: compile with new MediaListItem |
| `storage/.../DataStoreAppConfigStore.kt` | titleLang prefs |
