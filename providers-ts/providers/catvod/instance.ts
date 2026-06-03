import type { EntryList, EntryInfo, EntryDetail, PlaybackSpec } from '../../utils/types.js';
import type { CatVodConfig, SiteEntry } from './config.js';
import type { CatVodSpider } from './types.js';
import { parseSpiderField, siteExt } from './config.js';
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
import { ensureJar } from './handlers/jar.js';
import { fetchLiveChannels } from './iptv.js';

export interface CatVodState {
  config: CatVodConfig;
  unsupportedSites?: Set<string>;
  spiders?: Map<string, CatVodSpider>;  // Cache spider instances per siteKey
}

// ── Spider Handler Registry ──────────────────────────────────────────────────

const SPIDER_HANDLERS = [cmsHandler, jarHandler, drpyHandler];

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

  // Find handler that supports this site type
  const handler = SPIDER_HANDLERS.find(h => h.type.includes(site.type));
  if (!handler) {
    throw new Error(`No handler found for site type ${site.type}`);
  }

  let spider: CatVodSpider;

  if (handler.name === 'cms') {
    spider = handler.createSpider(site.api);
  } else if (handler.name === 'jar') {
    const jar = requireJar(state);
    spider = handler.createSpider(jar.url, jar.md5, site.api, siteExt(site), site.key);
  } else if (handler.name === 'drpy') {
    spider = handler.createSpider(site.api, siteExt(site), site.key);
  } else {
    throw new Error(`Unknown handler: ${handler.name}`);
  }

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
    // Check if site type is supported by any handler
    const handler = SPIDER_HANDLERS.find(h => h.type.includes(site.type));
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
      url: ref.url, headers: {}, mimeType: null,
      title: ref.name, durationMs: null, subtitleTracks: [], hooksState: {},
    }, state.config.hosts);
  }

  throw new Error(`getPlaybackSpec: unsupported ref type ${(ref as { type: string }).type}`);
}

// ── Dispatch helpers ──────────────────────────────────────────────────────────

async function initJar(state: CatVodState) {
  if (state.unsupportedSites) return;

  state.unsupportedSites = new Set();
  const jar = parseSpiderField(state.config.spider);
  if (!jar) return;

  try {
    await ensureJar(jar.url, jar.md5);
  } catch {
    // jar.load failed — all jar sites are unsupported
    const jarHandler = SPIDER_HANDLERS.find(h => h.name === 'jar');
    if (jarHandler) {
      state.unsupportedSites = new Set(
        state.config.sites.filter(s => jarHandler.type.includes(s.type)).map(s => s.key)
      );
    }
  }
}

async function listRoot(state: CatVodState): Promise<EntryList> {
  await initJar(state);
  const failed = state.unsupportedSites!;
  const jarHandler = SPIDER_HANDLERS.find(h => h.name === 'jar');

  const items: EntryList['items'] = state.config.sites
    .filter(site => !jarHandler || !jarHandler.type.includes(site.type) || !failed.has(site.key))
    .map((site) => ({
      id:    encodeRef({ type: 'site', key: site.key }),
      title: site.name,
      type:  'Folder' as const,
      cover: null,
    }));

  if (failed.size > 0) {
    items.push({
      id:    encodeRef({ type: 'unsupported', count: failed.size }),
      title: `${failed.size} site${failed.size > 1 ? 's' : ''} unsupported`,
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

function requireJar(state: CatVodState): { url: string; md5?: string } {
  const jar = parseSpiderField(state.config.spider);
  if (!jar) throw new Error('Site requires a JAR spider but config.spider is not set');
  return jar;
}

function applyHosts(spec: PlaybackSpec, hosts: string[] | undefined): PlaybackSpec {
  if (!hosts?.length || !spec.url) return spec;
  const map = new Map(hosts.map((h) => h.split('=') as [string, string]));
  const remap = (url: string) => {
    try {
      const u = new URL(url);
      const to = map.get(u.hostname);
      if (!to) return url;
      u.hostname = to;
      return u.toString();
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
