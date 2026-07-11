# Plan: CatVod Protocol Provider (TypeScript)

## Current Status

> **Superseded by `.claude/plans/jar-loader-agnostic-split.md`.** This document was the original "build the catvod provider" plan; everything it described is now implemented. The API shapes referenced in the example code below (`host.jar.load({ url, md5 })`, `host.crypto.sha256`) are pre-refactor; the active contract is in `providers-ts/utils/types.ts` — see `host.jar.load({ source: { url | path | buffer } })`, `host.crypto.checksum({ input, algo, encoding? })`, and the generic primitives `loadClass` / `registerLoader` / `adoptParent`.

**Nothing implemented.** All 10 provider files are missing. `host.jar` and `host.eval` namespaces are not yet in `types.ts` or wired in Kotlin. Phase 1 can start immediately; Phase 2 requires `plan-host-load-jar.md` to be implemented first.

## Goal

A JS provider (`providers-ts/providers/catvod/`) that implements the CatVod protocol, exposing all site types (苹果CMS HTTP, drpy2 JS spiders, JAR spiders, IPTV M3U) as a unified OpenTune provider.

---

## Overview

The user configures a CatVod subscription URL. The provider:
1. Fetches and decodes the config JSON (including JPEG-embedded base64)
2. Exposes all `sites` as top-level `Folder` entries
3. When the user browses into a site, delegates to the appropriate protocol handler
4. Maps CatVod's `VodItem` / `VodDetail` response format to OpenTune contracts

```
listEntry(null)
  → fetch + decode config.json
  → return sites[] as Folder entries

listEntry("site:WoGGGuard")
  → dispatch to JAR handler
  → homeContent() → categories as Folder entries

listEntry("site:WoGGGuard/cat:1/pg:2")
  → categoryContent(tid="1", pg="2") → VodItem[] as Playable/Series entries

getDetail("site:WoGGGuard/vod:12345")
  → detailContent(["12345"]) → EntryDetail with episode list

getPlaybackSpec("site:WoGGGuard/vod:12345/flag:线路1/ep:EP1$https://...")
  → playerContent(flag, id) → PlaybackSpec
```

---

## Provider Structure

```
providers-ts/providers/catvod/
├── index.ts          — OpenTune bridge (globalThis.opentuneProvider)
├── provider.ts       — getFieldsSpec, validateFields
├── instance.ts       — init, listEntry, search, getDetail, getPlaybackSpec
├── config.ts         — fetch + decode CatVod config.json (JPEG base64 unwrap)
├── ref.ts            — itemRef encoding/decoding
├── handlers/
│   ├── cms.ts        — 苹果CMS HTTP API handler (type 0/1/2)
│   ├── drpy.ts       — drpy2/drpy3 JS spider handler (type 4/9/10)
│   ├── jar.ts        — JAR spider handler (type 3, via host.jar.reflect)
│   └── iptv.ts       — IPTV M3U live handler
└── mapper.ts         — VodItem/VodDetail → EntryInfo/EntryDetail
```

---

## Step 1 — Config Fetching (`config.ts`)

The CatVod config endpoint may return JSON embedded in a JPEG (base64 in the image data).

```typescript
export interface CatVodConfig {
  spider?: string;           // "url;md5;hash" or plain URL — JAR for csp_* sites
  sites: SiteEntry[];
  lives?: LiveEntry[];
  rules?: AdFilterRule[];
  logo?: string;
  hosts?: string[];
  wallpaper?: string;
}

export interface SiteEntry {
  key: string;
  name: string;
  type: number;              // 0/1/2 = CMS HTTP, 3 = JAR, 4/9 = drpy2, 10 = drpy3
  api: string;               // HTTP URL (type 0/1/2/4/9/10) or class name (type 3)
  ext?: string | Record<string, unknown>;
  searchable?: 0 | 1;
  quickSearch?: 0 | 1;
  changeable?: 0 | 1;
  timeout?: number;
  playerType?: number | string;
  indexs?: 0 | 1;
  style?: { type: string; ratio?: number };
}

export interface LiveEntry {
  name: string;
  type: 0;
  url: string;
  playerType: number;
  epg?: string;
  logo?: string;
  ua?: string;
  timeout?: number;
}

export async function fetchConfig(url: string): Promise<CatVodConfig> {
  const resp = await host.http.get({ url });
  const body = resp.body;

  // Try direct JSON parse first
  try { return JSON.parse(body); } catch {}

  // Try base64 extraction from JPEG/BMP embedding
  const b64Match = body.match(/[A-Za-z0-9+/]{200,}={0,2}/);
  if (b64Match) {
    try { return JSON.parse(atob(b64Match[0])); } catch {}
  }

  throw new Error(`Cannot parse CatVod config from ${url}`);
}

export function parseSpiderField(spider?: string): { url: string; md5?: string } | null {
  if (!spider) return null;
  const parts = spider.split(';md5;');
  return { url: parts[0], md5: parts[1] };
}
```

---

## Step 2 — Item Ref Encoding (`ref.ts`)

```typescript
// Ref formats:
// "site:{key}"                                  → site root (homeContent)
// "site:{key}/cat:{tid}/pg:{n}"                 → category page
// "site:{key}/vod:{id}"                         → vod detail
// "site:{key}/vod:{id}/flag:{flag}/ep:{epUrl}"  → episode playback
// "live:{name}"                                 → IPTV channel

export interface SiteRef { type: 'site'; key: string }
export interface CatRef  { type: 'cat';  key: string; tid: string; pg: number }
export interface VodRef  { type: 'vod';  key: string; id: string }
export interface EpRef   { type: 'ep';   key: string; id: string; flag: string; epUrl: string }
export interface LiveRef { type: 'live'; name: string; url: string }

export type CatVodRef = SiteRef | CatRef | VodRef | EpRef | LiveRef

export const encodeRef = (ref: CatVodRef): string => JSON.stringify(ref);
export const decodeRef = (s: string): CatVodRef => JSON.parse(s);
```

---

## Step 3 — CMS Handler (`handlers/cms.ts`)

Handles type 0/1/2 sites — plain 苹果CMS HTTP API.

```typescript
export async function cmsHome(api: string): Promise<EntryList> {
  const data = JSON.parse((await host.http.get({ url: `${api}?ac=list` })).body);
  return {
    items: (data.class ?? []).map((c: any) => ({
      id: encodeRef({ type: 'cat', key: '', tid: String(c.type_id), pg: 1 }),
      title: c.type_name,
      type: 'Folder' as EntryType,
      cover: null,
    })),
    totalCount: data.class?.length ?? 0,
  };
}

export async function cmsCategory(api: string, tid: string, pg: number): Promise<EntryList> {
  const data = JSON.parse((await host.http.get({ url: `${api}?ac=list&t=${tid}&pg=${pg}` })).body);
  return { items: (data.list ?? []).map(vodItemToEntry), totalCount: data.total ?? 0 };
}

export async function cmsDetail(api: string, id: string): Promise<EntryDetail> {
  const data = JSON.parse((await host.http.get({ url: `${api}?ac=detail&ids=${id}` })).body);
  return vodDetailToEntryDetail(data.list?.[0]);
}

export async function cmsSearch(api: string, keyword: string, pg: number): Promise<EntryList> {
  const data = JSON.parse((await host.http.get({
    url: `${api}?ac=list&wd=${encodeURIComponent(keyword)}&pg=${pg}`
  })).body);
  return { items: (data.list ?? []).map(vodItemToEntry), totalCount: data.total ?? 0 };
}
```

---

## Step 4 — JAR Handler (`handlers/jar.ts`)

**Status: implemented** at `providers-ts/providers/catvod/spider/jar.ts`. See the superseded notice at the top — the example code below uses the pre-refactor `host.jar.load({ url, md5 })` shape; the live implementation uses `host.jar.load({ source: { url | path } })` + `host.crypto.checksum({ input, algo: 'md5', encoding: 'base64' })` for verification, then composes generic primitives (`loadClass` → `reflect(Init.init)` → poll `reflect(Init.loader)` → `registerLoader` → `adoptParent` → `reflect(InitOrigin.init)`) for the catvod boot dance.

```typescript
// Spider instance handles, keyed by siteKey
const spiderHandles = new Map<string, string>();

async function ensureJar(jarUrl: string, md5?: string) {
  await host.jar.load({ url: jarUrl, md5 });
  // Bootstrap: call Init.init() if present (decrypts encrypted JARs)
  await host.jar.reflect({
    url: jarUrl,
    cls: 'com.github.catvod.spider.Init',
    method: 'init',
    args: [],
  }).catch(() => { /* not all JARs have Init */ });
}

async function getSpider(jarUrl: string, api: string, ext: string, siteKey: string): Promise<string> {
  if (spiderHandles.has(siteKey)) return spiderHandles.get(siteKey)!;
  const cls = `com.github.catvod.spider.${api.replace('csp_', '')}`;
  // newInstance() then init(ext) — reflect returns opaque handle for objects
  const handle = await host.jar.reflect({ url: jarUrl, cls, method: 'newInstance', args: [] });
  await host.jar.reflect({ url: jarUrl, cls, method: 'init', instance: handle, args: [ext] });
  spiderHandles.set(siteKey, handle);
  return handle;
}

export async function jarHome(jarUrl: string, md5: string | undefined,
                               api: string, ext: string, siteKey: string): Promise<EntryList> {
  await ensureJar(jarUrl, md5);
  const handle = await getSpider(jarUrl, api, ext, siteKey);
  const cls = `com.github.catvod.spider.${api.replace('csp_', '')}`;
  const raw = await host.jar.reflect({ url: jarUrl, cls, method: 'homeContent',
                                        instance: handle, args: [false] });
  const data = JSON.parse(raw);
  return {
    items: (data.class ?? []).map((c: any) => ({
      id: encodeRef({ type: 'cat', key: siteKey, tid: String(c.type_id), pg: 1 }),
      title: c.type_name, type: 'Folder' as EntryType, cover: null,
    })),
    totalCount: data.class?.length ?? 0,
  };
}

export async function jarCategory(jarUrl: string, api: string, siteKey: string,
                                   tid: string, pg: number): Promise<EntryList> {
  const handle = await getSpider(jarUrl, api, '', siteKey);
  const cls = `com.github.catvod.spider.${api.replace('csp_', '')}`;
  const raw = await host.jar.reflect({ url: jarUrl, cls, method: 'categoryContent',
                                        instance: handle, args: [tid, String(pg), false, {}] });
  const data = JSON.parse(raw);
  return { items: (data.list ?? []).map(vodItemToEntry), totalCount: data.total ?? 0 };
}

export async function jarDetail(jarUrl: string, api: string,
                                 siteKey: string, id: string): Promise<EntryDetail> {
  const handle = await getSpider(jarUrl, api, '', siteKey);
  const cls = `com.github.catvod.spider.${api.replace('csp_', '')}`;
  const raw = await host.jar.reflect({ url: jarUrl, cls, method: 'detailContent',
                                        instance: handle, args: [[id]] });
  return vodDetailToEntryDetail(JSON.parse(raw).list?.[0]);
}

export async function jarPlay(jarUrl: string, api: string, siteKey: string,
                               flag: string, epUrl: string): Promise<PlaybackSpec> {
  const handle = await getSpider(jarUrl, api, '', siteKey);
  const cls = `com.github.catvod.spider.${api.replace('csp_', '')}`;
  const raw = await host.jar.reflect({ url: jarUrl, cls, method: 'playerContent',
                                        instance: handle, args: [flag, epUrl, []] });
  const data = JSON.parse(raw);
  return { url: data.url, headers: data.header ?? {}, mimeType: data.type ?? null,
           title: '', durationMs: null, subtitleTracks: [], hooksState: {} };
}
```

---

## Step 5 — drpy2 Handler (`handlers/drpy.ts`)

Handles type 4/9/10 sites. Requires `host.eval.script` and sync `_http` global from `plan-host-load-jar.md`. No JS libraries are bundled — fetched at runtime via `host.eval.script()` and cached for the session.

### Assets loaded at runtime

| Asset | Source | Purpose |
|-------|--------|---------|
| `http.js` | FongMi GitHub raw | `req()` wrapper over `_http` |
| `crypto-js.js` | FongMi GitHub raw | `CryptoJS` global |
| `gbk.js` | FongMi GitHub raw | `gbkTool` global |
| `cat.js` | FongMi GitHub raw | drpy2 runtime + `pdfh/pdfa/pd` + DOM parsing |

`pdfh`, `pdfa`, `pd` are implemented inside `cat.js` using bundled cheerio — no separate host call needed.

Kotlin provides three globals on `globalThis` (injected before provider code runs, documented in `plan-host-load-jar.md`):
- `_http(url, options)` — **blocking** HTTP (drpy2 calls HTTP synchronously)
- `local.get/set/delete` — per-engine KV store
- `setTimeout(fn, delay)` — timer

```typescript
const DRPY_ASSETS = [
  'https://raw.githubusercontent.com/FongMi/TV/main/quickjs/src/main/assets/js/lib/http.js',
  'https://raw.githubusercontent.com/FongMi/TV/main/quickjs/src/main/assets/js/lib/crypto-js.js',
  'https://raw.githubusercontent.com/FongMi/TV/main/quickjs/src/main/assets/js/lib/gbk.js',
  'https://raw.githubusercontent.com/FongMi/TV/main/quickjs/src/main/assets/js/lib/cat.js',
];

let assetsLoaded = false;

async function ensureAssets() {
  if (assetsLoaded) return;
  for (const url of DRPY_ASSETS) {
    await host.eval.script({ url });   // cached after first load
  }
  assetsLoaded = true;
}
```

**Note:** The exact drpy2 API surface (how `cat.js` exposes `init/home/category/detail/play/search`) must be confirmed by reading `cat.js` before implementing the body of each function. The handler is a stub in Phase 3.

---

## Step 6 — IPTV Handler (`handlers/iptv.ts`)

Handles `lives[]` entries — M3U playlists.

```typescript
export async function fetchLiveChannels(lives: LiveEntry[]): Promise<EntryList> {
  const items: EntryInfo[] = [];
  for (const live of lives) {
    const channels = parseM3U((await host.http.get({ url: live.url })).body);
    for (const ch of channels) {
      items.push({
        id: encodeRef({ type: 'live', name: ch.name, url: ch.url }),
        title: ch.name, type: 'Playable', cover: ch.logo ?? null,
      });
    }
  }
  return { items, totalCount: items.length };
}

function parseM3U(content: string) {
  const lines = content.split('\n');
  const channels: Array<{ name: string; url: string; logo?: string }> = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith('#EXTINF:')) continue;
    const name = line.match(/,(.+)$/)?.[1];
    const logo = line.match(/tvg-logo="([^"]+)"/)?.[1];
    const url  = lines[i + 1]?.trim();
    if (name && url && !url.startsWith('#')) channels.push({ name, url, logo });
  }
  return channels;
}
```

---

## Step 7 — Mapper (`mapper.ts`)

```typescript
export function vodItemToEntry(item: any): EntryInfo {
  return {
    id: item.vod_id,
    title: item.vod_name,
    type: 'Playable',
    cover: item.vod_pic ?? null,
    overview: item.vod_content ?? item.vod_blurb ?? null,
    communityRating: null,
    genres: item.type_name ? [item.type_name] : null,
    year: item.vod_year ? parseInt(item.vod_year) : null,
  };
}

export function vodDetailToEntryDetail(item: any): EntryDetail {
  // vod_play_from: "src1$$$src2"
  // vod_play_url:  "EP1$url1#EP2$url2$$$EP1$url1b#EP2$url2b"
  const sources   = (item.vod_play_from ?? '').split('$$$');
  const urlGroups = (item.vod_play_url  ?? '').split('$$$');

  const externalUrls: ExternalUrl[] = sources.flatMap((src: string, i: number) =>
    (urlGroups[i] ?? '').split('#').map((ep: string) => {
      const [epName, epUrl] = ep.split('$');
      return { name: `${src} / ${epName}`, url: epUrl ?? '' };
    })
  );

  return {
    title: item.vod_name ?? '',
    overview: item.vod_content ?? item.vod_blurb ?? null,
    logo: null,
    backdrop: item.vod_pic ? [item.vod_pic] : [],
    isMedia: true,
    rating: null, bitrate: null,
    externalUrls,
    year: item.vod_year ? parseInt(item.vod_year) : null,
    providerIds: {}, streams: [], etag: null,
  };
}
```

---

## Step 8 — Instance (`instance.ts`)

```typescript
export async function listEntry(config: CatVodConfig, location: string | null,
                                 startIndex: number, limit: number): Promise<EntryList> {
  if (location === null) {
    const items: EntryInfo[] = config.sites.map(site => ({
      id: encodeRef({ type: 'site', key: site.key }),
      title: site.name, type: 'Folder', cover: null,
    }));
    if (config.lives?.length) {
      items.push({ id: 'live:__all__', title: '直播', type: 'Folder', cover: null });
    }
    return { items, totalCount: items.length };
  }

  const ref = decodeRef(location);
  const jar = parseSpiderField(config.spider);

  if (ref.type === 'site') {
    const site = config.sites.find(s => s.key === ref.key)!;
    const ext  = typeof site.ext === 'string' ? site.ext : JSON.stringify(site.ext ?? {});
    if (site.type === 3)
      return jarHome(jar!.url, jar?.md5, site.api, ext, site.key);
    if (site.type === 4 || site.type === 9 || site.type === 10)
      return drpyHome(site.api, ext);
    return cmsHome(site.api);
  }

  if (ref.type === 'cat') {
    const site = config.sites.find(s => s.key === ref.key)!;
    if (site.type === 3)
      return jarCategory(jar!.url, site.api, ref.key, ref.tid, ref.pg);
    return cmsCategory(site.api, ref.tid, ref.pg);
  }

  if (ref.type === 'live') return fetchLiveChannels(config.lives ?? []);

  return { items: [], totalCount: 0 };
}
```

---

## Step 9 — Provider Fields & Validation (`provider.ts`)

```typescript
export function getFieldsSpec(): ProviderFieldSpec[] {
  return [{
    id: 'config_url', labelKey: 'catvod.field.config_url',
    kind: 'singleLine', required: true, order: 0,
    placeholderKey: 'catvod.field.config_url.placeholder',
  }];
}

export async function validateFields(values: Record<string, string>): Promise<ValidationResult> {
  try {
    const url = values['config_url'] ?? '';
    if (!url) throw new Error('Config URL is required');
    const config = await fetchConfig(url);
    if (!config.sites?.length) throw new Error('No sites found in config');
    const hash = await host.crypto.checksum({ input: url, algo: 'sha-256' });
    return { success: true, hash, name: `CatVod (${config.sites.length} sources)`, fields: { config_url: url } };
  } catch (e) {
    return { success: false, error: e instanceof Error ? e.message : String(e) };
  }
}
```

---

## Step 10 — Index (`index.ts`)

```typescript
import { getFieldsSpec, validateFields } from './provider.js';
import { fetchConfig, parseSpiderField, CatVodConfig } from './config.js';
import { listEntry, search, getDetail, getPlaybackSpec } from './instance.js';

let config: CatVodConfig | null = null;

(globalThis as any).opentuneProvider = {
  providesArt: true,
  async getFieldsSpec()        { return getFieldsSpec(); },
  async validateFields(args)   { return validateFields(args.values); },
  async init(args)             { config = await fetchConfig(args.credentials['config_url']); },
  async listEntry(args)        { return listEntry(config!, args.location, args.startIndex, args.limit); },
  async search(args)           { return search(config!, args.scopeLocation, JSON.parse(args.query)); },
  async getDetail(args)        { return getDetail(config!, args.itemRef); },
  async getPlaybackSpec(args)  { return getPlaybackSpec(config!, args.itemRef, args.startMs); },
  async onPlaybackReady()      {},
  async onProgressTick()       {},
  async onStop()               {},
};
```

---

## Phased Delivery

### Phase 1 — CMS + IPTV (no new host APIs needed)
Files: `config.ts`, `handlers/cms.ts`, `handlers/iptv.ts`, `mapper.ts`, `ref.ts`, `provider.ts`, `instance.ts`, `index.ts`

Works with type 0/1/2 sites and all `lives[]` entries. Can start immediately.

### Phase 2 — JAR spider support
Files: `handlers/jar.ts`

**Status: implemented.** The prerequisite (`plan-host-load-jar.md`, since superseded by `.claude/plans/jar-loader-agnostic-split.md`) is done. `JarLoader.kt` now exposes `load({ source })`, `reflect`, `loadClass`, `registerLoader`, `adoptParent`, `clear`, `clearInstances`. The catvod-specific orchestration (Init / DexNative / InitOrigin boot dance, including the 50 ms / 5 s polling) lives in `providers-ts/providers/catvod/spider/jar.ts`. Type 3 (`csp_*`) sites work end-to-end.

### Phase 3 — drpy2 JS spider support
Files: `handlers/drpy.ts` (full implementation)

**Prerequisites:**
- Phase 2 complete
- `EvalLoader.kt` — `host.eval.script`
- Sync `_http` global + `local` + `setTimeout` injected in `QuickJsEngine.kt`
- `providers-ts/utils/types.ts` — add `eval` namespace to `HostAPI`
- Read `cat.js` to confirm drpy2 API surface before implementing

Assets fetched from FongMi GitHub at runtime; nothing bundled in the provider. Unlocks type 4/9/10 sites.

---

## Files to Create

| File | Phase |
|------|-------|
| `providers-ts/providers/catvod/index.ts` | 1 |
| `providers-ts/providers/catvod/provider.ts` | 1 |
| `providers-ts/providers/catvod/instance.ts` | 1 |
| `providers-ts/providers/catvod/config.ts` | 1 |
| `providers-ts/providers/catvod/ref.ts` | 1 |
| `providers-ts/providers/catvod/mapper.ts` | 1 |
| `providers-ts/providers/catvod/handlers/cms.ts` | 1 |
| `providers-ts/providers/catvod/handlers/iptv.ts` | 1 |
| `providers-ts/providers/catvod/handlers/jar.ts` | 2 |
| `providers-ts/providers/catvod/handlers/drpy.ts` | 3 |

## Files to Modify (Phase 2+)

| File | Change |
|------|--------|
| `providers-ts/utils/types.ts` | Add `jar` namespace to `HostAPI` (Phase 2), `eval` namespace (Phase 3) |

*(All Kotlin-side changes are tracked in `plan-host-load-jar.md`.)*
