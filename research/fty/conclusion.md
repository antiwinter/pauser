# Research Conclusion — TVBox / FTY Provider Ecosystem

**Date:** 2026-05-20

---

## 1. Authorship & Relationships

| Entity | Role | Relation |
|--------|------|----------|
| **FongMi / 肥猫** | App developer (`github.com/FongMi/TV`, GPL-3.0) | Wrote the client app and the CatVod Spider framework |
| **影视仓 (Yingshicang)** | App fork, different author ("瘦鹅") | Compatible client, same protocol |
| **饭太硬 (FTY)** | Config curator, `github.com/fantaiying7` | Publishes `config.json` + spider JAR, not the app |
| **肥猫 (feimao)** | Config curator, `肥猫.live` | Different person from FongMi despite same name |

Config curators (FTY, feimao) are to the app what podcast publishers are to a podcast app. They don't write the client — they publish subscription feeds.

---

## 2. The Protocol Is Fully Open

The TVBox/CatVod protocol has no official spec document but is fully derivable from open-source code:

- **Client**: `github.com/FongMi/TV` (GPL-3.0)
- **Server reference**: `github.com/maccms/maccms10` (苹果CMS V10, PHP)
- **JS engine**: drpy2 (`github.com/hjdhnx/drpy-node`, Apache-2.0)
- **drpy3**: `github.com/hikerView/drpy3`

Multiple independent apps (FongMi/TV, 影视仓, ZyPlayer) implement the same protocol, confirming it is a de-facto open standard.

---

## 3. The Spider JAR Architecture

The `spider.jar` (downloaded at runtime from the config's `spider` field) is **self-contained**:

```
spider.jar
├── classes.dex           — decryption bootstrap (Init + DexNative)
└── assets/
    ├── ftyguard_v7.so    — ARM 32-bit JNI native lib (holds AES key)
    ├── ftyguard_v8.so    — ARM 64-bit JNI native lib (holds AES key)
    └── ftyshinidie.guard — encrypted DEX (real Spider implementations)
```

**The app (FongMi/TV) does NOT bundle any provider-specific native code.** Each JAR brings its own native library. The app only needs `DexClassLoader` — a standard Android API.

Loading flow:
1. App downloads `spider.jar` from config URL
2. `Init` class extracts `ftyguard_v8.so` from JAR assets to a temp dir
3. `System.loadLibrary()` loads it
4. Native `datadiv_decode*` decrypts `ftyshinidie.guard` using embedded AES key
5. `DexClassLoader` loads the decrypted DEX (~2 MB, 46 Spider classes)
6. Spider classes are cast to `com.github.catvod.crawler.Spider` and called

**Different config providers use different JARs with different native libs.** The app is provider-agnostic — it just calls the standard Spider interface.

---

## 4. Can We Load the JAR?

**Yes, on Android.** The mechanism is:
- Standard `DexClassLoader` (Android API, no special permissions)
- `System.loadLibrary()` from a temp path (standard Android)
- `Spider.init(Context, ext)` uses only standard Android APIs (`SharedPreferences`, `getCacheDir`, `getFilesDir`)

Our app is Android and has all of these. The only non-trivial step is extracting the `.so` from the JAR's `assets/` to a writable temp dir before loading.

**Caveats:**
- Legal: loading third-party encrypted code; the encryption is specifically designed to prevent reverse-engineering
- Maintenance: JAR URLs change; the `ext` field AES key is inside the native lib (we can call spiders but cannot decrypt `ext` ourselves — the spider does it internally via `DexNative`)
- The `ext` decryption is transparent to us — we pass the raw encrypted `ext` string to `Spider.init()` and the spider decrypts it internally using its own native code

---

## 5. Protocol Summary

### Site types

| type | Protocol | Open? | Server needed? |
|------|----------|-------|----------------|
| 0/1/2 | 苹果CMS HTTP API | ✅ | Yes (PHP/any) |
| 3 | JAR Spider (`csp_*`) | Interface ✅, Instances vary | No (runs in app) |
| 4/9 | drpy2 JS spider | ✅ | No (runs in app) |
| 10 | drpy3 JS spider | ✅ | No (runs in app) |

### 苹果CMS HTTP API (types 0/1/2)

```
GET {api}?ac=list                       → categories + optional featured
GET {api}?ac=list&t={id}&pg={page}      → paginated browse
GET {api}?ac=list&wd={keyword}          → search
GET {api}?ac=detail&ids={id1},{id2}     → full detail + episode list
```

Episode URL format: `vod_play_from = "src1$$$src2"`, `vod_play_url = "EP1$url1#EP2$url2$$$EP1$url1b#EP2$url2b"`

### Spider interface (type 3)

```java
String init(Context context, String extend)
String homeContent(boolean filter)
String homeVideoContent()
String categoryContent(String tid, String pg, boolean filter, HashMap<String,String> extend)
String detailContent(List<String> ids)
String playerContent(String flag, String id, List<String> vipFlags)
String searchContent(String key, boolean quick)
```

All return JSON strings in the same format as the 苹果CMS API.

### drpy2 host API requirements (type 4/9)

Required globals: `req`, `local`, `joinUrl`, `pdfh`, `pdfa`, `pd`, `jinja`  
Required assets: `cheerio`, `crypto-js`, `pako`, `node-rsa`, `json5`, `jinja`, `gbkTool`

---

## 6. Implementation Feasibility

| Feature | Effort | Notes |
|---------|--------|-------|
| 苹果CMS HTTP API provider (TS) | **1–2 days** | Pure HTTP, no native code |
| IPTV M3U live provider | **1 day** | Parse M3U, expose as Playable |
| drpy2 JS spider support | **1–2 weeks** | Needs host API extensions (dom, kv, charset) |
| JAR Spider loading (`host.loadJar`) | **1–2 weeks** | DexClassLoader + native lib extraction |
| Full TVBox provider (TS) combining all | **3–4 weeks** | Builds on all above |
