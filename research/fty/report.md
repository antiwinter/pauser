# 饭太硬 (FTY) — Provider Feasibility Research

**Date:** 2026-05-20  
**Source URL:** `http://www.饭太硬.net/tv` (punycode: `http://www.xn--sss604efuw.net/tv`)  
**Raw JSON:** [fty_tv.json](./fty_tv.json)

---

## 1. What Is This?

饭太硬 ("FTY", literally "rice is too hard") is a curated **TVBox configuration index** — a well-known Chinese IPTV/VOD aggregator config popular in mainland China. The `/tv` endpoint is the "空壳接口" (skeleton interface): a single JSON blob that TVBox-compatible Android TV apps (FongMi/TV, CatVOD, etc.) consume to discover dozens of media sources.

### Authorship & Relationships

| Entity | Role | Relation |
|--------|------|----------|
| **FongMi / 肥猫** | App developer (`github.com/FongMi/TV`, GPL-3.0) | Wrote the client app and the CatVod Spider framework |
| **影视仓 (Yingshicang)** | App fork, different author ("瘦鹅") | Compatible client, same protocol |
| **饭太硬 (FTY)** | Config curator, `github.com/fantaiying7` | Publishes `config.json` + spider JAR, not the app |
| **肥猫 (feimao)** | Config curator, `肥猫.live` | Different person from FongMi despite same name |

Config curators (FTY, feimao) are to the app what podcast publishers are to a podcast app. They don't write the client — they publish subscription feeds.

### Delivery mechanism

The JSON is **not served as `application/json`**. Instead, it is base64-encoded and embedded inside a JPEG image (JFIF, 100×100, ~20 KB). The host server returns `Content-Type: image/jpeg` or `image/x-ms-bmp` to evade scraping/blocking. The TVBox client decodes the embedded payload client-side. This is an intentional obfuscation layer.

---

## 2. JSON Structure

```
{
  "spider":    "<JAR URL>;md5;<hash>",    // spider JAR for csp_* providers
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
  type:          number;          // 0/1/2=CMS HTTP, 3=JAR spider, 4/9=drpy2, 10=drpy3
  api:           string;          // class name (type 3) or URL (type 0/1/2/4/9/10)
  ext?:          string | object; // config payload; may be AES-encrypted base64 for some csp_* entries
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

## 3. Site Types in This Config (52 Sites)

**All 52 sites in the FTY config use `type: 3`** (JAR spider). There are no bare type 4/9 drpy2 entries at the top level. The 3 sites that internally use JS rules (虎牙直播, 斗鱼直播, 兔小贝) are wrapped inside a `csp_drpy` Java class — the `api` field for those entries is a `.js` URL, but the site type is still `3` and the JAR must be loaded first.

| Protocol type | Count | Description |
|------|-------|-------------|
| `type: 3` — JAR spider (`csp_*`) | 49 | Java spider classes loaded from `spider.jar` at runtime |
| `type: 3` — JAR + drpy2 bridge | 3 | Java `csp_drpy` class that loads and runs a `.js` rule file |
| IPTV M3U (`lives`) | 7 | Plain M3U playlists (separate from the `sites` array) |

---

## 4. The Spider JAR Architecture

The `spider.jar` (downloaded at runtime from the config's `spider` field) is **self-contained**:

```
spider.jar (972 KB)
├── classes.dex           — decryption bootstrap (Init + DexNative)
└── assets/
    ├── ftyguard_v7.so    — ARM 32-bit JNI native lib (holds AES key + decryption routine)
    ├── ftyguard_v8.so    — ARM 64-bit JNI native lib (holds AES key + decryption routine)
    └── ftyshinidie.guard — custom-encrypted container (~2 MB DEX when decrypted)
```

The `classes.dex` in the outer JAR is only a **decryption bootstrap** — it contains `Init` and `DexNative` but not the real Spider implementations. The actual 46 Spider classes live inside `ftyshinidie.guard`, which is decrypted at runtime by the native `.so`.

Loading flow:
1. App downloads `spider.jar` from config URL
2. `Init` extracts `ftyguard_v8.so` (or `v7` for 32-bit) to a temp dir
3. `System.loadLibrary()` loads it
4. Native `datadiv_decode*` decrypts `ftyshinidie.guard` into a real DEX (~2 MB)
5. `DexClassLoader` loads the decrypted DEX (46 Spider classes)
6. Spider classes are cast to `com.github.catvod.crawler.Spider` and called

**Different config providers use different JARs with different native libs.** The app is provider-agnostic.

---

## 5. csp_* Providers

The 46 Spider implementations in the decrypted DEX all extend `com.github.catvod.crawler.Spider` (open-source interface from FongMi/TV). Notable entries:

- `csp_BiliGuard` — Bilibili
- `csp_WoGGGuard` — 4K VOD with danmaku
- `csp_T4Guard`, `csp_AppSxGuard` — Multi-source aggregators (encrypted `ext`)
- `csp_LibvioGuard`, `csp_NewCzGuard` — VOD with instant playback
- `csp_SixVGuard` — Magnetic torrent sources
- `csp_MyDriveGuard` — Personal cloud drive

The Spider interface is open source; the specific implementations are compiled into the encrypted `.guard` payload. The `ext` field for some entries (e.g. `T4Guard`, `AppSxGuard`) is an AES-encrypted base64 string — it is decrypted internally by `DexNative` using a key embedded in the `.so`. We pass the raw `ext` string through to `Spider.init()` and the spider decrypts it itself.

---

## 6. drpy2 — The JS Engine

`drpy2.min.js` (~67 KB) is the open-source rule execution engine. In the FTY config the 3 drpy2-backed sites go through a `csp_drpy` JAR bridge, but drpy2 can also run standalone (type 4/9/10 in other configs).

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
```

### drpy2 host API requirements

drpy2 requires several host globals and pre-loaded assets:

**Required globals:** `req`, `local`, `joinUrl`, `pdfh`, `pdfa`, `pd`, `jinja`  
**Required assets:** `cheerio`, `crypto-js`, `pako`, `node-rsa`, `json5`, `jinja`, `gbkTool`

These are expected to be pre-loaded in the host environment. Our QuickJS currently provides `host.http` and `host.crypto.sha256` only — implementing full drpy2 support requires extending the host API (see §8b below).

---

## 7. Content Categories

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

## 8. Feasibility Assessment

### What can we implement?

| Component | Feasibility | Effort |
|-----------|------------|--------|
| Fetch & decode the `/tv` config (JPEG base64 unwrap) | ✅ Trivial | Hours |
| IPTV M3U live sources (7 `lives` entries) | ✅ Easy | 1 day |
| 苹果CMS HTTP API (type 0/1/2 sites) | ✅ Easy | 1–2 days |
| JAR spider loading (`host.loadJar`, `host.spider.*`) | ✅ Feasible on Android | ~3.5 days |
| drpy2 JS spider support (type 4/9/10) | ✅ Feasible | 1–2 weeks |
| FTY's specific `csp_*` Spider behavior | ⚠️ Opaque source | Call the JAR (above); no reimplementation needed |
| Encrypted `ext` fields | ✅ Transparent | Passed through to `Spider.init()`; spider decrypts internally |

#### 8a. JAR spider loading

**Our app is Android and can load the JAR.** The mechanism is standard:
- `DexClassLoader` (Android API, no special permissions)
- `System.loadLibrary()` from a temp path (standard Android)
- Extract `ftyguard_v8.so` from JAR `assets/` to `cacheDir` before loading

`Spider.init(Context, ext)` uses only standard Android APIs. The `ext` decryption is **transparent to us** — we pass the raw encrypted string to `Spider.init()` and the spider's native code decrypts it internally using its own embedded key. We never need to know the AES key.

A concrete `host.loadJar()` / `host.spider.*` implementation plan is documented in [plan-host-load-jar.md](./plan-host-load-jar.md). Estimated effort: **~3.5 days**.

#### 8b. drpy2 JS spider support

The drpy2 engine is open-source and can run in our QuickJS sandbox once the host API gaps are filled:

| Gap | Solution |
|-----|---------|
| `req()` HTTP | Adapter over existing `host.http` |
| `pdfh/pdfa/pd` DOM parsing | Kotlin-side Jsoup host calls (avoids bundling 300KB cheerio) |
| `local.*` KV store | In-memory `ConcurrentHashMap` per engine instance |
| `CryptoJS`, `pako`, `node-rsa`, `json5`, `jinja` | Pre-bundled JS assets |
| `gbkTool` | Kotlin-side `host.charset.decode` |
| `assets://` module resolver | Pre-bundle all assets into a single IIFE before loading drpy2 |

Estimated effort: **1–2 weeks**.

#### 8c. IPTV / M3U lives (easy win)

The 7 live sources are plain M3U playlists hosted on public GitHub/CDN URLs. These can be ingested as a flat list of `Playable` channel entries with no dependencies on either the JAR or drpy2.

---

## 9. Implementation Options (Ranked)

### Option A — IPTV-only (Low effort, narrow scope)
- Fetch live M3U sources from the `lives` array
- Map channels to `EntryInfo { type: "Playable" }`
- No JAR, no drpy2 needed
- Delivers ~50–100 live Chinese TV channels
- **Effort: 1 day**

### Option B — 苹果CMS HTTP API provider (Low effort, broad ecosystem)
- Implement type 0/1/2 HTTP API
- Works with hundreds of self-hosted VOD sites, not just FTY
- No native code, no drpy2 needed
- **Effort: 1–2 days**

### Option C — Full TVBox provider with JAR + drpy2 support (Phased, ~4–5 weeks total)
- Phase 1: CMS HTTP + IPTV (3 days) — see [plan-tvbox-provider.md](./plan-tvbox-provider.md)
- Phase 2: JAR spider loading (3.5 days) — see [plan-host-load-jar.md](./plan-host-load-jar.md)
- Phase 3: drpy2 JS spider support (1–2 weeks)
- Unlocks the full TVBox ecosystem including FTY's 49 csp_* sites

### Option D — Target open ecosystem instead of FTY specifically
- The TVBox protocol has thousands of open drpy2 `.js` spiders and hundreds of public 苹果CMS endpoints
- Implementing Options B + drpy2 (Option C Phase 3) gives access to this broader ecosystem without any JAR dependency
- FTY's specific JAR is a curatorial convenience, not a unique content source

---

## 10. Technical Risks & Concerns

1. **Legal / content legitimacy**: FTY aggregates content from unauthorized streaming sources. The `csp_*` providers in particular are known to scrape licensed platforms. Implementing JAR loading would facilitate use of these spiders.

2. **Stability**: The config URL and embedded sources change frequently. Any hardcoded URL will break.

3. **Config obfuscation**: The JPEG-hiding trick means standard HTTP clients silently get garbage unless the decode step is implemented. This is intentional and could change at any time.

4. **JAR execution security**: The JAR runs in the same process with full app permissions. Only load JARs from URLs explicitly configured by the user; verify MD5 before loading.

5. **drpy2 asset bundle size**: Pre-bundling `crypto-js`, `pako`, `node-rsa`, `json5`, `jinja` adds ~500KB to the JS environment. Acceptable but worth noting.

6. **ARM `.so` on non-Android**: The native libs are ARM-compiled. They can only run on Android devices (armv7 or armv8). No desktop/server support possible.

---

## 11. Recommendation

**Pursue Option C in phases**, starting with Phase 1 (CMS HTTP + IPTV) as the immediate deliverable.

- **Phase 1** (CMS + IPTV, ~3 days): Immediate value with no new host APIs. Works with the `lives` array and any type 0/1/2 site.
- **Phase 2** (JAR loading, ~3.5 days): Unlocks FTY's 49 csp_* sites via `host.loadJar`. The technical path is clear and the effort is bounded.
- **Phase 3** (drpy2, 1–2 weeks): Unlocks the broader open JS spider ecosystem; worthwhile after Phase 2.

If the goal is narrowly "get Chinese live TV with minimal work," Option A (IPTV only) is sufficient. But the full protocol implementation (Option C) is achievable within reasonable effort and gives access to the entire TVBox ecosystem.

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
| Spider JAR analysis | [spider-jar-analysis.md](./spider-jar-analysis.md) |
| Protocol deep dive | [tvbox-protocol-deep-dive.md](./tvbox-protocol-deep-dive.md) |
| JAR loading plan | [plan-host-load-jar.md](./plan-host-load-jar.md) |
| TVBox provider plan | [plan-tvbox-provider.md](./plan-tvbox-provider.md) |
