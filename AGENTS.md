# OpenTune UI conventions

When adding or changing TV screens for a **content source** (Emby vs SMB), follow these rules so navigation and code stay easy to grep.

## Composables and files

- Name: **`{Source}{Screen}Route`** with `Source` in `Emby` or `Smb`, and `Screen` describing the flow (`Browse`, `Detail`, `Libraries`, `Add`, `Edit`, `Player`).
- Place Kotlin under **`app/.../ui/emby`** or **`app/.../ui/smb`** only. Do not add generic `ui/player`-style packages for one source.

## Navigation route strings

- Use prefixed paths: **`emby_*`** for Emby, **`smb_*`** for SMB (e.g. `emby_browse/...`, `smb_player/...`).
- Do not introduce bare segments like `browse/` or `player/` for source-specific flows.

## `Routes` helpers

- Expose `const val EMBY_*` / `SMB_*` and small builders **`emby*()`** / **`smb*()`** that apply the same URL-encoding rules (`UTF-8` charset name) as existing helpers.

## Log tags

- Use **`OpenTune{Source}{Screen}`** (e.g. `OpenTuneEmbyAdd`, `OpenTuneSmbPlayer`). One tag per screen; do not reuse a vague shared tag across screens.

## Playback hooks

- Implement **`OpenTunePlaybackHooks`** from `:playback-api`. Emby: **`EmbyPlaybackHooks`** in `app` (`playback` package). SMB: **`SmbPlaybackHooks`** in `:smb`.
