---
name: Merge player routes and logs
overview: Single catalog-level player route with no provider-specific behavior at that layer; Emby vs SMB differences resolved via a small source-layer API (playback-api style); one unified player log tag; remove PlayerConfigurator stubs; treat audio-disable retry as potentially common (not SMB-dogmatic).
todos:
  - id: player-session-api
    content: Introduce neutral PreparedPlayback / PlayerSession (or factory) API; symmetric Emby vs Smb preparers under ui/emby and ui/smb returning the same shape for catalog
    status: pending
  - id: thin-catalog-player
    content: Catalog player composable only binds nav args + calls factory + OpenTunePlayerScreen; no Emby/SMB branching for params or player behavior at route level
    status: pending
  - id: audio-disable-common
    content: Refactor audio-off retry toward shared player layer or hooks extension; document Emby parity (no transcodable audio); only keep source-specific bits where proven necessary
    status: pending
  - id: unify-player-log-tag
    content: Remove EmbyPlayerConfigurator + SmbPlayerConfigurator; single LOG_TAG for catalog player path
    status: pending
  - id: agents-player-logging
    content: Adjust AGENTS.md — unified player log tag; source layer owns playback prep; no mirrored PlayerConfigurator
    status: pending
  - id: remove-legacy-routes
    content: Delete EmbyPlayerRoute.kt / SmbPlayerRoute.kt after logic moved; grep cleanup
    status: pending
isProject: true
---

# Unified player route and logging

## Principle: no behavioral fork at the player route

At the **catalog player route** there should be **no meaningful difference** between sources: same composable shape, same parameters (provider + ids + opaque `itemRef` + `startMs`), same call into **`OpenTunePlayerScreen`** + [`OpenTunePlaybackHooks`](playback-api/src/main/java/com/opentune/playback/api/OpenTunePlaybackHooks.kt).

All **param interpretation**, **network/session setup**, and **source-specific playback rules** live in a **source layer**, parallel to how playback side effects live in `EmbyPlaybackHooks` / `SmbPlaybackHooks` today — not in a `when (provider)` that encodes Emby vs SMB *behavior* inside the route file.

### Concrete shape (pick one naming during implementation)

- Add a **neutral** contract (Kotlin `interface` or `fun` returning a data class) such as **`PreparedPlayback`** / **`PlayerSession`**: holds everything the shell needs (e.g. `ExoPlayer`, `OpenTunePlaybackHooks`, `startPositionMs`, `resumeProgressKey`, optional `topBanner` factory, optional **shared** listener attachment points).
- Provide **symmetric factories** under [`ui/emby`](app/src/main/java/com/opentune/app/ui/emby/) and [`ui/smb`](app/src/main/java/com/opentune/app/ui/smb/) (e.g. `EmbyPlaybackSessionFactory` / `SmbPlaybackSessionFactory`, or preparer functions) that take **decoded** nav inputs plus `OpenTuneApplication` / `OpenTuneDatabase` and return the same neutral type.
- [`CatalogPlayerRoute.kt`](app/src/main/java/com/opentune/app/ui/catalog/CatalogPlayerRoute.kt) (or renamed `MediaPlayerRoute`) **only**: decode args if needed, `remember`/`LaunchedEffect` call the right factory via `CatalogProvider`, then render **`PlayerShell`** + **`OpenTunePlayerScreen`**. No SMB-only or Emby-only branches for “how to build the player” beyond dispatching to the factory.

If a new concern is **unclear** as common vs source-specific, **stop and ask** before parking it on one branch.

## Audio-disable / retry is not inherently SMB-only

Today SMB carries an **in-place audio-off + `prepare()`** path for decode failures. Treat that as a **candidate cross-source behavior**: e.g. Emby direct play where **no server-side audio transcode** matches device capability could surface a similar failure mode.

Implementation stance:

1. **Default assumption:** implement **generic** “audio track unusable → disable audio → retry once” in the **`:player`** module (or a tiny shared helper used by `OpenTunePlayerScreen`) so Emby and SMB share one code path where applicable.
2. **Source-specific** only when proven: e.g. Emby might need a **different** retry (re-fetch `PlaybackInfo` with explicit stream indices) — that belongs in **`EmbyPlaybackHooks`** extension or the Emby factory, not a SMB-only `if` in catalog.
3. When unsure whether Emby needs the same retry **now**, note it in the PR and **ask** rather than leaving logic only under SMB.

## Logging

- **One** player-level tag (e.g. `OpenTunePlayer`) for the unified catalog player path; optional **message** suffix with provider when debugging.
- Remove [`EmbyPlayerConfigurator.kt`](app/src/main/java/com/opentune/app/ui/emby/EmbyPlayerConfigurator.kt) and [`SmbPlayerConfigurator.kt`](app/src/main/java/com/opentune/app/ui/smb/SmbPlayerConfigurator.kt) if they only exist for tags.

## AGENTS.md

Update [AGENTS.md](AGENTS.md): unified catalog **player** uses one log tag; **browse/detail/search** keep `OpenTuneEmby*` / `OpenTuneSmb*`; symmetry = **factories / session prep** under `ui/emby` and `ui/smb`, not mirrored `*PlayerRoute.kt` files.

## Removal

- Delete [`EmbyPlayerRoute.kt`](app/src/main/java/com/opentune/app/ui/emby/EmbyPlayerRoute.kt) and [`SmbPlayerRoute.kt`](app/src/main/java/com/opentune/app/ui/smb/SmbPlayerRoute.kt) after their responsibilities move to factories + shared player behavior.

## Verification

- Grep for `EmbyPlayerRoute`, `SmbPlayerRoute`, `PlayerConfigurator`.
- `:app:compileDebugKotlin` with `JAVA_HOME` set.
