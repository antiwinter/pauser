# Storage / Remote State Ad-Hoc Merge — Analysis & Refactor Plan

## Background

`listEntry()` returns `EntryList(items: List<EntryInfo>)` where each `EntryInfo` carries a
`userData: EntryUserData?` field populated by the **remote provider** (server-side position,
favorite, played status). Separately, `EntryStateEntity` is the **local** Room DB row that
tracks `positionMs`, `isFavorite`, `playbackSpeed`, `selectedSubtitleTrackId`,
`selectedAudioTrackId`, `selectedSeriesProgress`, etc.

There is **no centralized merge layer**. The two data sources are reconciled ad-hoc at
individual call sites.

---

## Current Ad-Hoc Call Sites

### UI layer — reads local `EntryStateEntity` alongside `EntryInfo`

| # | File | Lines | What it reads | Merged with |
|---|------|-------|---------------|-------------|
| 1 | `catalog/detail/DetailRoute.kt` | 75-79 | `entryStateStore.get(stateKey)` → `isFavorite`, `positionMs` | `entryInfo` from VM; passed as params to Movie/Series/DigipakDetailRoute |
| 2 | `catalog/detail/DetailRoute.kt` | 122 | **write** — `upsertFavorite(stateKey, newVal)` | — |
| 3 | `catalog/detail/SeriesDetailRoute.kt` | 92, 141, 179 | **write** — `upsertSeriesProgress(...)` using episode season/index from `EntryInfo` | — |

### UI layer — reads provider-supplied `userData` on `EntryInfo` (no local store)

| # | File | Lines | What it reads |
|---|------|-------|---------------|
| 4 | `catalog/components/MediaEntryComponent.kt` | 83 | `item.userData?.isFavorite` — draws ♥ badge on grid items |
| 5 | `catalog/browse/BrowseScreen.kt` | 152 | `item.userData?.positionMs` — passed to `onOpenPlayer` |
| 6 | `catalog/search/SearchScreen.kt` | 110 | `item.userData?.positionMs` — same `onOpenPlayer` pattern |
| 7 | `catalog/detail/SeriesDetailRoute.kt` | 104, 110, 171, 176 | `episode.userData?.positionMs` — builds `PlaybackSelection` |
| 8 | `catalog/detail/DigipakDetailRoute.kt` | 49, 52, 77, 87 | `child.userData?.positionMs` — picks resume child, builds `PlaybackSelection` |

### Player layer — reads/writes via `PlaybackStorageContext`

| # | File | Lines | What it does |
|---|------|-------|--------------|
| 9  | `player/PlayerController.kt` | 182 | Constructs `PlaybackStorageContext(entryStateStore, entryStateKey, ...)` |
| 10 | `player/engine/PlaybackSession.kt` | 83 | **read** — `?.playbackSpeed` to seed ExoPlayer on prepare |
| 11 | `player/engine/PlaybackSession.kt` | 155 | **write** — `upsertPosition` on `stop()` |
| 12 | `player/engine/PlaybackSession.kt` | 227 | **write** — heartbeat `upsertPosition` every 10 s |
| 13 | `player/controller/SpeedController.kt` | 61 | **write** — `upsertSpeed` on speed change |
| 14 | `player/controller/SubtitleController.kt` | 186-188, 228-230, 258-260, 281-283 | **write** — `upsertSubtitleTrack` on track select |
| 15 | `player/controller/AudioController.kt` | 71-73, 93-95 | **write** — `upsertAudioTrack` on track select |

### Infrastructure / non-UI

| # | File | Lines | What it does |
|---|------|-------|--------------|
| 16 | `providers/EndpointConfigRepository.kt` | 177, 187 | `deleteByEndpoint` on endpoint edit/remove |
| 17 | `server/debug/DebugRoutes.kt` | 294, 303, 316, 330 | debug API: observe, get, upsert subtitle/audio track |

---

## Key Observations

- **No Flow observation in UI.** `observeForEndpoint` / `observeAllFavorites` exist in
  `EntryState.kt` but are only consumed by the debug route. All UI reads are one-shot `get()`.
- **`playbackSpeed`, `selectedSubtitleTrackId`, `selectedAudioTrackId` are never read by the
  UI** — only written by player controllers and read once by `PlaybackSession` (speed only).
- The only UI read of local state that affects visible display is **DetailRoute.kt:75-79**
  (`isFavorite` toggle + resume `positionMs`). Everything else either writes back or reads
  provider-supplied `userData`.
- `CachingEndpointClient` (`EndpointCache.kt:125-142`) caches the raw `EntryList` without
  injecting any local state — a natural hook point that is currently unused for merging.

---

## Future Direction — Centralized Merge in `CachedEndpointClient`

### Goal

Move all ad-hoc merging into a single layer so every `listEntry()` consumer automatically
receives `EntryInfo` whose `userData` reflects both remote and local state. Decouple the
player engine entirely from `EntryStateStore`.

### Design

1. **Merge in `CachingEndpointClient.listEntry()`** — after fetching (or returning cached)
   results, overlay each `EntryInfo.userData` with the corresponding `EntryStateEntity` row
   from `EntryStateStore`. Local values take precedence for fields the app owns locally
   (`positionMs`, `isFavorite`, `playbackSpeed`, `selectedSubtitleTrackId`,
   `selectedAudioTrackId`).

2. **Player calls `CachedEndpointClient` to write state** — instead of writing directly to
   `EntryStateStore`, the player controllers (`SpeedController`, `SubtitleController`,
   `AudioController`, `PlaybackSession`) call a new mutation API on `EndpointClient`
   (or its caching wrapper):
   - `updateEntryPosition(itemId, positionMs)`
   - `updateEntrySpeed(itemId, speed)`
   - `updateEntrySubtitleTrack(itemId, trackId?)`
   - `updateEntryAudioTrack(itemId, trackId?)`
   - `updateFavorite(itemId, isFavorite)`

   `CachingEndpointClient` handles both persisting to `EntryStateStore` **and** invalidating
   (or patching) the relevant in-memory cache entry so the next `listEntry()` or `getEntry()`
   call reflects the update immediately.

3. **`PlaybackStorageContext` removed** — `PlaybackSession`, `SpeedController`,
   `SubtitleController`, `AudioController` receive an `EndpointClient` reference instead of
   a raw `EntryStateStore`. They have no direct dependency on the storage module.

4. **UI simplification** — `DetailRoute.kt` no longer needs its ad-hoc `entryStateStore.get()`
   block (sites 1-2 above); the merged state arrives in `EntryInfo.userData` already. The
   `SeriesDetailRoute` series-progress writes (site 3) become a single
   `client.updateSeriesProgress(...)` call.

### Benefits

- Single source of truth for what consumers see after `listEntry()`.
- Player engine has no compile-time dependency on `:storage` module.
- Cache invalidation / patching lives in one place.
- Easier to swap or mock the storage backend in tests.
