# 饭太硬 (FTY) — Provider Feasibility Research

**Date:** 2026-05-20  
**Source URL:** `http://www.饭太硬.net/tv` (punycode: `http://www.xn--sss604efuw.net/tv`)  
**Raw JSON:** [fty_tv.json](./fty_tv.json)

---

## 1. What Is This?

饭太硬 ("FTY", literally "rice is too hard") is a curated **TVBox configuration index** — a well-known Chinese IPTV/VOD aggregator config popular in mainland China. The `/tv` endpoint is the "空壳接口" (skeleton interface): a single JSON blob that TVBox-compatible Android TV apps (FongMi/TV, CatVOD, etc.) consume to discover dozens of media sources.

### Delivery mechanism

The JSON is **not served as `application/json`**. Instead, it is base64-encoded and embedded inside a JPEG image (JFIF, 100×100, ~20 KB). The host server returns `Content-Type: image/jpeg` or `image/x-ms-bmp` to evade scraping/blocking. The TVBox client decodes the embedded payload client-side. This is an intentional obfuscation layer.

---

## 2. JSON Structure

```
{
  "spider":    "<JAR URL>;md5;<hash>",    // optional — TVBox spider JAR
  "wallpaper": "<URL>",
  "sites":     [ <SiteEntry>... ],        // 52 entries
  "lives":     [ <LiveEntry>... ],        //  7 entries
  "rules":     [ <AdFilterRule>... ],
  "logo":      "<URL>",
  "hosts":     [ "<from>=<to>", ... ]     // host rewrites for ad-domain blocking
}
```

### SiteEntry schema

```typescript
{
  key:           string;          // stable ID used in URL params
  name:          string;          // display name (with emoji prefix)
  type:          3;               // always 3 in this config (= "VOD API")
  api:           string;          // see § API Types
  ext?:          string | object; // encrypted payload or cloud-drive config
  searchable?:   0 | 1;
  quickSearch?:  0 | 1;
  changeable?:   0 | 1;
  timeout?:      number;          // seconds
  playerType?:   0 | 1 | 2 | "2";
  indexs?:       0 | 1;
  style?:        { type: string; ratio?: number };
}
```

### LiveEntry schema

```typescript
{
  name:        string;
  type:        0;                 // M3U playlist
  url:         string;           // M3U URL
  playerType:  2;
  epg?:        string;           // EPG template URL
  logo?:       string;           // logo template URL
  ua?:         string;
  timeout?:    number;
}
```

---

## 3. API Types in This Config (52 Sites)

| Type | Count | Description |
|------|-------|-------------|
| `csp_*` (TVBox native Java) | 49 | Closed-source Android JAR spiders bundled with TVBox |
| `drpy2` JS engine | 3 | Open-source JS rule engine (see §4) |

The 3 drpy2 sites are: **虎牙直播** (Huya live), **斗鱼直播** (Douyu live), **兔小贝** (children's content).

---

## 4. drpy2 — The JS Engine

`drpy2.min.js` (~67 KB) is the open-source rule execution engine used by 3 of the 52 sites. It is a significant reference point for a JS provider integration.

### Exported API surface

```
init(rule)         → bootstrap a site rule
home()             → home-page categories
homeVod()          → featured VOD on home page
category(tid, pg, filter, extend) → category listing
detail(id)         → item detail + episodes
play(flag, id, vipFlags) → playback URL resolution
search(qs, quick, pg)    → search
proxy(params)      → local proxy for encrypted streams
sniffer()          → enable auxiliary sniffer
isVideo(url)       → video URL validator
```

### drpy2 site rule format (e.g. `虎牙.js`)

```javascript
var rule = {
  title:      "虎牙直播",
  host:       "https://www.huya.com",
  homeUrl:    "/cache.php?...",
  url:        "/cache.php?...&page=fypage",
  class_name: "娱乐&网游&单机&手游",
  class_url:  "8&1&2&3",
  detailUrl:  "https://m.huya.com/fyid",
  filterable: 1,
  filter:     { ... },         // per-category filter options
  // parsing rules can be CSS/XPath/JSONPath/regex or inline JS
};
```

Each rule is a plain JS object; drpy2 interprets it to fetch & parse pages.

### drpy2 dependencies

drpy2 requires several bundled assets that it imports via `assets://` scheme:
- `cheerio.min.js` (DOM parsing)
- `crypto-js.js`
- `node-rsa.js`, `pako.min.js`, `json5.js`, `jinja.js`
- `gbk.js` (GBK encoding support)

These are expected to be pre-loaded in the host environment (TVBox's embedded V8/QuickJS).

---

## 5. csp_* Providers

The 49 `csp_*` entries (e.g. `csp_WoGGGuard`, `csp_T4Guard`, `csp_AppSxGuard`) are **Java-class spider plugins** compiled into the TVBox APK. They are **not open source** and are not accessible outside the TVBox Android runtime. The names follow the pattern `csp_{ProviderName}Guard`.

Many of these accept an encrypted `ext` field (base64+AES) that contains per-source configuration (API keys, base URLs), so the config itself is intentionally opaque even if you were to reverse the JAR.

Some notable `csp_*` providers:
- `csp_BiliGuard` — Bilibili (video platform, with danmaku)
- `csp_MyDriveGuard` — Personal cloud drive
- `csp_WoGGGuard` — 4K VOD with danmaku
- `csp_T4Guard`, `csp_AppSxGuard` — Multi-source aggregators (encrypted ext)
- `csp_LibvioGuard`, `csp_NewCzGuard` — VOD with instant playback
- `csp_SixVGuard` — Magnetic torrent sources

---

## 6. Content Categories

| Category | Examples |
|----------|---------|
| VOD (movies/TV) | 立播, 奶酪, 厂长, 文采, 原创, 比特, 糯米, 热播 (37 searchable) |
| Cloud-drive backed | 玩偶哥哥, 聚剧剧, 抠搜, 米搜, 盘她, 盘他 |
| Live streaming | 虎牙, 斗鱼, B站直播, YY轮播, 范明明 (via M3U) |
| Sports | 八八看球, 吃瓜看球, 手机看球 |
| Anime | 咕咕, 巴士动漫, 日本动漫 |
| Music / MV | 易听音乐, 明星MV |
| Education | 少儿教育, 小学课堂, 初中课堂, 高中教育 |
| Audiobooks | 有声小说 |
| P2P | 荐片 (P2P) |
| Torrent | 新6V (磁力) |

---

## 7. Feasibility Assessment

### Can we implement FTY as a JS provider?

**Short answer: Partially feasible — the drpy2 subset is implementable, the csp_* majority is not.**

#### What's achievable

| Component | Feasibility | Notes |
|-----------|------------|-------|
| Fetch & decode the `/tv` config | ✅ Trivial | HTTP GET + base64 from JPEG |
| Present the 52 sites as `Folder` entries | ✅ Easy | Static from JSON |
| drpy2 JS sites (3 sites) | ✅ Possible | See §7a |
| IPTV live M3U sources (7 lives) | ✅ Easy | Fetch M3U, map channels to `Playable` entries |
| `csp_*` providers (49 sites) | ❌ Blocked | Java-only, no public API |
| Encrypted `ext` fields | ❌/⚠️ Blocked | AES key is in the TVBox JAR |

#### 7a. drpy2 integration path

The drpy2 engine itself (`drpy2.min.js`) is open-source ES module code. In principle it could run inside our QuickJS sandbox **if** all its `assets://` imports were satisfied. The blockers are:

1. **Asset bundle**: drpy2 expects `cheerio`, `crypto-js`, `pako`, `node-rsa`, `gbk`, `json5`, `jinja` to be pre-loaded under the `assets://js/lib/` virtual scheme. Our current QuickJS engine provides `host.http` and `host.crypto.sha256` only — no DOM/CSS parsing, no GBK codec.
2. **Dynamic import model**: drpy2 uses ES module `import` with custom URL schemes. Our engine executes a single IIFE bundle; it has no ES module resolver.
3. **Proxy / sniffer**: Several drpy2 rules use a local HTTP proxy for stream decryption. This requires a side-channel not present in our architecture.

**Effort to close these gaps**: Large. Would require implementing a module resolver, polyfilling 5–7 libraries, and potentially adding a GBK transcoder to the host API.

#### 7b. IPTV / M3U lives (easy win)

The 7 live sources are plain M3U playlists hosted on public GitHub/CDN URLs. These could be ingested as a flat list of `Playable` channel entries with no dependencies. This is independent of the csp_*/drpy2 question.

---

## 8. Implementation Options (Ranked)

### Option A — IPTV-only JS provider (Low effort, narrow scope)
- Fetch live M3U sources from the `lives` array
- Map channels to `EntryInfo { type: "Playable" }`
- No drpy2, no csp_* needed
- Delivers ~50–100 live Chinese TV channels
- **Effort: 1–2 days**

### Option B — Static catalog + drpy2 for live streams (Medium)
- Expose all 52 sites as `Folder` items at the top level
- For drpy2 sites, polyfill the required asset libraries in our QuickJS environment and run drpy2 natively
- csp_* folders show up but browsing them returns an error/stub
- **Effort: 1–2 weeks** (mainly library polyfilling)

### Option C — Full csp_* compatibility (Very high effort, likely infeasible)
- Would require reimplementing or reverse-engineering 49 closed-source Java spiders
- Legally and technically risky
- **Effort: months; not recommended**

---

## 9. Technical Risks & Concerns

1. **Legal / content legitimacy**: FTY aggregates content from unauthorized streaming sources. The csp_* providers in particular are known to scrape licensed platforms (Bilibili, iQiyi, Youku) without authorization. Implementing full support would facilitate copyright infringement.

2. **Stability**: The config URL and embedded sources change frequently (note `in.bmp` is a content-addressed blob hash). Any hardcoded URL will break.

3. **Config obfuscation**: The JPEG-hiding trick means standard HTTP clients silently get garbage unless they implement the decode step. This is an intentional anti-bot measure and could change at any time.

4. **drpy2 asset dependencies**: The `assets://` import scheme is TVBox-specific. Satisfying it requires bundling ~500 KB of additional JS libraries into our QuickJS environment.

5. **Encrypted ext fields**: The `csp_AppSxGuard` / `csp_T4Guard` entries use AES-encrypted `ext` blobs. The decryption key is embedded in the TVBox JAR — we'd need to reverse it.

---

## 10. Recommendation

**Pursue Option A (IPTV M3U live channels only)** if the goal is "get Chinese live TV into OpenTune with minimal work." The 7 `lives` entries are a clean, public-domain-ish source of M3U playlists that require zero proprietary code.

**Avoid Options B and C** in the short term. The drpy2 polyfilling work is significant, and the csp_* providers are a legal and technical dead-end.

If the team wants a real Chinese VOD provider in the future, a dedicated provider targeting a single open API (e.g. a self-hosted AList instance backed by cloud drive) would be a cleaner and more maintainable approach than trying to emulate the full TVBox ecosystem.

---

## Appendix — Key URLs

| Resource | URL |
|----------|-----|
| FTY homepage | `http://www.xn--sss604efuw.net/` (饭太硬.net) |
| FTY TV config | `http://www.xn--sss604efuw.cc/tv` (饭太硬.cc) |
| FTY backup | `http://fty.xxooo.cf/tv` |
| drpy2 engine | `https://github.com/fantaiying7/EXT` (main/drpy2.min.js) |
| TVBox client | `https://github.com/FongMi/TV` |
| Decoded JSON | [fty_tv.json](./fty_tv.json) |
