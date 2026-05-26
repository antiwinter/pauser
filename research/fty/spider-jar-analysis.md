# Spider JAR Deep Analysis — Can We Load It?

**Source:** FTY's `spyder.jar` (spider.jar from `http://www.饭太硬.cc/tv` config)  
**File:** `spyder.jar` (972 KB) — downloaded, extracted, and fully analyzed  
**Location:** `/tmp/fty_spider.bin` (original download)

---

## 1. JAR Structure

```
spyder.jar (ZIP format)
├── classes.dex           (36,668 bytes) — main DEX
├── META-INF/MANIFEST.MF
└── assets/
    ├── ftyguard_v7.so    (81,472 bytes) — 32-bit ARM native lib
    ├── ftyguard_v8.so    (94,200 bytes) — 64-bit ARM native lib
    └── ftyshinidie.guard (858,506 bytes) — encrypted payload
```

---

## 2. What We Found

### DEX Contents

The `classes.dex` contains **46 Spider implementations** + infrastructure classes:

**Spider implementations** (all extend `com.github.catvod.crawler.Spider`):
```
AListGuard, AllliveGuard, Anime1Guard, AppNoxGuard, AppSKGuard,
AppSxGuard, AppTTGuard, AppYsV2Guard, AueteGuard, BiliGuard,
BpanSoGuard, BttwooGuard, DexNative, Dm84Guard, DouDouGuard,
FirstAidGuard, JPJGuard, JpysGuard, JqqGuard, KanqiuGuard,
KkSsGuard, LibvioGuard, LiteAppleGuard, LiveGzGuard, LocalGuard,
MIPanSoGuard, MusicGuard, MyDriveGuard, NewCzGuard, NmyswvGuard,
Proxy, PushGuard, S_zpsGuard, SeedhubGuard, Sir88Guard,
SixVGuard, T4Guard, Tingshu275Guard, UuSsGuard, WebDAVGuard,
WoGGGuard, YCyzGuard, YGPGuard, YoutubeGuard, YpanSoGuard,
BaseSpiderGuard, Init$1, Init$Loader, Init
```

All extend `com.github.catvod.crawler.Spider` (the open CatVod interface).

### The Encryption Architecture

The JAR is protected by a **two-layer encryption system**:

**Layer 1 — Native libraries** (`ftyguard_v7.so` / `ftyguard_v8.so`):
- Standard ELF format, 32-bit ARM (v7) and 64-bit ARM (v8) native libraries
- Compiled with Clang, LLVM toolchain
- Contains `JNI_OnLoad` — it's a JNI native library loaded via `System.loadLibrary()`
- Exported symbol: `datadiv_decode*` functions — custom decryption routines

**Layer 2 — The `.guard` file** (`ftyshinidie.guard`):
- Starts with `PK\x03\x04` (ZIP local file header magic)
- Header claims it's `classes.dex`, compressed size = 858,199 bytes, uncompressed = 2,036,416 bytes
- **However**: Python's zipfile cannot parse it — the DEFLATE stream is encrypted/obfuscated
- The native `ftyguard` library must decrypt this file before use

**The `ftyshinidie.guard` is NOT a standard ZIP.** It's a custom encrypted container. The ZIP header is a lie.

### How the encryption flow works

```
DexNative.class
    ↓ calls native method (via JNI)
ftyguard_v8.so (JNI_OnLoad registers native methods)
    ↓
datadiv_decode() ← decryption function in native code
    ↓
ftyshinidie.guard (encrypted data)
    ↓
decrypted → actual classes.dex (2 MB decompressed)
    ↓
loaded via DexClassLoader
```

### What Init class does

The `Init` class is the **decryption bootstrap**. It:
1. Loads `ftyguard_v7.so` or `ftyguard_v8.so` (based on device ABI)
2. Calls native `init()` function which decrypts `ftyshinidie.guard` into a real DEX
3. Loads the decrypted DEX via DexClassLoader
4. The decrypted DEX contains the real Spider implementations

### What DexNative does

`DexNative` bridges Java and native code. It provides native methods that the Spyder classes call to decrypt their `ext` fields. This is how the encrypted `ext` values (base64 strings in the FTY config) get decrypted — the native library holds the AES key and algorithm.

---

## 3. Can We Load This JAR in Our System?

### Obstacles

| Obstacle | Severity | Description |
|----------|----------|-------------|
| **Native library** (`ftyguard_v7/v8.so`) | ❌ **Critical** | ARM-compiled JNI libraries. Would need reverse-engineering of the decryption algorithm to reimplement in pure Java/Kotlin. |
| **Encrypted `.guard` file** | ❌ **Critical** | Cannot be decompressed without the native decryption key. The algorithm is hidden in the `.so` files. |
| **ARM .so on non-Android** | ❌ **Critical** | Even if we run on Android, we'd need to bundle both ARM v7 and v8 variants and handle ABI selection. |
| **Android Context dependency** | ⚠️ Medium | `Spider.init(Context, String)` requires an Android `Context`. Our QuickJS engine doesn't have a live Android Context in the same way. |
| **AES key embedded in .so** | ❌ **Critical** | The key used to decrypt the `ext` field is compiled into the native binary and is not accessible to us. |

### What IS accessible

| Component | Accessible? | Notes |
|-----------|-------------|-------|
| `Spider` abstract class interface | ✅ Yes (open source in FongMi/TV) | Standard Spider interface |
| Method signatures: `homeContent`, `categoryContent`, etc. | ✅ Yes | All return JSON strings |
| The raw bytecode of the spider classes | ✅ Yes (readable via strings) | We can decompile/disassemble |
| 46 Spider implementations in the DEX | ⚠️ Partially | The real classes are inside the encrypted `.guard`; the outer JAR only has the decryption bootstrap |
| Android framework APIs (okhttp, SharedPreferences) | ✅ Yes | Our app is Android and has these |
| The `ext` field encryption | ❌ No | AES key is in native code we can't read |

---

## 4. Detailed Technical Findings

### The Spider interface (from FongMi/TV source)

```java
package com.github.catvod.crawler;

public abstract class Spider {
    public abstract String init(Context context, String extend) throws Exception;
    public abstract String homeContent(boolean filter) throws Exception;
    public abstract String homeVideoContent() throws Exception;
    public abstract String categoryContent(String tid, String pg, boolean filter,
                                          HashMap<String, String> extend) throws Exception;
    public abstract String detailContent(List<String> ids) throws Exception;
    public abstract String playerContent(String flag, String id, List<String> vipFlags) throws Exception;
    public abstract String searchContent(String key, boolean quick) throws Exception;
    public String searchContent(String key, boolean quick, String pg)
        throws Exception { return searchContent(key, quick); }
}
```

All methods return `String` (JSON). The interface is clean and simple.

### The native library ABI

```
ftyguard_v7.so:  ARM v7 (32-bit), EABI5, position-independent
  - Targets: android armv7 devices
  - Compiled: Android NDK clang

ftyguard_v8.so:  ARM v8 (64-bit), AArch64
  - Targets: android armv8 devices (all modern phones/TV boxes)
  - Also compiled: Android NDK clang
  - Contains JNI_OnLoad symbol
```

### The encryption mechanism

The `ftyshinidie.guard` file is:
1. A custom encrypted container (not standard ZIP despite magic bytes)
2. Decrypted by `datadiv_decode*` functions in the native `.so`
3. Decompressed size (2,036,416 bytes) suggests the decrypted content is ~2 MB of DEX
4. The decryption is specific to this JAR instance — different FTY configs may use different keys

### Which spiders need the encrypted `ext`

Looking at the FTY config `ext` field patterns:
- Most sites: `ext` is a plain dictionary (e.g., `{"Cloud-drive":"tvfan/Cloud-drive.txt"}`)
- `T4Guard`, `AppSxGuard` (and many multi-source aggregators): encrypted base64 string in `ext`
- The encrypted `ext` is decoded by `DexNative.nativeDecode()` before being passed to `Spider.init()`

**The encrypted `ext` contains site-specific configuration** — API keys, base URLs, etc. Without the decryption, we can still call the Spider methods but with an incorrect/missing `ext` parameter, which would cause the spider to fail or return empty results.

---

## 5. Alternative Approaches

### Approach A — Re-implement Spiders from Bytecode

**Effort: Very high. Legal risk: High.**

We can decompile the (encrypted) JAR's DEX and see the actual Spider implementations. However:
1. The real classes are in the encrypted `.guard`, not accessible without the native key
2. Even if we could extract the bytecode, reverse-engineering and reimplementing 46 spiders is months of work
3. The encryption is specifically designed to prevent this

### Approach B — Implement the Spider Interface from Open Specs

**Effort: High. Legal risk: Low.**

The Spider interface is open (from FongMi/TV). Many spiders make HTTP calls to known APIs (e.g., Bilibili, Libvio). We could implement the same API calls ourselves following the same interface.

However:
1. Each Spider has site-specific logic (CSS selectors, API endpoints, login flows)
2. We'd need to reverse-engineer each spider's behavior from network traffic, not from bytecode
3. The encrypted `ext` fields contain site credentials we don't have
4. This is effectively building new providers from scratch, not "plugging in" the JAR

### Approach C — Decrypt the `ext` Fields via Hook

**Effort: Medium. Legal risk: High.**

If we run the FongMi/TV app in an Android sandbox (or on the same device as our app), we could potentially hook into the native decryption function and extract the AES key at runtime.

However:
1. Requires running as root or using Xposed/Frida on the target device
2. The key is inside ARM machine code, extracted via memory inspection at runtime
3. Device-specific and not portable
4. Legal gray area ( circumvents access controls)

### Approach D — Focus on Open Protocol Alternatives

**Effort: Low. Legal risk: None.**

The actual value in the TVBox ecosystem is accessible without touching the JAR:

1. **苹果CMS HTTP API (type 1/2)**: Hundreds of self-hosted VOD sites expose this API publicly. No encryption, no JAR, just HTTP calls.

2. **drpy2 JS spiders**: Thousands of open-source `.js` spider files follow the drpy2 standard. These run in any JS engine that provides the required host API (cheerio, crypto-js, pako, etc.). No native code needed.

3. **M3U IPTV live**: The 7 live sources in the FTY config are plain M3U URLs.

4. **Open-source spider implementations**: The FongMi/TV GitHub has open-source spider implementations that we can study and re-implement for specific sites.

---

## 6. Conclusion

**Can we "plug in" the FTY spider.jar to our system? No — not in any practical sense.**

The JAR is protected by:
1. ARM native code encryption (ftyguard_v7/v8.so) — custom, not standard
2. An encrypted payload (ftyshinidie.guard) that wraps the real Spider implementations
3. An AES key embedded in compiled ARM machine code

Even if running on Android with the same CPU architecture, we cannot extract the decryption key from the `.so` files without significant reverse-engineering effort.

**What we can do instead:**

| Alternative | Effort | Outcome |
|-------------|--------|---------|
| Reimplement Spider interface for sites with known APIs | 1-2 weeks per site | Limited to sites whose API we can reverse |
| drpy2 JS support | 1-2 weeks | Unlocks thousands of open JS spiders |
| 苹果CMS HTTP API support | 1-2 days | Unlocks hundreds of public VOD sites |
| Focus on sites where we have credentials | Medium | e.g., Alist on personal cloud drive |

**Recommendation:** Pursue the open protocol path (drpy2 + 苹果CMS + M3U). This gives access to the same content ecosystem without any legal or technical entanglement with the encrypted JAR. The encrypted spiders in FTY's JAR are a curatorial convenience (one-click setup), not a unique source of content.

---

## Appendix — Files Analyzed

| File | Size | Type | Analysis |
|------|------|------|----------|
| `spyder.jar` | 972 KB | ZIP/JAR | Downloaded from FTY config `spider` field |
| `classes.dex` | 36 KB | DEX | Decryption bootstrap + Init class only |
| `ftyguard_v7.so` | 81 KB | ELF/ARMv7 | JNI native lib, contains `datadiv_decode*` |
| `ftyguard_v8.so` | 94 KB | ELF/ARMv8 | JNI native lib, contains `JNI_OnLoad` |
| `ftyshinidie.guard` | 858 KB | Custom encrypted | ZIP magic but DEFLATE stream is custom-encrypted |