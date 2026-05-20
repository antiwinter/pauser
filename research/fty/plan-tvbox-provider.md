# Plan: TVBox Protocol Provider (TypeScript)

**Goal:** A JS provider (`providers-ts/providers/tvbox/`) that implements the full TVBox/CatVod protocol, exposing all site types (苹果CMS HTTP, drpy2 JS spiders, JAR spiders, IPTV M3U) as a unified OpenTune provider.

---

## Overview

The user configures a TVBox subscription URL (e.g. `http://www.饭太硬.cc/tv`). The provider:
1. Fetches and decodes the config JSON (including JPEG-embedded base64)
2. Exposes all `sites` as top-level `Folder` entries
3. When the user browses into a site, delegates to the appropriate protocol handler
4. Maps TVBox's `VodItem` / `VodDetail` / `playerContent` to OpenTune contracts

```
listEntry(null)
  → fetch + decode config.json
  → return sites[] as Folder entries

listEntry("site:WoGGGuard")
  → dispatch to JAR spider handler
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
providers-ts/providers/tvbox/
├── index.ts          — OpenTune bridge (globalThis.opentuneProvider)
├── provider.ts       — getFieldsSpec, validateFields
├── instance.ts       — init, listEntry, search, getDetail, getPlaybackSpec
├── config.ts         — fetch + decode TVBox config.json (JPEG base64 unwrap)
├── ref.ts            — itemRef encoding/decoding
├── handlers/
│   ├── cms.ts        — 苹果CMS HTTP API handler (type 0/1/2)
│   ├── drpy.ts       — drpy2/drpy3 JS spider handler (type 4/9/10)
│   ├── jar.ts        — JAR spider handler (type 3, via host.jar/host.spider)
│   └── iptv.ts       — IPTV M3U live handler
└── mapper.ts         — VodItem/VodDetail → EntryInfo/EntryDetail
```

---

## Step 1 — Config Fetching (`config.ts`)

The TVBox config endpoint returns JSON embedded in a JPEG (base64 in the image data).

```typescript
export interface TvBoxConfig {
  spider?: string;           // "url;md5;hash" or plain URL
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
  type: number;              // 0/1/2 = CMS, 3 = JAR, 4/9 = drpy2, 10 = drpy3
  api: string;               // URL or class name
  ext?: string | Record<string, unknown>;
  searchable?: 0 | 1;
  quickSearch?: 0 | 1;
  changeable?: 0 | 1;
  timeout?: number;
  playerType?: number | string;
  indexs?: 0 | 1;
  style?: { type: string; ratio?: number };
}

export async function fetchConfig(url: string): Promise<TvBoxConfig> {
  const resp = await host.http.get({ url });
  const body = resp.body;

  // Try direct JSON parse first
  try {
    return JSON.parse(body);
  } catch {}

  // Try base64 extraction from JPEG/BMP (FTY-style embedding)
  const b64Match = body.match(/[A-Za-z0-9+/]{200,}={0,2}/);
  if (b64Match) {
    try {
      const decoded = atob(b64Match[0]);
      return JSON.parse(decoded);
    } catch {}
  }

  throw new Error(`Cannot parse TVBox config from ${url}`);
}

export function parseSpiderField(spider?: string): { url: string; md5?: string } | null {
  if (!spider) return null;
  const parts = spider.split(';md5;');
  return { url: parts[0], md5: parts[1] };
}
```

---

## Step 2 — Item Ref Encoding (`ref.ts`)

All OpenTune item refs are opaque strings. We encode TVBox location as structured refs.

```typescript
// Ref formats:
// "site:{key}"                                    → site root (homeContent)
// "site:{key}/cat:{tid}"                          → category root
// "site:{key}/cat:{tid}/pg:{n}"                   → category page
// "site:{key}/vod:{id}"                           → vod detail
// "site:{key}/vod:{id}/flag:{flag}/ep:{epUrl}"    → episode playback
// "live:{name}"                                   → IPTV channel

export interface SiteRef { type: 'site'; key: string }
export interface CatRef  { type: 'cat';  key: string; tid: string; pg: number }
export interface VodRef  { type: 'vod';  key: string; id: string }
export interface EpRef   { type: 'ep';   key: string; id: string; flag: string; epUrl: string }
export interface LiveRef { type: 'live'; name: string; url: string }

export type TvBoxRef = SiteRef | CatRef | VodRef | EpRef | LiveRef

export function encodeRef(ref: TvBoxRef): string {
  return JSON.stringify(ref);
}

export function decodeRef(s: string): TvBoxRef {
  return JSON.parse(s);
}
```

---

## Step 3 — CMS Handler (`handlers/cms.ts`)

Handles type 0/1/2 sites — plain HTTP API.

```typescript
export async function cmsHome(api: string): Promise<EntryList> {
  const resp = await host.http.get({ url: `${api}?ac=list` });
  const data = JSON.parse(resp.body);
  const categories: EntryInfo[] = (data.class ?? []).map((c: any) => ({
    id: encodeRef({ type: 'cat', key: '?', tid: String(c.type_id), pg: 1 }),
    title: c.type_name,
    type: 'Folder' as EntryType,
    cover: null,
  }));
  return { items: categories, totalCount: categories.length };
}

export async function cmsCategory(
  api: string, tid: string, pg: number, limit: number
): Promise<EntryList> {
  const resp = await host.http.get({
    url: `${api}?ac=list&t=${tid}&pg=${pg}`
  });
  const data = JSON.parse(resp.body);
  return {
    items: (data.list ?? []).map(vodItemToEntry),
    totalCount: data.total ?? 0,
  };
}

export async function cmsDetail(api: string, id: string): Promise<EntryDetail> {
  const resp = await host.http.get({ url: `${api}?ac=detail&ids=${id}` });
  const data = JSON.parse(resp.body);
  return vodDetailToEntryDetail(data.list?.[0]);
}

export async function cmsSearch(api: string, keyword: string, pg: number): Promise<EntryList> {
  const resp = await host.http.get({
    url: `${api}?ac=list&wd=${encodeURIComponent(keyword)}&pg=${pg}`
  });
  const data = JSON.parse(resp.body);
  return { items: (data.list ?? []).map(vodItemToEntry), totalCount: data.total ?? 0 };
}
```

---

## Step 4 — drpy2 Handler (`handlers/drpy.ts`)

Handles type 4/9/10 sites — JS spider files run via drpy2 engine.

The drpy2 engine requires a bootstrap environment. We pre-load it before calling spider methods.

```typescript
// drpy2 bootstrap is pre-bundled as a string constant (built at compile time)
import DRPY2_BUNDLE from './drpy2-bundle.js';  // pre-built IIFE string

let drpyInitialized = false;

async function ensureDrpy() {
  if (drpyInitialized) return;
  // The drpy2 bundle + all its asset dependencies are pre-loaded
  // via host.drpy.eval() — a new host namespace that evals JS in a
  // separate QuickJS context dedicated to drpy
  // (or we can eval it in the same context before the provider bundle)
  drpyInitialized = true;
}

export async function drpyHome(apiUrl: string, extUrl: string): Promise<EntryList> {
  await ensureDrpy();
  // Fetch the spider rule JS file
  const ruleResp = await host.http.get({ url: apiUrl });
  // init() the drpy engine with the rule
  // home() returns JSON string
  const homeJson = await host.drpy.call({ method: 'home', ruleJs: ruleResp.body });
  const data = JSON.parse(homeJson);
  return { items: data.class.map(classToFolder), totalCount: data.class.length };
}
```

**Note:** drpy2 support requires the host API extensions from the drpy2 plan (dom parsing, KV store, charset). This handler is a stub until those are implemented.

---

## Step 5 — JAR Handler (`handlers/jar.ts`)

Handles type 3 sites — delegates to `host.jar` / `host.spider`.

```typescript
let jarLoaded = false;

async function ensureJar(spiderUrl: string, md5: string | undefined,
                          spiderKey: string, ext: string) {
  if (jarLoaded) return;
  await host.jar.load({ url: spiderUrl, md5, spiderKey, ext });
  jarLoaded = true;
}

export async function jarHome(
  spiderUrl: string, md5: string | undefined,
  spiderKey: string, ext: string, filter: boolean
): Promise<EntryList> {
  await ensureJar(spiderUrl, md5, spiderKey, ext);
  const raw = await host.spider.homeContent({ filter });
  const data = JSON.parse(raw);
  const categories: EntryInfo[] = (data.class ?? []).map((c: any) => ({
    id: encodeRef({ type: 'cat', key: spiderKey, tid: String(c.type_id), pg: 1 }),
    title: c.type_name,
    type: 'Folder' as EntryType,
    cover: null,
  }));
  return { items: categories, totalCount: categories.length };
}

export async function jarCategory(
  spiderKey: string, tid: string, pg: number,
  filter: boolean, extend: Record<string, string>
): Promise<EntryList> {
  const raw = await host.spider.categoryContent({
    tid, pg: String(pg), filter, extend
  });
  const data = JSON.parse(raw);
  return {
    items: (data.list ?? []).map(vodItemToEntry),
    totalCount: data.total ?? 999,
  };
}

export async function jarDetail(spiderKey: string, id: string): Promise<EntryDetail> {
  const raw = await host.spider.detailContent({ ids: [id] });
  const data = JSON.parse(raw);
  return vodDetailToEntryDetail(data.list?.[0]);
}

export async function jarPlay(
  flag: string, epUrl: string, vipFlags: string[]
): Promise<PlaybackSpec> {
  const raw = await host.spider.playerContent({ flag, id: epUrl, vipFlags });
  const data = JSON.parse(raw);
  return {
    url: data.url,
    headers: data.header ?? {},
    mimeType: data.type ?? null,
    title: '',
    durationMs: null,
    subtitleTracks: [],
    hooksState: {},
  };
}
```

---

## Step 6 — IPTV Handler (`handlers/iptv.ts`)

Handles `lives[]` entries — M3U playlists.

```typescript
export async function fetchLiveChannels(lives: LiveEntry[]): Promise<EntryList> {
  const items: EntryInfo[] = [];
  for (const live of lives) {
    const resp = await host.http.get({ url: live.url });
    const channels = parseM3U(resp.body);
    for (const ch of channels) {
      items.push({
        id: encodeRef({ type: 'live', name: ch.name, url: ch.url }),
        title: ch.name,
        type: 'Playable',
        cover: ch.logo ?? null,
      });
    }
  }
  return { items, totalCount: items.length };
}

function parseM3U(content: string): Array<{ name: string; url: string; logo?: string }> {
  const lines = content.split('\n');
  const channels = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line.startsWith('#EXTINF:')) {
      const nameMatch = line.match(/,(.+)$/);
      const logoMatch = line.match(/tvg-logo="([^"]+)"/);
      const url = lines[i + 1]?.trim();
      if (nameMatch && url && !url.startsWith('#')) {
        channels.push({ name: nameMatch[1], url, logo: logoMatch?.[1] });
      }
    }
  }
  return channels;
}
```

---

## Step 7 — Mapper (`mapper.ts`)

Maps TVBox VodItem/VodDetail to OpenTune contracts.

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
  // Parse vod_play_from / vod_play_url into episode list
  const sources = (item.vod_play_from ?? '').split('$$$');
  const urlGroups = (item.vod_play_url ?? '').split('$$$');

  // Build externalUrls from episode list (each episode = one ExternalUrl)
  // The actual episode selection happens in getPlaybackSpec
  const externalUrls: ExternalUrl[] = sources.flatMap((src: string, i: number) => {
    const eps = (urlGroups[i] ?? '').split('#');
    return eps.map((ep: string) => {
      const [epName, epUrl] = ep.split('$');
      return { name: `${src} / ${epName}`, url: epUrl ?? '' };
    });
  });

  return {
    title: item.vod_name ?? '',
    overview: item.vod_content ?? item.vod_blurb ?? null,
    logo: null,
    backdrop: item.vod_pic ? [item.vod_pic] : [],
    isMedia: true,
    rating: null,
    bitrate: null,
    externalUrls,
    year: item.vod_year ? parseInt(item.vod_year) : null,
    providerIds: {},
    streams: [],
    etag: null,
  };
}
```

---

## Step 8 — Instance (`instance.ts`)

The main dispatch logic.

```typescript
export async function listEntry(
  config: TvBoxConfig,
  location: string | null,
  startIndex: number,
  limit: number,
): Promise<EntryList> {
  // Root: show all sites + live section
  if (location === null) {
    const siteEntries: EntryInfo[] = config.sites.map(site => ({
      id: encodeRef({ type: 'site', key: site.key }),
      title: site.name,
      type: 'Folder',
      cover: null,
    }));
    if (config.lives?.length) {
      siteEntries.push({
        id: 'live:__all__',
        title: '📺 直播',
        type: 'Folder',
        cover: null,
      });
    }
    return { items: siteEntries, totalCount: siteEntries.length };
  }

  const ref = decodeRef(location);

  if (ref.type === 'site') {
    const site = config.sites.find(s => s.key === ref.key)!;
    return dispatchHome(config, site);
  }

  if (ref.type === 'cat') {
    const site = config.sites.find(s => s.key === ref.key)!;
    return dispatchCategory(config, site, ref.tid, ref.pg, limit);
  }

  if (ref.type === 'live') {
    return fetchLiveChannels(config.lives ?? []);
  }

  return { items: [], totalCount: 0 };
}

function dispatchHome(config: TvBoxConfig, site: SiteEntry): Promise<EntryList> {
  const spiderInfo = parseSpiderField(config.spider);
  const ext = typeof site.ext === 'string' ? site.ext : JSON.stringify(site.ext ?? {});

  if (site.type === 3) {
    // JAR spider
    return jarHome(spiderInfo!.url, spiderInfo?.md5, site.api, ext, false);
  } else if (site.type === 4 || site.type === 9 || site.type === 10) {
    // drpy2/3 JS spider
    return drpyHome(site.api, ext);
  } else {
    // CMS HTTP API (type 0/1/2)
    return cmsHome(site.api);
  }
}
```

---

## Step 9 — Provider Fields & Validation (`provider.ts`)

```typescript
export function getFieldsSpec(): ProviderFieldSpec[] {
  return [
    {
      id: 'config_url',
      labelKey: 'tvbox.field.config_url',
      kind: 'singleLine',
      required: true,
      order: 0,
      placeholderKey: 'tvbox.field.config_url.placeholder',
    },
  ];
}

export async function validateFields(
  values: Record<string, string>
): Promise<ValidationResult> {
  try {
    const url = values['config_url'] ?? '';
    if (!url) throw new Error('Config URL is required');
    const config = await fetchConfig(url);
    if (!config.sites?.length) throw new Error('No sites found in config');

    const hash = await host.crypto.sha256({ input: url });
    const name = `TVBox (${config.sites.length} sources)`;
    return { success: true, hash, name, fields: { config_url: url } };
  } catch (e) {
    return { success: false, error: e instanceof Error ? e.message : String(e) };
  }
}
```

---

## Step 10 — Index (`index.ts`)

```typescript
import { getFieldsSpec, validateFields } from './provider.js';
import { fetchConfig } from './config.js';
import { listEntry, search, getDetail, getPlaybackSpec } from './instance.js';

let config: TvBoxConfig | null = null;

(globalThis as any).opentuneProvider = {
  providesArt: true,

  async getFieldsSpec() { return getFieldsSpec(); },
  async validateFields(args) { return validateFields(args.values); },

  async init(args) {
    config = await fetchConfig(args.credentials['config_url']);
  },

  async listEntry(args) {
    return listEntry(config!, args.location, args.startIndex, args.limit);
  },

  async search(args) {
    return search(config!, args.scopeLocation, JSON.parse(args.query));
  },

  async getDetail(args) {
    return getDetail(config!, args.itemRef);
  },

  async getPlaybackSpec(args) {
    return getPlaybackSpec(config!, args.itemRef, args.startMs);
  },

  async onPlaybackReady() {},
  async onProgressTick() {},
  async onStop() {},
};
```

---

## Rollup Config Addition

Add `tvbox` to the providers list — it will be auto-discovered since `providers/tvbox/index.ts` exists.

---

## Phased Delivery

### Phase 1 — CMS + IPTV (no new host APIs needed) — ~3 days

- `config.ts` (fetch + JPEG base64 decode)
- `handlers/cms.ts` (苹果CMS HTTP API)
- `handlers/iptv.ts` (M3U live channels)
- `mapper.ts`, `ref.ts`
- `provider.ts`, `instance.ts`, `index.ts`
- Works with any type 0/1/2 site and all `lives[]` entries

### Phase 2 — JAR Spider support — ~3.5 days (depends on `host.loadJar` plan)

- `handlers/jar.ts`
- Requires `host.jar.*` and `host.spider.*` from the `host.loadJar` plan
- Unlocks all `csp_*` type 3 sites (FTY's 49 sites)

### Phase 3 — drpy2 JS spider support — ~1–2 weeks

- `handlers/drpy.ts`
- Requires drpy2 host API extensions (dom parsing, KV store, charset)
- Unlocks type 4/9/10 sites and the broader drpy2 ecosystem

---

## Files to Create

| File | Notes |
|------|-------|
| `providers-ts/providers/tvbox/index.ts` | Entry point |
| `providers-ts/providers/tvbox/provider.ts` | Fields + validation |
| `providers-ts/providers/tvbox/instance.ts` | Core dispatch |
| `providers-ts/providers/tvbox/config.ts` | Config fetch + decode |
| `providers-ts/providers/tvbox/ref.ts` | ItemRef encoding |
| `providers-ts/providers/tvbox/mapper.ts` | VodItem → EntryInfo |
| `providers-ts/providers/tvbox/handlers/cms.ts` | 苹果CMS HTTP |
| `providers-ts/providers/tvbox/handlers/iptv.ts` | M3U live |
| `providers-ts/providers/tvbox/handlers/jar.ts` | JAR spider |
| `providers-ts/providers/tvbox/handlers/drpy.ts` | drpy2 JS spider |

---

## Estimated Effort

| Phase | Effort |
|-------|--------|
| Phase 1 (CMS + IPTV) | 3 days |
| Phase 2 (JAR spiders) | 3.5 days + host.loadJar plan |
| Phase 3 (drpy2) | 1–2 weeks + drpy2 host extensions |
| **Total (all phases)** | **~4–5 weeks** |
