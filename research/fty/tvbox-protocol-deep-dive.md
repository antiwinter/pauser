# TVBox Protocol Deep Dive — Feasibility for OpenTune JS Provider

**Date:** 2026-05-20  
**Scope:** Authorship relationships, protocol openness, drpy2 host API requirements, implementation feasibility

---

## 1. Authorship — Who Is Who?

These are **four distinct entities** at two different layers:

### App developers (client-side)

| Name | GitHub | Role |
|------|--------|------|
| **肥猫 / FongMi** | `github.com/FongMi/TV` (8k stars, GPL-3.0) | Author of the main open-source Android TV app "肥猫电视". Also maintains the `catvod` org. The original CatVod framework author. |
| **影视仓 (Yingshicang)** | Separate fork, author known as "瘦鹅" | A polished UI fork of the same ecosystem. Compatible with the same config.json protocol. |

### Config curators (content-side, not app developers)

| Name | Domain | Role |
|------|--------|------|
| **饭太硬 (fantaiying)** | `饭太硬.net`, `饭太硬.cc`, GitHub: `fantaiying7` | Curates and publishes TVBox config JSON files. Not an app developer. Has a fork of FongMi/TV but is a separate person. |
| **肥猫 (feimao)** | `肥猫.live` | Another independent config curator. Distinct from FongMi despite the same Chinese name. |

**Key takeaway**: 肥猫 (FongMi) wrote the app; 饭太硬 and 肥猫 (feimao) are third-party config publishers who use the app. The relationship is like a podcast app vs podcast feed publishers. They all follow the same protocol because they use the same client.

---

## 2. Is the Protocol Open? Yes.

The TVBox/CatVod protocol is **fully open source** and documented through code:

- **Client**: `github.com/FongMi/TV` (GPL-3.0) — the Android app that consumes configs
- **Community OSC**: `github.com/CatVodTVOfficial/TVBoxOSC` (MIT) — the open-source reconstruction
- **Server reference**: `github.com/maccms/maccms10` (PHP) — the canonical 苹果CMS V10 server
- **JS engine**: `github.com/hjdhnx/drpy-node` — drpy2 running in Node.js (shows host API contract)
- **drpy3**: `github.com/hikerView/drpy3` — successor JS engine

There is no formal spec document, but the protocol is fully derivable from these open-source implementations. The community has documented it extensively on forums.

---

## 3. The Protocol Stack

### 3.1 Config JSON (`config.json` / the `/tv` endpoint)

The top-level subscription file. Structure:

```typescript
{
  spider:    string;          // JAR URL for csp_ spiders: "https://cdn.../spider.jar;md5;hash"
  sites:     SiteEntry[];     // content sources
  lives:     LiveEntry[];     // IPTV M3U sources
  rules:     AdFilterRule[];  // M3U8 ad-segment filter rules
  logo:      string;
  hosts:     string[];        // host rewrites: "from=to"
  wallpaper: string;
}
```

### 3.2 Site Types

| type | Protocol | Open? | Notes |
|------|----------|-------|-------|
| 0 | 苹果CMS XML (`ac=list` / `ac=detail`) | ✅ | Legacy XML format |
| 1 | 苹果CMS JSON | ✅ | Most common self-hosted VOD API |
| 2 | MacCMS variant | ✅ | JSON, same endpoints |
| 3 | JAR Spider (`csp_*`) | ✅ Source, ❌ Instances | Java class loaded from spider.jar |
| 4 | drpy2 JS spider | ✅ | `.js` file executed by embedded engine |
| 9 | drpy2 (alt numbering) | ✅ | Same as type 4 in some forks |
| 10 | drpy3 JS spider | ✅ | Newer engine |

**Important**: In the FTY config, all 52 sites use `type: 3`. This means they are JAR-based spiders. The `api` field is the Java class name (e.g. `csp_WoGGGuard`), not a URL. The drpy2 JS sites in FTY are actually wrapped inside a `csp_drpy` Java class that bridges to the JS engine — but the `api` field for those three sites is a `.js` URL, which is the drpy2 convention.

### 3.3 The 苹果CMS HTTP API (types 0/1/2) — Fully Open

This is the simplest and most portable protocol. Any server can implement it.

**Endpoints** (all GET):
```
{api}?ac=list                          → category list + optional featured items
{api}?ac=list&t={typeId}&pg={page}     → paginated category browse
{api}?ac=list&wd={keyword}&pg={page}   → search
{api}?ac=detail&ids={id1},{id2},...    → full detail for specific IDs
```

**JSON response envelope**:
```json
{
  "code": 1,
  "msg": "数据列表",
  "page": 1,
  "pagecount": 10,
  "limit": "20",
  "total": 200,
  "list": [ VodItem ],
  "class": [ { "type_id": 1, "type_name": "电影" } ]
}
```

**VodItem (list)**:
```json
{
  "vod_id": "123",
  "vod_name": "Title",
  "vod_pic": "https://...",
  "vod_remarks": "更新至12集",
  "type_id": 1,
  "type_name": "电影",
  "vod_time": "2024-01-01 12:00:00"
}
```

**VodItem (detail, from `ac=detail`)**:
```json
{
  "vod_id": "123",
  "vod_name": "Title",
  "vod_pic": "https://...",
  "vod_year": "2024",
  "vod_area": "大陆",
  "vod_actor": "Actor1,Actor2",
  "vod_director": "Director",
  "vod_content": "Overview text...",
  "vod_play_from": "source1$$$source2",
  "vod_play_url": "EP1$url1#EP2$url2$$$EP1$url1b#EP2$url2b"
}
```

**Episode URL encoding**:
- `$$$` separates multiple playback sources (mirrors)
- `#` separates episodes within a source
- `$` separates episode name from URL within each episode entry

### 3.4 JAR Spider Interface (type 3 `csp_*`) — Open Source, Closed Instances

The `Spider` abstract class is open source in FongMi/TV:

```java
public abstract class Spider {
    public void init(Context context, String extend) throws Exception {}
    public String homeContent(boolean filter) throws Exception { return ""; }
    public String homeVideoContent() throws Exception { return ""; }
    public String categoryContent(String tid, String pg, boolean filter,
                                   HashMap<String, String> extend) throws Exception { return ""; }
    public String detailContent(List<String> ids) throws Exception { return ""; }
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception { return ""; }
    public String searchContent(String key, boolean quick) throws Exception { return ""; }
    public String searchContent(String key, boolean quick, String pg) throws Exception { return ""; }
    public Object[] proxyLocal(HashMap<String, String> params) throws Exception { return null; }
}
```

All methods return JSON strings. The return formats are identical to drpy2's JS output.

The `csp_*` class names in FTY's config (e.g. `csp_WoGGGuard`, `csp_T4Guard`) are **compiled into a private spider.jar** that FTY distributes. The source for these specific spiders is not public. However, the interface they implement is fully open, and anyone can write their own `csp_*` implementations.

### 3.5 drpy2 JS Spider (type 4/9) — Fully Open

The drpy2 engine is open source. A `.js` spider file is a plain JS object:

```javascript
var rule = {
  title: "Site Name",
  host: "https://example.com",
  homeUrl: "/api/home",
  url: "/api/list?page=fypage&cat=fyclass",
  searchUrl: "/api/search?wd=**",
  detailUrl: "/api/detail/fyid",
  class_name: "电影&电视剧&综艺",
  class_url: "1&2&3",
  // Parsing rules (CSS/XPath/JSONPath/regex or inline JS):
  list: "$.data.list",
  title: "$.name",
  img: "$.cover",
  id: "$.id",
  // ... more parsing rules
};
```

The drpy2 engine interprets this rule to fetch and parse pages, returning the standard Spider JSON format.

---

## 4. drpy2 Host API Requirements — Full Inventory

This is what the host environment must provide for drpy2 to run. Based on analysis of `drpy2.min.js`:

### 4.1 Required globals (hard failure if absent)

| Global | Type | Description |
|--------|------|-------------|
| `req` | function | HTTP primitive: `req(url, opts) → { content, headers }` |
| `local` | object | KV store: `local.get(rkey, k)`, `local.set(rkey, k, v)`, `local.delete(rkey, k)` |
| `joinUrl` | function | URL resolution: `joinUrl(base, path) → string` |
| `pdfh` | function | DOM parse one: `pdfh(html, rule) → string` |
| `pdfa` | function | DOM parse all: `pdfa(html, rule) → string[]` |
| `pd` | function | DOM parse URL: `pd(html, rule, base?) → string` |
| `jinja` | object | Template engine: `jinja.render(template, obj) → string` |

### 4.2 Optional globals (checked with `typeof` before use)

| Global | Type | Description |
|--------|------|-------------|
| `pdfl` | function | Optimized list parser (enables `drpy2.1` mode) |
| `getProxy` | function | Returns local proxy URL: `getProxy(true) → string` |
| `key` | string | Rule namespace key (overrides auto-derived RKEY) |
| `JSEncrypt` | class | RSA encryption (browser-style) |
| `os` | object | File I/O (debug only: `os.open`, `os.read`) |

### 4.3 Asset imports (via `assets://` scheme)

| Import | Provides |
|--------|---------|
| `assets://js/lib/cheerio.min.js` | `cheerio` (DOM parsing + `.jp()` JSONPath + `.jinja2()`) |
| `assets://js/lib/crypto-js.js` | `CryptoJS` global (AES, MD5, SHA, etc.) |
| `./node-rsa.js` | `NodeRSA` global |
| `./pako.min.js` | `pako` global (gzip/deflate) |
| `./模板.js` | `模板` (template presets) |
| `./gbk.js` | `gbkTool` (GBK encoding/decoding) |
| `./json5.js` | `JSON5` global |
| `./jinja.js` | `jinja` global (Jinja2 templates) |

### 4.4 `req()` contract

```typescript
interface ReqOptions {
  method?: "GET" | "POST";
  headers?: Record<string, string>;
  body?: string;           // POST body
  timeout?: number;        // ms
  encoding?: string;       // e.g. "gbk" — triggers GBK decode
  buffer?: number;         // 2 = return base64 of binary response
  redirect?: 0 | 1;        // 0 = don't follow redirects
  withHeaders?: boolean;   // return JSON string of { headers, content }
}

interface ReqResult {
  content: string;         // response body (decoded string or base64)
  headers: Record<string, string>;
}
```

### 4.5 `local.*` contract

```typescript
interface LocalStorage {
  get(rkey: string, key: string): string;
  set(rkey: string, key: string, value: string): void;
  delete(rkey: string, key: string): void;
}
```

Note: 3-argument form, first arg is the rule namespace (RKEY). This is different from browser `localStorage`.

---

## 5. Mapping drpy2 Needs to Our QuickJS Environment

### What we already have

| drpy2 need | Our equivalent | Gap |
|------------|---------------|-----|
| `req()` HTTP | `host.http.get/post` | Different API shape — needs adapter |
| `host.crypto.sha256` | ✅ | Only SHA-256; drpy needs AES, MD5, RSA |
| QuickJS runtime | ✅ | Already running QuickJS |
| Persistent KV (`local.*`) | ❌ | Not implemented |
| `joinUrl` | ❌ | Trivial to implement in JS |
| `pdfh/pdfa/pd` | ❌ | Requires cheerio or equivalent DOM parser |
| `jinja` | ❌ | Jinja2 template engine |
| `CryptoJS` | ❌ | AES/MD5/SHA suite |
| `pako` | ❌ | gzip/deflate |
| `NodeRSA` | ❌ | RSA operations |
| `gbkTool` | ❌ | GBK charset encoding |
| `JSON5` | ❌ | JSON5 parser (small, easy) |
| `模板` (templates) | ❌ | FTY-specific preset rules |
| `getProxy` | ❌ | Local proxy server (complex) |

### What can be provided via host injection (Kotlin side)

These are straightforward to add to `HostApis.kt`:

| Feature | Implementation |
|---------|---------------|
| `req()` adapter | Thin wrapper over existing `OkHttpClient` with GBK support |
| `local.*` KV store | In-memory `ConcurrentHashMap` per engine instance (or SQLite for persistence) |
| `joinUrl` | Pure JS polyfill (5 lines) |
| `CryptoJS` (AES/MD5) | Bundle `crypto-js` as a pre-loaded JS asset |
| `pako` | Bundle `pako.min.js` as a pre-loaded JS asset |
| `NodeRSA` | Bundle `node-rsa.js` as a pre-loaded JS asset |
| `JSON5` | Bundle `json5.js` as a pre-loaded JS asset |
| `jinja` | Bundle `jinja.js` as a pre-loaded JS asset |
| `gbkTool` | Kotlin-side GBK decode, exposed via `host.charset.decode(base64, encoding)` |

### What requires more work

| Feature | Complexity | Notes |
|---------|-----------|-------|
| `pdfh/pdfa/pd` (DOM parsing) | **Medium** | Need cheerio or a lightweight HTML parser in QuickJS. Cheerio itself is ~300KB minified. Alternative: implement `pdfh/pdfa/pd` as Kotlin-side host calls using Jsoup (already on Android). |
| `assets://` module resolver | **Medium** | Need to intercept ES module imports in QuickJS. Alternative: pre-bundle all assets into a single IIFE before loading. |
| `getProxy` / local proxy | **High** | drpy2's `proxy()` function routes M3U8 ad-stripping through a local HTTP server. Not needed for basic playback — only for ad-filtered streams. Can be stubbed initially. |
| `模板` (template presets) | **Low** | A static JS object with preset rule templates. Can be bundled. |

### The `assets://` problem — two solutions

**Option 1 — Pre-bundle**: Before loading a drpy2 spider, concatenate all required assets into a single IIFE that sets up globals, then load the drpy2 engine, then load the spider rule. No module resolver needed. This is how `drpy-node` works.

**Option 2 — Module resolver**: Implement a custom ES module loader in the QuickJS C bindings that intercepts `assets://` imports and serves pre-loaded content. More elegant but requires native code changes.

Option 1 is simpler and sufficient.

---

## 6. The `pdfh/pdfa/pd` Problem — Key Decision Point

These three functions are the heart of drpy2's parsing capability. They implement a mini-DSL:

```
pdfh(html, "ul.list&&li&&Text")     → text content of first match
pdfa(html, "ul.list&&li")           → array of matched elements (as HTML strings)
pd(html, "a&&href", "https://base") → resolved URL from attribute
```

The rule syntax is: `selector&&selector&&...&&(Text|attr_name)`.

**Option A — Kotlin/Jsoup host call**: Implement `pdfh/pdfa/pd` as `host.dom.*` calls dispatched to Jsoup on the Kotlin side. Jsoup is already available on Android. This avoids bundling cheerio (~300KB) into QuickJS.

```kotlin
// In HostApis.kt
fun handleDom(name: String, argsJson: String): String? {
    return when (name) {
        "pdfh" -> { /* Jsoup parse + selector chain */ }
        "pdfa" -> { /* Jsoup parse + selector chain → JSON array */ }
        "pd"   -> { /* Jsoup parse + URL resolution */ }
        else   -> throw IllegalArgumentException("Unknown dom method: $name")
    }
}
```

Then in the JS bootstrap:
```javascript
function pdfh(html, rule) { return host.dom.pdfh({ html, rule }); }
function pdfa(html, rule) { return JSON.parse(host.dom.pdfa({ html, rule })); }
function pd(html, rule, base) { return host.dom.pd({ html, rule, base }); }
```

**Option B — Bundle cheerio**: Include `cheerio.min.js` (~300KB) as a pre-loaded asset. Heavier but self-contained and matches the TVBox reference implementation exactly.

Option A is recommended — Jsoup is already a dependency, avoids 300KB of JS, and the selector DSL is simple enough to implement in ~100 lines of Kotlin.

---

## 7. Overall Feasibility Assessment

### What we can implement

| Capability | Effort | Value |
|-----------|--------|-------|
| 苹果CMS type 1/2 HTTP API | **Low (1–2 days)** | High — works with hundreds of self-hosted VOD sites |
| IPTV M3U live sources | **Low (1 day)** | Medium — Chinese live TV channels |
| drpy2 JS spider support | **Medium (1–2 weeks)** | High — unlocks the open JS spider ecosystem |
| drpy3 JS spider support | **Low (incremental)** | Same engine, minor API differences |

### What we cannot implement

| Capability | Reason |
|-----------|--------|
| FTY's specific `csp_*` spiders | Private spider.jar, no source |
| Encrypted `ext` fields (AES) | Key is in the private JAR |
| `csp_WoGGGuard`, `csp_T4Guard`, etc. | Closed-source implementations |

### The key insight

The FTY config is a **curated list** that happens to use mostly private `csp_*` spiders. But the underlying protocol is open. We don't need FTY's specific spiders — we can:

1. Implement the **苹果CMS HTTP API** (types 1/2) and connect to any of the hundreds of public/self-hosted VOD sites that expose this API
2. Implement **drpy2 JS spider** support and run any of the thousands of open `.js` spider files in the community
3. Implement **IPTV M3U** support for live channels

This gives us access to the entire open TVBox ecosystem, not just FTY's curated list.

---

## 8. Recommended Implementation Plan

### Phase 1 — 苹果CMS HTTP API provider (1–2 days)

A new JS provider that implements the type 1/2 HTTP API. User configures a base URL; the provider calls `?ac=list` and `?ac=detail`.

Mapping to OpenTune contracts:
- `listEntry(null)` → `?ac=list` → categories as `Folder` entries
- `listEntry(categoryId)` → `?ac=list&t={id}&pg={page}` → `Playable`/`Series` entries
- `search(query)` → `?ac=list&wd={query}` → results
- `getDetail(id)` → `?ac=detail&ids={id}` → `EntryDetail` with episode list
- `getPlaybackSpec(episodeUrl)` → direct URL (or via configured parser)

No drpy2 needed. Pure HTTP.

### Phase 2 — drpy2 JS spider support (1–2 weeks)

Extend the existing JS provider infrastructure to support drpy2 spider files.

Required additions to `HostApis.kt`:
1. `host.dom.pdfh/pdfa/pd` — Jsoup-backed DOM parsing
2. `host.kv.get/set/delete` — per-engine KV store (in-memory)
3. `host.charset.decode` — GBK/charset decoding

Required JS bootstrap additions (pre-loaded before drpy2):
1. `req()` adapter wrapping `host.http`
2. `joinUrl()` polyfill
3. `local` object wrapping `host.kv`
4. Bundled: `crypto-js`, `pako`, `node-rsa`, `json5`, `jinja`
5. `模板` preset object (static)

The drpy2 engine itself (`drpy2.min.js`) loads after the bootstrap. Then the spider rule `.js` file loads last.

Call flow mapping to OpenTune contracts:
- `listEntry(null)` → `drpy.home()` → categories
- `listEntry(categoryId)` → `drpy.category(tid, pg, filter, extend)`
- `search(query)` → `drpy.search(wd, quick, pg)`
- `getDetail(id)` → `drpy.detail(id)` → parse `vod_play_from`/`vod_play_url`
- `getPlaybackSpec(flag, episodeUrl)` → `drpy.play(flag, id, flags)`

### Phase 3 — IPTV M3U provider (1 day, independent)

Parse M3U playlists and expose channels as `Playable` entries. Can be a separate simple provider.

---

## 9. What the FTY Config Gives Us (Revised Assessment)

With drpy2 support implemented, the FTY config becomes more useful:

| Source type | Count | Accessible with our impl? |
|-------------|-------|--------------------------|
| `csp_*` (private JAR) | 49 | ❌ No |
| drpy2 JS sites | 3 | ✅ Yes (虎牙, 斗鱼, 兔小贝) |
| IPTV M3U lives | 7 | ✅ Yes |

But more importantly, the broader TVBox ecosystem has **thousands of open drpy2 `.js` spider files** and **hundreds of public 苹果CMS API endpoints** that we can access once the protocol is implemented. FTY's specific config is just one entry point.
