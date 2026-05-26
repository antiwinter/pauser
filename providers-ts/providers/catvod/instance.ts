import type { EntryList, EntryDetail, PlaybackSpec } from '../../utils/types.js';
import type { CatVodConfig, SiteEntry } from './config.js';
import { decodeRef, encodeRef } from './ref.js';
import { parseEpisodes, vodItemToEntry } from './mapper.js';
import { cmsHome, cmsCategory, cmsDetail, cmsSearch, buildDetail } from './handlers/cms.js';
import { fetchLiveChannels } from './handlers/iptv.js';

export interface CatVodState {
  config: CatVodConfig;
}

// ── listEntry ─────────────────────────────────────────────────────────────────

export async function listEntry(
  state: CatVodState,
  location: string | null,
  startIndex: number,
  limit: number,
): Promise<EntryList> {
  if (location === null) return listRoot(state);
  const ref = decodeRef(location);

  if (ref.type === 'site') {
    const site = requireSite(state, ref.key);
    return dispatchHome(site);
  }

  if (ref.type === 'cat') {
    const site = requireSite(state, ref.key);
    const pg   = startIndex === 0 ? 1 : Math.floor(startIndex / limit) + 1;
    return dispatchCategory(site, ref.tid, pg);
  }

  if (ref.type === 'vod') {
    // Episode list for multi-episode items
    const site = requireSite(state, ref.key);
    const raw  = await cmsDetail(site.api, ref.id);
    const eps  = parseEpisodes(raw);
    const items = eps.map((ep) => ({
      id:    encodeRef({ type: 'ep', key: ref.key, id: ref.id, flag: ep.flag, epUrl: ep.url }),
      title: eps.length > 1 ? ep.name : raw.vod_name ?? ep.name,
      type:  'Playable' as const,
      cover: raw.vod_pic ?? null,
    }));
    return { items, totalCount: items.length };
  }

  if (ref.type === 'live') {
    return fetchLiveChannels(state.config.lives ?? []);
  }

  return { items: [], totalCount: 0 };
}

// ── search ────────────────────────────────────────────────────────────────────

export async function search(
  state: CatVodState,
  _scopeLocation: string,
  query: string,
): Promise<EntryList> {
  const results: EntryList['items'] = [];
  for (const site of state.config.sites) {
    if (!site.searchable && site.searchable !== undefined) continue;
    if (site.type !== 0 && site.type !== 1 && site.type !== 2) continue;
    try {
      const r = await cmsSearch(site.api, site.key, query, 1);
      results.push(...r.items);
    } catch (_) {}
  }
  return { items: results, totalCount: results.length };
}

// ── getDetail ─────────────────────────────────────────────────────────────────

export async function getDetail(
  state: CatVodState,
  itemRef: string,
): Promise<EntryDetail> {
  const ref = decodeRef(itemRef);

  if (ref.type === 'vod') {
    const site = requireSite(state, ref.key);
    const raw  = await cmsDetail(site.api, ref.id);
    return buildDetail(raw);
  }

  if (ref.type === 'live') {
    return {
      title: ref.name, overview: null, logo: null, backdrop: [],
      isMedia: true, rating: null, bitrate: null, externalUrls: [],
      year: null, providerIds: {}, streams: [], etag: null,
    };
  }

  throw new Error(`getDetail: unsupported ref type ${(ref as { type: string }).type}`);
}

// ── getPlaybackSpec ───────────────────────────────────────────────────────────

export async function getPlaybackSpec(
  state: CatVodState,
  itemRef: string,
  _startMs: number,
): Promise<PlaybackSpec> {
  const ref = decodeRef(itemRef);

  // Direct episode ref → already has the URL
  if (ref.type === 'ep') {
    const site = requireSite(state, ref.key);
    return dispatchPlay(site, ref.flag, ref.epUrl);
  }

  // Vod ref with single episode → resolve inline
  if (ref.type === 'vod') {
    const site = requireSite(state, ref.key);
    const raw  = await cmsDetail(site.api, ref.id);
    const eps  = parseEpisodes(raw);
    if (eps.length === 0) throw new Error('No episodes found');
    return dispatchPlay(site, eps[0].flag, eps[0].url);
  }

  // Live channel → direct URL
  if (ref.type === 'live') {
    return {
      url: ref.url, headers: {}, mimeType: null,
      title: ref.name, durationMs: null, subtitleTracks: [], hooksState: {},
    };
  }

  throw new Error(`getPlaybackSpec: unsupported ref type ${(ref as { type: string }).type}`);
}

// ── Dispatch helpers ──────────────────────────────────────────────────────────

function listRoot(state: CatVodState): EntryList {
  const items: EntryList['items'] = state.config.sites.map((site) => ({
    id:    encodeRef({ type: 'site', key: site.key }),
    title: site.name,
    type:  'Folder' as const,
    cover: null,
  }));
  if (state.config.lives?.length) {
    items.push({ id: encodeRef({ type: 'live', name: '__all__', url: '' }), title: '直播', type: 'Folder', cover: null });
  }
  return { items, totalCount: items.length };
}

function requireSite(state: CatVodState, key: string): SiteEntry {
  const site = state.config.sites.find((s) => s.key === key);
  if (!site) throw new Error(`Site not found: ${key}`);
  return site;
}

async function dispatchHome(site: SiteEntry): Promise<EntryList> {
  if (isCms(site)) return cmsHome(site.api, site.key);
  throw new Error(`Site type ${site.type} not supported in Phase 1`);
}

async function dispatchCategory(site: SiteEntry, tid: string, pg: number): Promise<EntryList> {
  if (isCms(site)) return cmsCategory(site.api, site.key, tid, pg);
  throw new Error(`Site type ${site.type} not supported in Phase 1`);
}

async function dispatchPlay(site: SiteEntry, flag: string, epUrl: string): Promise<PlaybackSpec> {
  if (isCms(site)) {
    // CMS sites: the epUrl from vod_play_url IS the direct video URL
    return {
      url: epUrl, headers: {}, mimeType: null,
      title: '', durationMs: null, subtitleTracks: [], hooksState: {},
    };
  }
  throw new Error(`Site type ${site.type} not supported in Phase 1`);
}

function isCms(site: SiteEntry): boolean {
  return site.type === 0 || site.type === 1 || site.type === 2;
}
