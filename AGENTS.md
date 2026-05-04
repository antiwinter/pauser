# OpenTune UI conventions

When adding or changing TV screens for a **content source**, follow these rules so navigation and code stay easy to grep.

## Draft development (no migrations, no legacy shims)

OpenTune is still **draft / pre-release**. Do **not**:

- Add **backward-compatibility** layers for removed code paths (no dual APIs, no “deprecated but still works” bridges, no version switches to keep old behavior alive).
- Implement **data or navigation migrations** for end users (no “upgrade from v0.1 nav keys to v0.2”, no Room destructive-migration workarounds solely to preserve old installs).

When something changes, **update call sites and schema directly** and delete the old approach. If that breaks an unreleased install, that is acceptable at this stage.

## Providers (catalog + playback + server config)

- **Registry:** [`OpenTuneProviderRegistry`](app/src/main/java/com/opentune/app/providers/OpenTuneProviderRegistry.kt) on [`OpenTuneApplication`](app/src/main/java/com/opentune/app/OpenTuneApplication.kt) maps **`OpenTuneProviderIds`** (`HTTP_LIBRARY`, `FILE_SHARE`) to **`CatalogBindingPlugin`** + **`PlaybackPreparer`**. Register new backends there only.
- **Implementations** live under **`app/.../providers/emby`** and **`app/.../providers/smb`** (catalog factories, playback preparers, HTTP/SMB specifics). **Do not** put protocol types or `Emby*` / `Smb*` **Kotlin identifiers** under **`ui/catalog`** or **`ui/home`**.
- **Server add/edit UI** is neutral: [`ServerAddRoute`](app/src/main/java/com/opentune/app/ui/config/ServerAddRoute.kt) / [`ServerEditRoute`](app/src/main/java/com/opentune/app/ui/config/ServerEditRoute.kt) under **`ui/config`**, driven by [`ServerFieldSpec`](provider-api/src/main/java/com/opentune/provider/ServerFieldSpec.kt) from **`:provider-api`** via [`EmbyServerFields`](emby-api/src/main/java/com/opentune/emby/api/EmbyServerFields.kt) / [`SmbServerFields`](smb/src/main/java/com/opentune/smb/SmbServerFields.kt). Field copy resolves through **`strings.xml`** + [`ProviderFieldLabels`](app/src/main/java/com/opentune/app/ui/config/ProviderFieldLabels.kt).

## Symmetry (parallel providers)

Keep **parallel** layout per provider under **`providers/{emby,smb}`**: same *roles* (catalog factory, playback preparer), same naming pattern with **source prefix on types** allowed **only** under `providers/*`, not in `ui/catalog` or `ui/home`.

## Shared catalog UI

- Cross-source TV shells (`BrowseScreen`, `DetailScreen`, `SearchScreen`, `MediaEntryComponent`, `BrowseRoute` / `DetailRoute` / `PlayerRoute` / `SearchRoute`) live under **`app/.../ui/catalog`**. They depend on **`MediaCatalogSource`** and **`OpenTuneApplication.providerRegistry`** via [`MediaCatalogBinding`](app/src/main/java/com/opentune/app/ui/catalog/MediaCatalogBinding.kt), not on protocol APIs directly.

## Navigation route strings

**Unified catalog flows** (`provider` segment = registry id, e.g. values in [`OpenTuneProviderIds`](app/src/main/java/com/opentune/app/providers/OpenTuneProviderIds.kt)):

- `browse/{provider}/{sourceId}/{location}` — URL-encoded `location` (opaque to Nav; decode in `CatalogNav` / binding plugins).
- `detail/{provider}/{sourceId}/{itemRef}` — encoded item key.
- `player/{provider}/{sourceId}/{itemRef}/{startMs}`
- `search/{provider}/{sourceId}/{scopeLocation}`

**Server configuration (neutral):**

- `provider_add/{providerId}` — [`Routes.providerAdd`](app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt)
- `provider_edit/{providerId}/{sourceId}` — [`Routes.providerEdit`](app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt)

Encode/decode route segments in **`Routes`** and/or **`CatalogNav`** — avoid scattering magic location strings. Libraries root token: **`CatalogNav.LIBRARIES_ROOT_SEGMENT`**.

## Composables and files

- **Server config UI:** **`ServerAddRoute`**, **`ServerEditRoute`** under **`ui/config`**.
- **Home:** **`HomeRoute`** under **`ui/home`** — uses `OpenTuneProviderIds` + string resources only.
- **Catalog routes:** under **`ui/catalog`** as above.

## `Routes` helpers

- Unified **`BROWSE`**, **`DETAIL`**, **`PLAYER`**, **`SEARCH`**, **`PROVIDER_ADD`**, **`PROVIDER_EDIT`** and matching **`browse()`**, **`detail()`**, **`player()`**, **`search()`**, **`providerAdd()`**, **`providerEdit()`** builders with **`UTF-8`** charset name for encoding.

## Log tags

- Catalog binding plugins may use per-implementation tags (e.g. existing browse/detail log tags) for protocol diagnostics.
- **Unified catalog player** (`PlayerRoute` / `PlaybackPreparer`): use the single tag **`OpenTunePlayer`** from [`OPEN_TUNE_PLAYER_LOG`](player/src/main/java/com/opentune/player/OpenTuneAudioDecodeFallback.kt); optional provider hints in log **messages** when needed.

## Playback hooks

- Implement **`OpenTunePlaybackHooks`** from `:playback-api`. HTTP-library: **`EmbyPlaybackHooks`** in `app` (`playback` package). File-share: **`SmbPlaybackHooks`** in `:smb`.
