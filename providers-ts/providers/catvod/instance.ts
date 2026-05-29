import type { EntryList, EntryInfo, EntryDetail, PlaybackSpec } from '../../utils/types.js';
import type { CatVodConfig, SiteEntry } from './config.js';
import { parseSpiderField, siteExt } from './config.js';
import { decodeRef, encodeRef } from './ref.js';
import { parseEpisodes, vodItemToEntry } from './mapper.js';
import { cmsHome, cmsCategory, cmsDetail, cmsSearch, buildDetail } from './handlers/cms.js';
import { jarHome, jarCategory, jarDetail, jarPlay, ensureJar } from './handlers/jar.js';
import { fetchLiveChannels } from './handlers/iptv.js';
import { drpyHome, drpyCategory, drpyDetail, drpyPlay, drpySearch } from './handlers/drpy.js';

export interface CatVodState {
  config: CatVodConfig;
  unsupportedSites?: Set<string>;
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
    const all  = await dispatchHome(site, state);
    return { items: all.items.slice(startIndex, startIndex + limit), totalCount: all.totalCount };
  }

  if (ref.type === 'cat') {
    const site = requireSite(state, ref.key);
    const pg   = startIndex === 0 ? 1 : Math.floor(startIndex / limit) + 1;
    return dispatchCategory(site, state, ref.tid, pg);
  }

  if (ref.type === 'vod') {
    // Episode list for multi-episode items
    const site = requireSite(state, ref.key);
    const raw  = await dispatchDetail(site, state, ref.id);
    const eps  = parseEpisodes(raw);
    const items = eps.map((ep) => ({
      id:    encodeRef({ type: 'ep', key: ref.key, id: ref.id, flag: ep.flag, epUrl: ep.url }),
      title: eps.length > 1 ? ep.name : raw.vod_name ?? ep.name,
      type:  'Playable' as const,
      cover: raw.vod_pic ?? null,
    }));
    return { items: items.slice(startIndex, startIndex + limit), totalCount: items.length };
  }

  if (ref.type === 'live-source') {
    const live = state.config.lives?.[ref.index];
    if (!live) return { items: [], totalCount: 0 };
    const all = await fetchLiveChannels(live);
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
    if (site.type !== 0 && site.type !== 1 && site.type !== 2 && !isDrpy(site)) continue;
    try {
      if (isDrpy(site)) {
        const r = await drpySearch(site.api, siteExt(site), site.key, query, 1);
        results.push(...r.items);
      } else {
        const r = await cmsSearch(site.api, site.key, query, 1);
        results.push(...r.items);
      }
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
    const raw  = await dispatchDetail(site, state, ref.id);
    return buildDetail(raw);
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
    return applyHosts(await dispatchPlay(site, state, ref.flag, ref.epUrl), state.config.hosts);
  }

  // Vod ref with single episode → resolve inline
  if (ref.type === 'vod') {
    const site = requireSite(state, ref.key);
    const raw  = await dispatchDetail(site, state, ref.id);
    const eps  = parseEpisodes(raw);
    if (eps.length === 0) throw new Error('No episodes found');
    return applyHosts(await dispatchPlay(site, state, eps[0].flag, eps[0].url), state.config.hosts);
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
    state.unsupportedSites = new Set(state.config.sites.filter(isJar).map(s => s.key));
  }
}

async function listRoot(state: CatVodState): Promise<EntryList> {
  await initJar(state);
  const failed = state.unsupportedSites!;

  const items: EntryList['items'] = state.config.sites
    .filter(site => !isJar(site) || !failed.has(site.key))
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

async function dispatchHome(site: SiteEntry, state: CatVodState): Promise<EntryList> {
  if (isCms(site)) return cmsHome(site.api, site.key);
  if (isJar(site)) {
    const jar = requireJar(state);
    return await jarHome(jar.url, jar.md5, site.api, siteExt(site), site.key);
  }
  if (isDrpy(site)) return drpyHome(site.api, siteExt(site), site.key);
  throw new Error(`Site type ${site.type} not supported yet`);
}

async function dispatchCategory(site: SiteEntry, state: CatVodState, tid: string, pg: number): Promise<EntryList> {
  if (isCms(site)) return cmsCategory(site.api, site.key, tid, pg);
  if (isJar(site)) {
    const jar = requireJar(state);
    return await jarCategory(jar.url, site.api, siteExt(site), site.key, tid, pg);
  }
  if (isDrpy(site)) return drpyCategory(site.api, siteExt(site), site.key, tid, pg);
  throw new Error(`Site type ${site.type} not supported yet`);
}

async function dispatchDetail(site: SiteEntry, state: CatVodState, id: string) {
  if (isCms(site)) return cmsDetail(site.api, id);
  if (isJar(site)) {
    const jar = requireJar(state);
    return await jarDetail(jar.url, site.api, siteExt(site), site.key, id);
  }
  if (isDrpy(site)) return drpyDetail(site.api, siteExt(site), site.key, id);
  throw new Error(`Site type ${site.type} not supported yet`);
}

async function dispatchPlay(site: SiteEntry, state: CatVodState, flag: string, epUrl: string): Promise<PlaybackSpec> {
  if (isCms(site)) {
    return { url: epUrl, headers: {}, mimeType: null, title: '', durationMs: null, subtitleTracks: [], hooksState: {} };
  }
  if (isJar(site)) {
    const jar = requireJar(state);
    return await jarPlay(jar.url, site.api, siteExt(site), site.key, flag, epUrl);
  }
  if (isDrpy(site)) return drpyPlay(site.api, siteExt(site), site.key, flag, epUrl);
  throw new Error(`Site type ${site.type} not supported yet`);
}

function isCms(site: SiteEntry): boolean {
  return site.type === 0 || site.type === 1 || site.type === 2;
}

function isJar(site: SiteEntry): boolean {
  return site.type === 3;
}

function isDrpy(site: SiteEntry): boolean {
  return site.type === 4 || site.type === 9 || site.type === 10;
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
