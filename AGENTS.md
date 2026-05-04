# OpenTune UI conventions

When adding or changing TV screens for a **content source**, follow these rules so navigation and code stay easy to grep.

## Draft development (no migrations, no legacy shims)

OpenTune is still **draft / pre-release**. Do **not**:

- Add **backward-compatibility** layers for removed code paths (no dual APIs, no “deprecated but still works” bridges, no version switches to keep old behavior alive).
- Implement **data or navigation migrations** for end users (no “upgrade from v0.1 nav keys to v0.2”, no Room destructive-migration workarounds solely to preserve old installs).

When something changes, **update call sites and schema directly** and delete the old approach. If that breaks an unreleased install, that is acceptable at this stage.

## Providers (catalog + playback + server config)

- **Contracts:** [`:provider-api`](provider-api/src/main/java/com/opentune/provider/) holds **`OpenTuneProvider`**, **`OpenTuneProviderIds`**, **`CatalogBindingPlugin`**, **`MediaCatalogSource`**, media models, **`PlaybackSpec`** (provider-built Media3 `MediaSource`), **`OpenTunePlaybackHooks`**, **`ServerStore`** / **`FavoritesStore`** / **`ProgressStore`**, **`ProviderConfigBackend`**, and **`ServerFieldSpec`**. There is **no** `:playback-api` module (hooks live in `:provider-api`).
- **Registry:** [`OpenTuneProviderRegistry`](app/src/main/java/com/opentune/app/providers/OpenTuneProviderRegistry.kt) on [`OpenTuneApplication`](app/src/main/java/com/opentune/app/OpenTuneApplication.kt) maps **`OpenTuneProviderIds`** (`HTTP_LIBRARY`, `FILE_SHARE`) to **`OpenTuneProvider`** instances. Register new backends there only.
- **Implementations:** HTTP-library code lives in **`:providers:emby`** ([`EmbyProvider`](providers/emby/src/main/java/com/opentune/emby/api/EmbyProvider.kt), catalog binding, playback resolver, config backend, `EmbyRepository`, `EmbyPlaybackHooks`). File-share code lives in **`:providers:smb`** ([`SmbProvider`](providers/smb/src/main/java/com/opentune/smb/SmbProvider.kt), catalog binding, playback resolver, config backend). **Do not** put protocol types or `Emby*` / `Smb*` **Kotlin identifiers** under **`ui/catalog`**, **`ui/home`**, or **`app/.../providers/*`** (only neutral registry + [`ServerConfigRepository`](app/src/main/java/com/opentune/app/providers/ServerConfigRepository.kt) dispatcher remain in `app`).
- **Persistence:** [`:storage`](storage/src/main/java/com/opentune/storage/) holds generic **`ServerEntity`** + **`ServerDao`**, favorites, progress, and [`OpenTuneStorageBindings`](storage/src/main/java/com/opentune/storage/StorageBindings.kt) implementing the `:provider-api` store interfaces. Providers never import `:storage`.
- **Server add/edit UI** is neutral: [`ServerAddRoute`](app/src/main/java/com/opentune/app/ui/config/ServerAddRoute.kt) / [`ServerEditRoute`](app/src/main/java/com/opentune/app/ui/config/ServerEditRoute.kt) under **`ui/config`**, driven by each provider’s **`addFields()`** / **`editFields()`** and **`ServerFieldSpec`**. Field copy resolves through **`strings.xml`** + [`ProviderFieldLabels`](app/src/main/java/com/opentune/app/ui/config/ProviderFieldLabels.kt).

## Symmetry (parallel providers)

Keep **parallel** roles per provider in **`:providers:emby`** vs **`:providers:smb`**: catalog binding plugin, playback resolver (`PlaybackSpec`), config backend, and an umbrella **`OpenTuneProvider`**. **Source-prefixed type names** (`Emby*`, `Smb*`) are allowed **only** inside those modules, not in `ui/catalog` or `ui/home`.

## Shared catalog UI

- Cross-source TV shells (`BrowseScreen`, `DetailScreen`, `SearchScreen`, `MediaEntryComponent`, `BrowseRoute` / `DetailRoute` / `PlayerRoute` / `SearchRoute`) live under **`app/.../ui/catalog`**. They depend on **`:provider-api`** types and **`OpenTuneApplication.providerRegistry`** + **`catalogBindingDeps()`** via [`MediaCatalogBinding`](app/src/main/java/com/opentune/app/ui/catalog/MediaCatalogBinding.kt), not on protocol APIs directly.
- **Player shell:** [`OpenTunePlayerScreen`](player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt) in **`:player`** takes a **`PlaybackSpec`** only (no Emby/SMB branching).

## Navigation route strings

**Unified catalog flows** (`provider` segment = registry id, e.g. values in [`OpenTuneProviderIds`](provider-api/src/main/java/com/opentune/provider/ConfigContracts.kt)):

- `browse/{provider}/{sourceId}/{location}` — URL-encoded `location` (opaque to Nav; decode in `CatalogNav` / binding plugins).
- `detail/{provider}/{sourceId}/{itemRef}` — encoded item key.
- `player/{provider}/{sourceId}/{itemRef}/{startMs}`
- `search/{provider}/{sourceId}/{scopeLocation}`

**Server configuration (neutral):**

- `provider_add/{providerId}` — [`Routes.providerAdd`](app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt)
- `provider_edit/{providerId}/{sourceId}` — [`Routes.providerEdit`](app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt)

Encode/decode route segments in **`Routes`** and/or **`CatalogNav`** — avoid scattering magic location strings. Libraries root token: **`CatalogNav.LIBRARIES_ROOT_SEGMENT`** (same value as [`CatalogRouteTokens.LIBRARIES_ROOT_SEGMENT`](provider-api/src/main/java/com/opentune/provider/CatalogContracts.kt)).

## Composables and files

- **Server config UI:** **`ServerAddRoute`**, **`ServerEditRoute`** under **`ui/config`**.
- **Home:** **`HomeRoute`** under **`ui/home`** — uses `OpenTuneProviderIds` + string resources only; observes **`ServerStore`** via **`OpenTuneApplication.storageBindings`**.
- **Catalog routes:** under **`ui/catalog`** as above.

## `Routes` helpers

- Unified **`BROWSE`**, **`DETAIL`**, **`PLAYER`**, **`SEARCH`**, **`PROVIDER_ADD`**, **`PROVIDER_EDIT`** and matching **`browse()`**, **`detail()`**, **`player()`**, **`search()`**, **`providerAdd()`**, **`providerEdit()`** builders with **`UTF-8`** charset name for encoding.

## Log tags

- Catalog binding plugins may use per-implementation tags (e.g. existing browse/detail log tags) for protocol diagnostics.
- **Unified catalog player:** use the single tag **`OpenTunePlayer`** from [`OPEN_TUNE_PLAYER_LOG`](player/src/main/java/com/opentune/player/OpenTuneAudioDecodeFallback.kt); optional provider hints in log **messages** when needed.

## Playback hooks

- Implement **`OpenTunePlaybackHooks`** from **`:provider-api`**. HTTP-library: **`EmbyPlaybackHooks`** in **`:providers:emby`**. File-share: **`SmbPlaybackHooks`** in **`:providers:smb`**.
