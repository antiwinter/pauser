# Plan: M3U IPTV Provider (TypeScript)

**Goal:** A JS provider (`providers-ts/providers/m3u/`) that parses M3U playlists and exposes channels as `Playable` entries, supporting per-channel HTTP headers and catchup attributes as found in APTV-format playlists.

---

## Overview

The user configures an M3U URL. The provider:
1. Fetches and parses the M3U playlist
2. Exposes channel groups as `Folder` entries at the root
3. Exposes individual channels as `Playable` entries within each group
4. Passes per-channel HTTP headers through to `PlaybackSpec`

No VOD, no search, no detail page — channels are flat `Playable` entries.

---

## Provider Structure

```
providers-ts/providers/m3u/
├── index.ts       — Insomnia bridge (globalThis.insomniaProvider)
├── provider.ts    — getFieldsSpec, validateFields
├── instance.ts    — init, listEntry, getPlaybackSpec
├── parser.ts      — M3U text → Channel[]
└── ref.ts         — itemRef encoding/decoding
```

---

## Step 1 — M3U Parser (`parser.ts`)

Parses standard M3U plus the APTV-style per-channel header attributes.

```typescript
export interface Channel {
  name:       string;
  url:        string;
  group:      string;
  tvgId?:     string;
  tvgLogo?:   string;
  userAgent?: string;
  referer?:   string;
  headers:    Record<string, string>;   // all http-header="Key=Value" entries
  catchup?:   CatchupInfo;
}

export interface CatchupInfo {
  mode:   string;    // e.g. "append"
  source: string;    // URL template with ${(b)...} / ${(e)...} tokens
}

export function parseM3U(content: string): Channel[] {
  const lines   = content.split('\n');
  const channels: Channel[] = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith('#EXTINF:')) continue;

    const url = lines[i + 1]?.trim();
    if (!url || url.startsWith('#')) continue;

    const headers: Record<string, string> = {};

    // Per-channel header shorthands
    const ua      = line.match(/\bhttp-user-agent="([^"]+)"/i)?.[1];
    const referer = line.match(/\bhttp-referer="([^"]+)"/i)?.[1];
    if (ua)      headers['User-Agent'] = ua;
    if (referer) headers['Referer']    = referer;

    // Generic http-header="Key=Value"
    const headerRe = /\bhttp-header="([^"=]+)=([^"]+)"/gi;
    let hm: RegExpExecArray | null;
    while ((hm = headerRe.exec(line)) !== null) {
      headers[hm[1]] = hm[2];
    }

    // Catchup
    const catchupMode   = line.match(/\bcatchup="([^"]+)"/)?.[1];
    const catchupSource = line.match(/\bcatchup-source="([^"]+)"/)?.[1];

    channels.push({
      name:      line.match(/,(.+)$/)?.[1]?.trim() ?? url,
      url,
      group:     line.match(/\bgroup-title="([^"]+)"/)?.[1] ?? 'Other',
      tvgId:     line.match(/\btvg-id="([^"]+)"/)?.[1],
      tvgLogo:   line.match(/\btvg-logo="([^"]+)"/)?.[1],
      userAgent: ua,
      referer,
      headers,
      catchup: catchupMode && catchupSource
        ? { mode: catchupMode, source: catchupSource }
        : undefined,
    });
  }

  return channels;
}
```

---

## Step 2 — Item Ref Encoding (`ref.ts`)

```typescript
// Root:           null
// Group folder:   { type: 'group', name: '央视IPV4' }
// Channel:        { type: 'channel', index: 42 }
//   (index into the cached channels array — avoids re-encoding full URL in ref)

export type M3URef =
  | { type: 'group';   name: string }
  | { type: 'channel'; index: number }

export const encodeRef = (ref: M3URef): string => JSON.stringify(ref);
export const decodeRef = (s: string): M3URef    => JSON.parse(s);
```

---

## Step 3 — Instance (`instance.ts`)

```typescript
export interface M3UState {
  url:      string;
  channels: Channel[];   // loaded on init(), grouped in memory
}

export async function init(url: string): Promise<M3UState> {
  const resp = await host.http.get({ url });
  return { url, channels: parseM3U(resp.body) };
}

export async function listEntry(
  state: M3UState,
  location: string | null,
): Promise<EntryList> {
  // Root — return unique groups as Folder entries
  if (location === null) {
    const groups = [...new Set(state.channels.map(c => c.group))];
    return {
      items: groups.map(g => ({
        id:    encodeRef({ type: 'group', name: g }),
        title: g,
        type:  'Folder',
        cover: null,
      })),
      totalCount: groups.length,
    };
  }

  const ref = decodeRef(location);

  // Group — return channels in this group as Playable entries
  if (ref.type === 'group') {
    const items = state.channels
      .map((c, index) => ({ c, index }))
      .filter(({ c }) => c.group === ref.name)
      .map(({ c, index }) => ({
        id:    encodeRef({ type: 'channel', index }),
        title: c.name,
        type:  'Playable' as EntryType,
        cover: c.tvgLogo ?? null,
      }));
    return { items, totalCount: items.length };
  }

  return { items: [], totalCount: 0 };
}

export async function getPlaybackSpec(
  state: M3UState,
  itemRef: string,
): Promise<PlaybackSpec> {
  const ref = decodeRef(itemRef);
  if (ref.type !== 'channel') throw new Error('Invalid ref for playback');

  const ch = state.channels[ref.index];
  if (!ch) throw new Error(`Channel index ${ref.index} out of range`);

  return {
    url:            ch.url,
    headers:        ch.headers,
    mimeType:       null,         // let the player sniff
    title:          ch.name,
    durationMs:     null,
    subtitleTracks: [],
    hooksState:     {},
  };
}
```

---

## Step 4 — Provider Fields & Validation (`provider.ts`)

```typescript
export function getFieldsSpec(): ProviderFieldSpec[] {
  return [
    {
      id:             'm3u_url',
      labelKey:       'm3u.field.url',
      kind:           'singleLine',
      required:       true,
      order:          0,
      placeholderKey: 'm3u.field.url.placeholder',
    },
  ];
}

export async function validateFields(
  values: Record<string, string>,
): Promise<ValidationResult> {
  try {
    const url = values['m3u_url']?.trim() ?? '';
    if (!url) throw new Error('M3U URL is required');

    const resp = await host.http.get({ url });
    if (!resp.body.includes('#EXTM3U')) throw new Error('URL does not appear to be a valid M3U playlist');

    const channels = parseM3U(resp.body);
    if (channels.length === 0) throw new Error('No channels found in playlist');

    const hash = await host.crypto.sha256({ input: url });
    const name = `IPTV (${channels.length} channels)`;
    return { success: true, hash, name, fields: { m3u_url: url } };
  } catch (e) {
    return { success: false, error: e instanceof Error ? e.message : String(e) };
  }
}
```

---

## Step 5 — Index (`index.ts`)

```typescript
import { getFieldsSpec, validateFields } from './provider.js';
import { init, listEntry, getPlaybackSpec } from './instance.js';
import type { M3UState } from './instance.js';

let state: M3UState | null = null;

(globalThis as any).insomniaProvider = {
  providesArt: false,

  async getFieldsSpec()      { return getFieldsSpec(); },
  async validateFields(args) { return validateFields(args.values); },

  async init(args) {
    state = await init(args.credentials['m3u_url']);
  },

  async listEntry(args) {
    return listEntry(state!, args.location);
  },

  // M3U has no search or detail — channels are directly Playable
  async search()    { return []; },
  async getDetail() { throw new Error('Not supported'); },

  async getPlaybackSpec(args) {
    return getPlaybackSpec(state!, args.itemRef);
  },

  async onPlaybackReady() {},
  async onProgressTick()  {},
  async onStop()          {},
};
```

---

## What Is Not Implemented (and Why)

| Feature | Decision |
|---------|----------|
| Catchup / timeshift | Out of scope — requires player-level support for live seek; the `catchup` data is parsed and available on `Channel` if needed later |
| EPG (`x-tvg-url`) | Out of scope — separate concern from playback; not part of Insomnia's current contracts |
| Auto-refresh on `#EXT-X-SUB-URL` | Out of scope — handled by re-calling `init()` on provider reload |
| Search | Not applicable — channel lists are small enough to browse |
| `#EXT-X-APP` / `#EXT-X-APTV-TYPE` tags | Ignored — app-specific metadata, irrelevant to playback |

---

## Files to Create

| File | Notes |
|------|-------|
| `providers-ts/providers/m3u/index.ts` | Entry point |
| `providers-ts/providers/m3u/provider.ts` | Fields + validation |
| `providers-ts/providers/m3u/instance.ts` | Core logic |
| `providers-ts/providers/m3u/parser.ts` | M3U parser |
| `providers-ts/providers/m3u/ref.ts` | ItemRef encoding |

---

## Estimated Effort

| Task | Estimate |
|------|----------|
| `parser.ts` | 0.5 day |
| `instance.ts` + `ref.ts` | 0.5 day |
| `provider.ts` + `index.ts` | 0.5 day |
| **Total** | **~1.5 days** |
