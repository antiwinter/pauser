import type { EntryList, EntryInfo, EntryDetail, PlaybackSpec } from '../../utils/types.js';
import type { CatVodConfig, SiteEntry } from './config.js';
import type { CatVodSpider } from './types.js';
import { decodeRef, encodeRef } from './ref.js';
import {
  parseEpisodes,
  vodDetailToEntryDetail,
  categoryListToFolders,
  vodListToEntries,
  liveChannelsToEntries,
  playResultToSpec,
} from './mapper.js';
import cmsHandler from './handlers/cms.js';
import jarHandler from './handlers/jar.js';
import drpyHandler from './handlers/drpy.js';
import { fetchLiveChannels } from './iptv.js';

// ── Handler interface ─────────────────────────────────────────────────────────

interface BaseSpiderHandler {
  name: string;
  type: number[];
  createSpider: (site: SiteEntry) => CatVodSpider;
}

interface SpiderHandlerWithInit extends BaseSpiderHandler {
  init: (state: CatVodState) => Promise<void>;
  canHandle: (site: SiteEntry, state: CatVodState) => boolean;
}

type SpiderHandler = BaseSpiderHandler | SpiderHandlerWithInit;

export interface CatVodState {
  config: CatVodConfig;
  unsupportedSites?: Set<string>;
  spiders?: Map<string, CatVodSpider>;  // Cache spider instances per siteKey
}

// ── Spider Handler Registry ──────────────────────────────────────────────────

const SPIDER_HANDLERS: SpiderHandler[] = [cmsHandler, jarHandler, drpyHandler];

// ── Spider Instance Management ───────────────────────────────────────────────

/**
 * Get or create a spider instance for a site
 * Caches instances to preserve state (especially for drpy/jar)
 */
function getSpider(site: SiteEntry, state: CatVodState): CatVodSpider {
  if (!state.spiders) {
    state.spiders = new Map();
  }

  const cached = state.spiders.get(site.key);
  if (cached) return cached;

  // Find handler that supports this site type and can handle it
  const handler = SPIDER_HANDLERS.find(h =>
    h.type.includes(site.type) &&
    (!('canHandle' in h) || (h as SpiderHandlerWithInit).canHandle(site, state))
  );
  if (!handler) {
    throw new Error(`No available handler for site type ${site.type}`);
  }

  const spider = handler.createSpider(site);
  state.spiders.set(site.key, spider);
  return spider;
}

// ── listEntry ─────────────────────────────────────────────────────────────────

export async function listEntry(
  state: CatVodState,
  location: string | null,
  startIndex: number,
  limit: number,
): Promise<EntryList> {
  if (location === null) return await listRoot(state);
  const ref = decodeRef(location);

  if (ref.type === 'unsupported') {
    return { items: [], totalCount: 0 };
  }

  if (ref.type === 'site') {
    const site = requireSite(state, ref.key);
    const spider = getSpider(site, state);
    const result = await spider.home();
    const all = categoryListToFolders(result.class ?? [], site.key);
    return { items: all.items.slice(startIndex, startIndex + limit), totalCount: all.totalCount };
  }

  if (ref.type === 'cat') {
    const site = requireSite(state, ref.key);
    const spider = getSpider(site, state);
    const pg = startIndex === 0 ? 1 : Math.floor(startIndex / limit) + 1;
    const result = await spider.category(ref.tid, pg);
    const entryList = vodListToEntries(result.list ?? [], site.key, result.total);
    return entryList;
  }

  if (ref.type === 'vod') {
    // Episode list for multi-episode items
    const site = requireSite(state, ref.key);
    const spider = getSpider(site, state);
    const detail = await spider.detail(ref.id);
    const eps = parseEpisodes(detail);
    const items = eps.map((ep) => ({
      id:    encodeRef({ type: 'ep', key: ref.key, id: ref.id, flag: ep.flag, epUrl: ep.url }),
      title: eps.length > 1 ? ep.name : detail.vod_name ?? ep.name,
      type:  'Playable' as const,
      cover: detail.vod_pic ?? null,
    }));
    return { items: items.slice(startIndex, startIndex + limit), totalCount: items.length };
  }

  if (ref.type === 'live-source') {
    const live = state.config.lives?.[ref.index];
    if (!live) return { items: [], totalCount: 0 };
    const result = await fetchLiveChannels(live);
    const all = liveChannelsToEntries(result.channels);
    return { items: all.items.slice(startIndex, startIndex + limit), totalCount: all.totalCount };
  }

  if (ref.type === 'live') {
    return { items: [], totalCount: 0 };
  }

  throw new Error(`listEntry: unsupported ref type ${(ref as { type: string }).type}`);
}

// ── search ────────────────────────────────────────────────────────────────────

export async function search(
  state: CatVodState,
  _scopeLocation: string,
  query: string,
): Promise<EntryInfo[]> {
  const results: EntryInfo[] = [];
  for (const site of state.config.sites) {
    if (!site.searchable && site.searchable !== undefined) continue;
    // Check if site type is supported and handler can handle it
    const handler = SPIDER_HANDLERS.find(h =>
      h.type.includes(site.type) &&
      (!('canHandle' in h) || (h as SpiderHandlerWithInit).canHandle(site, state))
    );
    if (!handler) continue;
    try {
      const spider = getSpider(site, state);
      if (!spider.search) continue;  // Skip if search not supported
      const result = await spider.search(query, 1);
      const entryList = vodListToEntries(result.list ?? [], site.key, result.total);
      results.push(...entryList.items);
    } catch (_) {}
  }
  return results;
}

// ── getDetail ─────────────────────────────────────────────────────────────────

export async function getDetail(
  state: CatVodState,
  itemRef: string,
): Promise<EntryDetail> {
  const ref = decodeRef(itemRef);

  if (ref.type === 'vod') {
    const site = requireSite(state, ref.key);
    const spider = getSpider(site, state);
    const detail = await spider.detail(ref.id);
    return vodDetailToEntryDetail(detail);
  }

  if (ref.type === 'live-source') {
    const live = state.config.lives?.[ref.index];
    return {
      title: live?.name ?? '', overview: null, logo: null, backdrop: [],
      isMedia: false, rating: null, bitrate: null, externalUrls: [],
      year: null, providerIds: {}, streams: [], etag: null,
    };
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
    const spider = getSpider(site, state);
    const result = await spider.play(ref.flag, ref.epUrl);
    return applyHosts(playResultToSpec(result, ref.epUrl), state.config.hosts);
  }

  // Vod ref with single episode → resolve inline
  if (ref.type === 'vod') {
    const site = requireSite(state, ref.key);
    const spider = getSpider(site, state);
    const detail = await spider.detail(ref.id);
    const eps = parseEpisodes(detail);
    if (eps.length === 0) throw new Error('No episodes found');
    const result = await spider.play(eps[0].flag, eps[0].url);
    return applyHosts(playResultToSpec(result, eps[0].url), state.config.hosts);
  }

  // Live channel → direct URL
  if (ref.type === 'live') {
    return applyHosts({
      url: ref.url, headers: {}, mimeType: null, bitrate: null,
      title: ref.name, durationMs: null, subtitleTracks: [], hooksState: {},
    }, state.config.hosts);
  }

  throw new Error(`getPlaybackSpec: unsupported ref type ${(ref as { type: string }).type}`);
}

// ── Dispatch helpers ──────────────────────────────────────────────────────────

async function initHandlers(state: CatVodState): Promise<void> {
  for (const handler of SPIDER_HANDLERS) {
    if ('init' in handler) {
      await (handler as SpiderHandlerWithInit).init(state).catch(() => {}); // Allow init to fail silently
    }
  }
}

async function listRoot(state: CatVodState): Promise<EntryList> {
  await initHandlers(state);

  const available: SiteEntry[] = [];
  const unavailable: SiteEntry[] = [];

  for (const site of state.config.sites) {
    const handler = SPIDER_HANDLERS.find(h =>
      h.type.includes(site.type) &&
      (!('canHandle' in h) || (h as SpiderHandlerWithInit).canHandle(site, state))
    );
    if (handler) {
      available.push(site);
    } else {
      unavailable.push(site);
    }
  }

  const items: EntryList['items'] = available.map(site => ({
    id:    encodeRef({ type: 'site', key: site.key }),
    title: site.name,
    type:  'Folder' as const,
    cover: null,
  }));

  if (unavailable.length > 0) {
    items.push({
      id:    encodeRef({ type: 'unsupported', count: unavailable.length }),
      title: `${unavailable.length} site${unavailable.length > 1 ? 's' : ''} unsupported`,
      type:  'Folder' as const,
      cover: null,
    });
  }

  if (state.config.lives?.length) {
    for (let i = 0; i < state.config.lives.length; i++) {
      items.push({
        id:    encodeRef({ type: 'live-source', index: i }),
        title: state.config.lives[i].name,
        type:  'Folder' as const,
        cover: null,
      });
    }
  }
  return { items, totalCount: items.length };
}

function requireSite(state: CatVodState, key: string): SiteEntry {
  const site = state.config.sites.find((s) => s.key === key);
  if (!site) throw new Error(`Site not found: ${key}`);
  return site;
}

function applyHosts(spec: PlaybackSpec, hosts: string[] | undefined): PlaybackSpec {
  if (!hosts?.length || !spec.url) return spec;
  const map = new Map(hosts.map((h) => h.split('=') as [string, string]));
  const remap = (url: string) => {
    try {
      // URL constructor may not be available in QuickJS
      type URLConstructor = { new(url: string): { hostname: string; toString(): string } };
      const URLCtor = (globalThis as { URL?: URLConstructor }).URL;
      if (!URLCtor) return url;
      const parsed = new URLCtor(url);
      const to = map.get(parsed.hostname);
      if (!to) return url;
      parsed.hostname = to;
      return parsed.toString();
    } catch { return url; }
  };
  return {
    ...spec,
    url: remap(spec.url),
    headers: Object.fromEntries(
      Object.entries(spec.headers).map(([k, v]) => [k, remap(v)])
    ),
  };
}
