# APTV — Protocol Research

**Date:** 2026-05-21
**Sources examined:**
- `https://add.aptv.app/` — one-click import URL scheme
- `https://gh.aptv.app/` — CORS proxy for GitHub-hosted M3U files
- `https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u` — sample playlist
- `https://github.com/Kimentanm/aptv` — app README

---

## 1. What Is APTV?

APTV is a **closed-source Apple-platform IPTV player** (iOS, iPadOS, tvOS, macOS, watchOS, visionOS) available on the App Store. It is an M3U/IPTV player with catchup (time-shift) support. It is **not related to CatVod** — it has no VOD, no sites/spider architecture, no 苹果CMS API. It is purely a live TV channel player.

The repository `Kimentanm/aptv` on GitHub is just the **public issue tracker and sample playlist** — the app source is not open.

---

## 2. Protocol: Extended M3U Only

APTV's only subscription format is **M3U**. There is no JSON config, no CatVod-style `sites`/`lives` structure, no API protocol.

APTV adds a small set of proprietary `#EXT-X-*` tags on top of standard M3U:

### Standard M3U tags used

| Tag / Attribute | Meaning |
|----------------|---------|
| `#EXTM3U` | Playlist header |
| `#EXTINF:-1 ...,Name` | Channel entry with metadata |
| `tvg-id` | EPG channel ID |
| `tvg-name` | Channel display name |
| `tvg-logo` | Channel logo URL |
| `group-title` | Channel group/category |
| `x-tvg-url` | EPG XML source URL (on `#EXTM3U` line) |

### APTV-specific M3U extensions

| Tag / Attribute | Meaning |
|----------------|---------|
| `#EXT-X-APP APTV` | Marks this as an APTV-specific playlist |
| `#EXT-X-APTV-TYPE remote` | Tells the app this is a remotely hosted playlist (vs inline) |
| `#EXT-X-SUB-URL <url>` | The canonical remote URL for this playlist — used for auto-refresh |
| `http-user-agent="..."` | Per-channel User-Agent header override |
| `http-referer="..."` | Per-channel Referer header override |
| `http-header="Key=Value"` | Per-channel arbitrary HTTP header |
| `catchup="append"` | Catchup mode: appends time params to the stream URL |
| `catchup-source="?playbackbegin=...&playbackend=..."` | Catchup URL template with `${(b)yyyyMMddHHmmss}` / `${(e)yyyyMMddHHmmss}` tokens |

### `#EXT-X-SUB-URL` and `gh.aptv.app`

The `#EXT-X-SUB-URL` tag points to the canonical remote URL. In the sample playlist this is:

```
#EXT-X-SUB-URL https://gh.aptv.app/https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u
```

`gh.aptv.app` is a **CORS/header proxy** operated by the APTV developer. It fronts GitHub raw URLs to allow the app to fetch them with custom headers and avoid CORS restrictions in the app's HTTP client. It is not a protocol — it is infrastructure.

### `add.aptv.app`

`https://add.aptv.app/` is a **URL scheme redirector**. It responds with HTTP 302 to:

```
aptv://add?url=
```

So `https://add.aptv.app/https://example.com/playlist.m3u` opens APTV and imports `https://example.com/playlist.m3u`. This is a convenience for sharing subscription links — not a protocol.

---

## 3. Channel Content

The sample playlist (`iptv.m3u`) contains **33 channels** in 4 groups:

| Group | Content |
|-------|---------|
| 央视IPV4 | CCTV 1–17, CGTN (multiple languages), CHC channels |
| 卫视IPV4 | ~30 provincial/satellite channels + Phoenix TV |
| 4K8K频道 | 4K/8K versions of major channels |
| 历年春晚 | CCTV Spring Festival Gala recordings 1983–2026 (static VOD links) |

Notable per-channel features used:
- Most channels: `http-user-agent="AptvPlayer-UA"`
- Catchup on CCTV and Phoenix channels using `catchup="append"` + `catchup-source` template
- Some channels: `http-referer` or `http-header` for CDN authentication

---

## 4. Comparison with CatVod Protocol

| Dimension | APTV | CatVod |
|-----------|------|--------|
| Config format | M3U (text) | JSON |
| VOD support | ❌ No | ✅ Yes (sites array) |
| Live TV support | ✅ Yes (all channels) | ✅ Yes (lives array) |
| EPG support | ✅ Via `x-tvg-url` | ❌ Not in protocol |
| Catchup/timeshift | ✅ Via `catchup-source` | ❌ Not in protocol |
| Per-channel HTTP headers | ✅ Via `http-user-agent`, `http-referer`, `http-header` | ❌ Not in protocol |
| Spider/JAR plugins | ❌ | ✅ (type 3) |
| JS spider rules | ❌ | ✅ (type 4/9/10) |
| 苹果CMS HTTP API | ❌ | ✅ (type 0/1/2) |
| Open source client | ❌ (closed) | ✅ (FongMi/TV, GPL-3) |
| Platform | Apple only | Android |

**The two protocols are entirely different and have no overlap.** APTV is a pure M3U IPTV player with catchup extensions. CatVod is a VOD+live aggregation framework with plugin support.

---

## 5. Feasibility for OpenTune

APTV is not a protocol to implement — it is an app. What is relevant for OpenTune is whether APTV's M3U format adds anything beyond standard M3U that we should support.

| Feature | Implementable? | Notes |
|---------|---------------|-------|
| Standard M3U parsing | ✅ Trivial | Already covered by CatVod IPTV handler |
| `http-user-agent` per channel | ✅ Easy | Pass as request header when fetching stream |
| `http-referer` / `http-header` per channel | ✅ Easy | Same — per-channel header map |
| `catchup` / `catchup-source` timeshift | ⚠️ Medium | Requires time-substitution in URL template; player must support seeking into a live stream |
| EPG via `x-tvg-url` | ⚠️ Medium | Fetch + parse XMLTV format; separate concern from playback |
| `#EXT-X-APP` / `#EXT-X-APTV-TYPE` / `#EXT-X-SUB-URL` | ✅ Ignorable | App-specific metadata; not needed for playback |

The per-channel HTTP header attributes (`http-user-agent`, `http-referer`, `http-header`) are the most practically useful additions — many Chinese IPTV streams require specific UA/Referer headers and these are widely used in community M3U files beyond just APTV.

---

## 6. Conclusion

APTV uses **extended M3U only** — not CatVod, not 苹果CMS, not any JSON protocol. The two ecosystems are unrelated. An OpenTune M3U provider that handles standard M3U plus the per-channel HTTP header attributes would be compatible with APTV-format playlists. Catchup/timeshift and EPG are separate features that go beyond the playlist format itself.
