# Plan: Optimize Provider Interface — Items 1, 2, 3

## Context

`optimize-provider-interface.md` lists 4 items. Item 4 (browse methodology) was already implemented. This plan covers items 1–3:

1. **Remove EntryDetail/getDetail, merge into EntryInfo; replace EntryType enum with strings; central filename detection**
2. **Simplify PlaybackSpec — remove title/bitrate/durationMs, add codec info; analyze hooksState vs url/header**
3. **Revise detail screen — 3 specialized overviews, pass EntryInfo directly, resolve progress + pre-buffer**

## Key finding: `getInfo` is NOT needed

**`listEntry` with `DETAIL_FIELDS` covers all `EntryDetail` data.** The Emby API `GET /Users/{userId}/Items` returns the same fields whether used for browsing or detail — the only difference is which fields are requested. `DETAIL_FIELDS` (`MediaSources`, `MediaStreams`, `ProductionYear`, `ExternalUrls`, `ProviderIds`, `RunTimeTicks`, plus the browse fields) is a strict superset of what `DetailScreen.kt` consumes (`backdrop`, `title`, `logo`, `streams`, `rating`, `isMedia`, `overview`).

**`getPlaybackSpec` is self-contained** — it calls `getPlaybackInfo` directly for media sources/streams, and `getItem` for title/duration. It does NOT depend on `getDetail`.

**Navigation already passes `EntryInfo`** via route params (`Routes.detail` encodes `infoJson`). `DetailRoute` receives `initialInfo: EntryInfo?`.

**Approach**: Enrich `listEntry` to return all detail fields. Remove `getDetail` entirely. No separate `getInfo` call.

---

## Phase 1 — Contract Layer (Item 1 + 2 foundation)

### 1.1 Expand `EntryInfo`, delete `EntryDetail`, replace `EntryType` enum with strings

**File: `content/contract/.../CatalogContracts.kt`**

- Replace `enum class EntryType` → `typealias EntryType = String` (keep alias name for import compatibility; routing changes from `EntryType.Folder` to `"Folder"`)
- Expand `EntryInfo` with detail fields:
  - `logo: String? = null`, `backdrop: List<String> = emptyList()`, `bitrate: Int? = null`, `year: Int? = null`, `durationMs: Long? = null`, `width: Int? = null`, `height: Int? = null`, `officialRating: String? = null`, `filename: String? = null`
- Delete `EntryDetail`, `ExternalUrl`, `StreamInfo` data classes
- Change `SearchQuery.excludeTypes: Set<EntryType>` → `Set<String>`

**File: `providers-ts/utils/types.ts`**

- `EntryType` → `string`
- Add new fields to `EntryInfo` interface
- Delete `EntryDetail` interface
- Add `MediaCodecInfo` interface:
  ```ts
  interface MediaCodecInfo { codec: string; bitDepth?: number | null; profile?: string | null; }
  ```

### 1.2 Add `MediaCodecInfo` data class

Add to `CatalogContracts.kt`:
```kotlin
@Serializable
data class MediaCodecInfo(
    val codec: String,
    val bitDepth: Int? = null,
    val profile: String? = null,
)
```

### 1.3 Simplify `PlaybackSpec`

**File: `player/.../PlaybackContracts.kt`**

- **Remove**: `title`, `durationMs`, `bitrate`
- **Add**: `mediaCodecs: List<MediaCodecInfo> = emptyList()`
- **Keep `mimeType`** — used in `PlaybackSpecExt.kt:29` for ExoPlayer `setMimeType`
- **Keep `hooks` separate from `url`/`headers`** — different consumers:
  - `url`/`headers` → ExoPlayer media fetch
  - `hooks` → provider progress reporting (Emby `/Sessions/Playing`)
  - Cannot unify: `hooksState` carries `baseUrl`, `userId`, `accessToken`, `deviceProfile` — none needed by the player

### 1.4 Remove `getDetail`, update `EndpointClient`

**File: `content/contract/.../ProviderContracts.kt`**

- Delete `abstract suspend fun getDetail(itemRef: String): EntryDetail`
- `listEntry` is now the single source for all entry data

### 1.5 Update TypeScript bridge protocol

**File: `providers-ts/utils/types.ts` — `OpenTuneProviderBridge`**

- Delete `getDetail` from bridge interface
- Update `PlaybackSpec`: remove `title`, `durationMs`, `bitrate`; add `mediaCodecs: MediaCodecInfo[]`

---

## Phase 2 — Provider Implementations

### 2.1 Emby Provider: use `DETAIL_FIELDS` in `listEntry`

**File: `providers-ts/providers/emby/client.ts`**

- `listEntry`: replace `BROWSE_FIELDS` with `DETAIL_FIELDS` in the `getItems` call (line 120)
- Delete `getDetail` function entirely
- `toListItem` in `mapper.ts` → rename to `toEntryInfo`, populate all new fields:
  - `logo` from `ImageTags['Logo']`
  - `backdrop` from `BackdropImageTags` (build URLs)
  - `bitrate` from `MediaSources?.[0]?.Bitrate`
  - `year` from `ProductionYear`
  - `durationMs` from `RunTimeTicks` (ticks / 10_000)
  - `officialRating` from `OfficialRating`
  - `width`/`height` from `MediaSources?.[0]?.MediaStreams` (first video stream)
  - `parentId`, `seriesId`, `seasonNumber` from `ParentId`, `SeriesId`, `ParentIndexNumber`
- `getPlaybackSpec`: remove `title`/`durationMs`/`bitrate` from return; add `mediaCodecs`:
  ```ts
  const mediaCodecs = (source.MediaStreams ?? []).map(s => ({
    codec: s.Codec?.toLowerCase() ?? '',
    bitDepth: s.BitDepth ?? null,
  })).filter(s => s.codec);
  ```

### 2.2 Emby `search`: use `DETAIL_FIELDS`

**File: `providers-ts/providers/emby/client.ts`**

- `search`: replace `BROWSE_FIELDS` with `DETAIL_FIELDS` (line 148)

### 2.3 SMB Provider: no getDetail, enriched listEntry

**File: `content/providers/smb/.../SmbClient.kt`**

- `mapEntry()`: return `type = "Folder"` for dirs, `type = "Unknown"` for files. Populate `filename = e.name`. No filename detection.
- Delete `getDetail` override
- `getPlaybackSpec()`: remove `title` from `PlaybackSpec` constructor (path-derived title not needed on spec)

### 2.4 JS Provider (QuickJS bridge)

**File: `content/providers/js/.../JsClient.kt`**

- `parseEntryType()`: passthrough (return raw string)
- `parseListItem()`: populate all new fields from JSON (logo, backdrop, bitrate, year, durationMs, width, height, officialRating, filename, parentId, seriesId, seasonNumber, overview, childCount — read keys that exist)
- Delete `parseDetailModel` and `getDetail` override
- `parsePlaybackSpec()`: remove title/durationMs/bitrate parsing; add `mediaCodecs` array parsing

### 2.5 Emby JS bundle: `listEntry` enriches entries

**File: `providers-ts/providers/emby/index.ts`**

- Delete `getDetail` bridge method
- `listEntry` already calls `toListItem` which now maps all detail fields

### 2.6 CatVod Provider

**File: `providers-ts/providers/catvod/client.ts`**

- Delete `getDetail` override
- `playResultToSpec`: remove title/durationMs/bitrate

**File: `providers-ts/providers/catvod/mapper.ts`**

- `vodDetailToEntryDetail` → return `EntryInfo` with all fields

### 2.7 Central Filename Detection

**Create: `content/contract/.../FilenameDetector.kt`**
```kotlin
object FilenameDetector {
    private val VIDEO_EXTS = setOf(".mkv", ".mp4", ".avi", ".webm", ".m4v", ".mov", ".wmv", ".flv", ".ts", ".m2ts")
    private val AUDIO_EXTS = setOf(".mp3", ".flac", ".aac", ".ogg", ".wav", ".wma", ".m4a", ".opus", ".alac")
    private val IMAGE_EXTS = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".tiff")

    fun detectType(filename: String): String = when {
        filename.endsWithAny(VIDEO_EXTS) -> "Video"
        filename.endsWithAny(AUDIO_EXTS) -> "Audio"
        filename.endsWithAny(IMAGE_EXTS) -> "Image"
        else -> "Unknown"
    }

    private fun String.endsWithAny(exts: Set<String>): Boolean =
        exts.any { lowercase().endsWith(it) }
}
```

**Create: `providers-ts/utils/filename-detector.ts`** — mirror same extension lists.

---

## Phase 3 — Routing Layer (Item 1 routing)

### 3.1 Update BrowseScreen.kt and SearchScreen.kt click handlers

**Files: `BrowseScreen.kt:156-165`, `SearchScreen.kt`**

Replace enum `when` with string comparisons + filename detection fallback:
```kotlin
when (item.type) {
    "Folder", "Season" -> onOpenBrowseLocation(item.id)
    "Movie", "Digipak", "Series" -> onOpenDetail(item)
    "Episode", "Video" -> onOpenPlayer(item.id, item.userData?.positionMs)
    "Image" -> onOpenImageViewer(item.id)
    "Audio" -> onOpenAudioUnsupported(item.id)
    "Unknown" -> {
        when (FilenameDetector.detectType(item.filename ?: item.title)) {
            "Video" -> onOpenPlayer(item.id, item.userData?.positionMs)
            "Audio" -> onOpenAudioUnsupported(item.id)
            "Image" -> onOpenImageViewer(item.id)
            else -> Unit
        }
    }
}
```

- Add `onOpenAudioUnsupported: (String) -> Unit` parameter to both screens
- Import `FilenameDetector`

### 3.2 Add Audio Unsupported Screen

**Create: `AudioUnsupportedScreen.kt`**

Simple composable: "Audio playback not supported" + Back button. Add route in `Routes.kt`.

---

## Phase 4 — Player Layer (Item 2)

### 4.1 Update `PlaybackSpec` construction sites

- **`SmbClient.getPlaybackSpec()`**: remove `title`/`durationMs`/`bitrate` args
- **`JsClient.parsePlaybackSpec()`**: remove those fields; parse `mediaCodecs`
- **Emby `getPlaybackSpec()`** (client.ts): remove title/durationMs/bitrate, add mediaCodecs
- **CatVod**: same

### 4.2 Verify engine is unaffected

`PlaybackEngine.kt` does NOT use `title`/`durationMs`/`bitrate` from `PlaybackSpec`. No changes needed.
`PlaybackSpecExt.kt` `toMediaSource()` uses only `url`, `headers`, `mimeType`. No changes.

### 4.3 Update debug DTOs

**File: `server/.../debug/DebugRoutes.kt`**

- `PlaybackSpecDto`: remove `title`/`durationMs`, add `mediaCodecs`
- Delete `getDetail` route, or change to use `listEntry`-returned EntryInfo

---

## Phase 5 — Detail Screen Refactor (Item 3)

### 5.1 Rewrite `DetailRoute.kt`

**File: `DetailRoute.kt`**

- Delete `detail` state variable (no more `getDetail`)
- Use `initialInfo` as the fully-populated `EntryInfo` (all detail fields now in `EntryInfo`)
- If `initialInfo` is null/empty (edge case), fetch via `listEntry(location, 0, 1)` to get the enriched entry
- Based on `entryInfo.type`, route to the appropriate overview composable:
  ```kotlin
  when (entryInfo.type) {
      "Movie" -> MovieOverviewScreen(entryInfo, ...)
      "Series" -> SeriesOverviewScreen(entryInfo, seasons, episodes, ...)
      "Digipak" -> DigipakOverviewScreen(entryInfo, children, ...)
  }
  ```

- **Series progress resolution**: for Series type, after loading seasons via `listEntry`, check `EntryStateStore.decodeSeriesProgress(positionMs)` to find last-watched season/episode. Auto-select that season. Pre-buffer the next unwatched episode.

- **Pre-buffering for Movies**: `LaunchedEffect` launches coroutine that calls `client.getPlaybackSpec(itemRef, resumeMs)`, creates hidden ExoPlayer via `OpenTuneExoPlayer.createForBundledSources()`, calls `prepare()`, waits for `STATE_READY`. Store prepared player in a `remember` holder. When user presses Play, pass the pre-buffered spec to `PlayerRoute`.

### 5.2 Create common detail components

**Create: `DetailCommonComponents.kt`**

Shared components:
- `DetailBackdrop(imageUrl)` — full-screen backdrop
- `DetailBadges(...)` — played indicator, year, communityRating, resolution label (SD/HD/FHD/QHD/4K/5K/8K), bitDepth, video codec, audio codec, officialRating, genres
- `DetailPlayButtons(onResume, onPlayFromStart, isFavorite, onToggleFavorite)`
- `ResolutionLabel(height: Int?): String` helper

### 5.3 Create 3 overview screens

**Create: `MovieOverviewScreen.kt`**
- Page 1: backdrop1, title/logo, badges row, overview snippet (~4 lines), play buttons
- Page 2: backdrop2, full overview
- Horizontal pager with page indicator dots

**Create: `SeriesOverviewScreen.kt`**
- Page 1: same layout + season selector + episode row
- Page 2: full overview
- Auto-selected season from progress resolution
- Pre-buffer next unwatched episode

**Create: `DigipakOverviewScreen.kt`**
- Page 1: backdrop, title, play buttons, children grid (multi-child)
- Page 2: full overview
- Single-child case: direct play buttons

### 5.4 Delete old `DetailScreen.kt`

**File: `DetailScreen.kt`** — delete. Functionality replaced by three overview screens.

### 5.5 Update `ArtUrlInjector`

**File: `ArtUrlInjector.kt`**

Replace `applyDetail(entryDetail, protocol)` → integrate into existing `apply()` or rename to `applyInfo(entryInfo, protocol)`.

---

## Phase 6 — Cleanup

- Update `SearchQuery.excludeTypes` usage sites (JsClient.kt:206, SmbClient.kt:93) to use strings
- Update `DebugModels.kt` `EntryInfoDto` with new fields
- Update tests (`CatalogNavTest.kt`)
- Verify no remaining `EntryDetail`/`getDetail` references
- Add `People` field to Emby DTO if cast/crew is desired (out of scope for this plan — `People` not currently used anywhere)

---

## Execution Order

```
Phase 1 (contracts) — MUST complete first
    ↓
Phase 2 (providers) — per-provider, can be parallelized
    ↓
Phase 3 (routing) + Phase 4 (player spec) — can be parallelized
    ↓
Phase 5 (detail screen) — depends on Phase 1 + 2
    ↓
Phase 6 (cleanup)
```

## Verification

1. `./gradlew assembleDebug` — clean build
2. Emby/Jellyfin: browse any library → grid shows items, click item → detail screen shows enriched info (logo, backdrop, bitrate, year, codecs, etc.)
3. SMB: browse shows filenames, type detection happens centrally, detail shows filename-based info
4. Detail screen: Movie has page 1/page 2 swiping, Series shows season/episode with progress resume, Digipak shows children
5. Play movie → pre-buffer makes playback start immediately
6. Search works with string-based type filtering
7. Audio items show "unsupported" screen
8. Image viewer still works
