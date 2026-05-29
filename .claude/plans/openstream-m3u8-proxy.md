# Plan: openStream-based M3U8 Proxy

## Goal

Use the existing `StreamProxy` + `openStream()` infrastructure to serve cleaned M3U8 manifests to ExoPlayer. All filtering (DNS remapping + regex ad removal) runs in TypeScript; Kotlin only wraps the text in a `ProviderStream` and serves bytes.

## Architecture

### Why this over a Ktor route

The existing `StreamProxy` already bridges `ProviderStream` (random-access bytes) → HTTP responses for ExoPlayer. Reusing it avoids adding a new Ktor route. The 128K chunk size and random-access interface are overkill for a few-KB M3U8 text, but functionally correct — ExoPlayer reads sequentially from offset 0, which `StreamProxy` handles fine.

### Data flow

```
User plays item
  ↓
PlayerRoute → JsProviderInstance.getPlaybackSpec(itemRef)
  ↓
  calls TS getPlaybackSpec → spider.play() → raw M3U8 URL
  ↓
  TS: fetch M3U8, applyHosts(), regex filter, resolve relative URLs
  TS: return { url: "stream://m3u8/{itemRef}", content: cleanedText }
  ↓
  Kotlin: detects "stream://m3u8/" prefix in URL
  Kotlin: registrar.registerStream(this, itemRef) → "http://127.0.0.1:7920/stream/{token}"
  Kotlin: replaces URL in PlaybackSpec
  ↓
ExoPlayer → GET http://127.0.0.1:7920/stream/{token}
  ↓
StreamProxy: entry.instance.openStream(itemRef)
  ↓
Kotlin: JsProviderInstance.openStream() → engine.callMethod("openStream", { itemRef })
  ↓
TS: openStream({ itemRef }) → re-fetch + re-filter → return { content: cleanedText }
  ↓
Kotlin: wraps in InMemoryStream : ProviderStream
  ↓
StreamProxy: reads bytes via readAt(), serves to ExoPlayer
```

Key: `openStream` is called fresh on each HTTP request (StreamProxy already does this), so live/chunked manifests are re-filtered on each ExoPlayer poll.

---

## Implementation Steps

### Step 1: TS — Add `openStream` to bridge contract

**`providers-ts/utils/types.ts`**

Add to `OpenTuneProviderBridge`:
```typescript
openStream?(args: { itemRef: string }): Promise<{ content: string } | null>;
```

Also add `HlsRule` to config types if not present:
```typescript
export interface HlsRule {
  name:   string;
  hosts:  string[];
  regex:  string[];
}
```

And update `CatVodConfig` in `config.ts`:
```typescript
export interface CatVodConfig {
  spider?: string;
  sites:   SiteEntry[];
  lives?:  LiveEntry[];
  hosts?:  string[];
  rules?:  HlsRule[];
}
```

### Step 2: TS — Implement M3U8 fetching + filtering

**`providers-ts/providers/catvod/instance.ts`**

Add a `purifyM3u8` helper:
```typescript
async function purifyM3u8(
  rawUrl: string,
  hosts: string[] | undefined,
  rules: HlsRule[] | undefined,
): Promise<string> {
  // 1. Fetch raw M3U8 text
  const resp = await host.http.get({ url: rawUrl });
  let text = resp.body;

  // 2. Apply hosts remapping
  const hostMap = buildHostMap(hosts);
  text = applyHostsToText(text, hostMap);

  // 3. Apply regex ad filtering (only for rules matching the URL hostname)
  const matchedRules = matchRules(rawUrl, rules);
  for (const rule of matchedRules) {
    for (const pattern of rule.regex) {
      try { text = text.replace(new RegExp(pattern, 'g'), ''); } catch {}
    }
  }

  // 4. Resolve relative URLs to absolute
  text = resolveRelativeUrls(text, rawUrl);

  return text;
}
```

Helper functions:
- `buildHostMap` — split `host=target` pairs into a Map (extracted from existing `applyHosts`)
- `applyHostsToText` — find URLs in M3U8 text and remap hostnames
- `matchRules` — extract hostname from URL, filter rules where `hosts` contains or matches it
- `resolveRelativeUrls` — prepend base URL to relative `.ts`/`.m3u8` paths

Add `openStream` to the TS provider:
```typescript
export async function openStream(
  state: CatVodState,
  itemRef: string,
): Promise<{ content: string } | null> {
  // Re-resolve the playback URL, then purify
  const spec = await getPlaybackSpec(state, itemRef, 0);
  if (!isM3u8(spec.url)) return null;
  const content = await purifyM3u8(spec.url, state.config.hosts, state.config.rules);
  return { content };
}

function isM3u8(url: string): boolean {
  return url.includes('.m3u8') || url.includes('m3u8?');
}
```

### Step 3: Kotlin — Add `InMemoryStream` wrapper

**New file: `content/providers/js/src/main/java/com/opentune/provider/js/InMemoryStream.kt`**

```kotlin
class InMemoryStream(private val data: ByteArray) : ProviderStream {
    override suspend fun getSize(): Long = data.size.toLong()

    override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return 0
        val available = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, available)
        return available
    }

    override fun close() {}
}
```

### Step 4: Kotlin — Override `openStream` in `JsProviderInstance`

**`JsProviderInstance.kt`** — add after `getPlaybackSpec`:

```kotlin
override suspend fun openStream(itemRef: String): ProviderStream? {
    ensureReady()
    val argsJson = buildJsonObject {
        put("itemRef", itemRef)
    }.toString()
    val resultJson = engine.callMethod("openStream", argsJson) ?: return null
    val obj = json.parseToJsonElement(resultJson).jsonObject
    val content = obj["content"]?.jsonPrimitive?.content ?: return null
    return InMemoryStream(content.encodeToByteArray())
}
```

### Step 5: Kotlin — Intercept `stream://m3u8/` URL in `getPlaybackSpec`

**`JsProviderInstance.kt`** — modify `parsePlaybackSpec` or wrap the result in `getPlaybackSpec`:

After getting the parsed `PlaybackSpec`, check if URL starts with `"stream://m3u8/"`:

```kotlin
override suspend fun getPlaybackSpec(itemRef: String, startMs: Long): PlaybackSpec {
    ensureReady()
    // ... existing code to get resultJson ...
    val spec = parsePlaybackSpec(json.parseToJsonElement(resultJson).jsonObject)

    // If TS returned a stream:// URL, register with StreamProxy
    if (spec.url.startsWith("stream://m3u8/")) {
        val registrar = StreamRegistrarHolder.get()
        val proxyUrl = registrar.registerStream(this, itemRef)
        return spec.copy(url = proxyUrl)
    }
    return spec
}
```

Add import:
```kotlin
import com.opentune.content.contract.StreamRegistrarHolder
```

### Step 6: TS — Wire into `getPlaybackSpec`

**`providers-ts/providers/catvod/instance.ts`** — modify `getPlaybackSpec`:

After `applyHosts`, check if the URL is an M3U8 that needs rule-based filtering:

```typescript
const spec = applyHosts(await dispatchPlay(...), state.config.hosts);

// Check if rules apply to this URL
const matchedRules = matchRules(spec.url, state.config.rules);
if (isM3u8(spec.url) && matchedRules.length > 0) {
  // Return stream:// URL so Kotlin routes through StreamProxy
  return { ...spec, url: `stream://m3u8/${encodeURIComponent(itemRef)}` };
}
return spec;
```

This means `purifyM3u8` is NOT called during `getPlaybackSpec` — the actual filtering is deferred to `openStream`, which is called fresh on each request (important for live manifests).

---

## Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| **Where filtering runs** | `openStream` (each request) | Live manifests need re-filtering on each ExoPlayer poll |
| **`stream://` URL convention** | Prefix signals "register with StreamProxy" | No new API surface; reuse existing registrar |
| **`openStream` is optional** | TS bridge uses `openStream?` | Not all providers need streaming |
| **Only `.m3u8` URLs proxied** | `isM3u8()` check | Direct file URLs don't need cleaning |
| **No Ktor route changes** | Reuse `StreamProxy` | Zero server-side changes needed |
