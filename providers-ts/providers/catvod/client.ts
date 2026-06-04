import type {
  EntryList,
  EntryInfo,
  EntryDetail,
  PlaybackSpec,
  ValidationResult,
} from "../../utils/types.js";
import type { CatVodConfig, SiteEntry } from "./config.js";
import type { CatVodSpider } from "./spider/types.js";
import { decodeRef, encodeRef } from "./ref.js";
import {
  parseEpisodes,
  vodDetailToEntryDetail,
  categoryListToFolders,
  vodListToEntries,
  liveChannelsToEntries,
  playResultToSpec,
} from "./mapper.js";
import cmsHandler from "./spider/cms.js";
import jarHandler from "./spider/jar.js";
import drpyHandler from "./spider/drpy.js";
import { fetchLiveChannels } from "./iptv.js";

// ── Handler interface ─────────────────────────────────────────────────────────

interface BaseSpiderHandler {
  name: string;
  type: number[];
  createSpider: (site: SiteEntry) => CatVodSpider;
}

interface SpiderHandlerWithInit extends BaseSpiderHandler {
  init: (state: CatVodClientState) => Promise<void>;
  canHandle: (site: SiteEntry, state: CatVodClientState) => boolean;
}

type SpiderHandler = BaseSpiderHandler | SpiderHandlerWithInit;

export interface CatVodClientState {
  rawCredentials: Record<string, string>;  // raw form values — available for test()
  config: CatVodConfig;                    // always populated by init()
  unsupportedSites?: Set<string>;
  spiders?: Map<string, CatVodSpider>;     // Cache spider instances per siteKey
  _siteMap?: Map<string, SiteEntry>;       // O(1) site lookup by key
}

// ── test() ───────────────────────────────────────────────────────────────────

export async function test(state: CatVodClientState): Promise<ValidationResult> {
  const cfg = state.config;
  return {
    success: true,
    fields: {
      config_url: state.rawCredentials['config_url'] ?? '',
      name: `CatVod (${cfg.sites.length} sources)`,
    },
  };
}

// ── Spider Handler Registry ──────────────────────────────────────────────────

const SPIDER_HANDLERS: SpiderHandler[] = [cmsHandler, jarHandler, drpyHandler];

// Index by site type for O(1) handler resolution
const HANDLER_BY_TYPE = new Map<number, SpiderHandler>();
for (const h of SPIDER_HANDLERS) {
  for (const t of h.type) {
    if (!HANDLER_BY_TYPE.has(t)) HANDLER_BY_TYPE.set(t, h);
  }
}

// ── Spider Instance Management ───────────────────────────────────────────────

/**
 * Get or create a spider instance for a site
 * Caches instances to preserve state (especially for drpy/jar)
 */
function getSpider(site: SiteEntry, state: CatVodClientState): CatVodSpider {
  if (!state.spiders) {
    state.spiders = new Map();
  }

  const cached = state.spiders.get(site.key);
  if (cached) return cached;

  const handler = HANDLER_BY_TYPE.get(site.type);
  if (!handler) {
    throw new Error(`No available handler for site type ${site.type}`);
  }

  const spider = handler.createSpider(site);
  state.spiders.set(site.key, spider);
  return spider;
}

// ── listEntry ─────────────────────────────────────────────────────────────────

export async function listEntry(
  state: CatVodClientState,
  location: string | null,
  startIndex: number,
  limit: number,
): Promise<EntryList> {
  if (location === null) return await listRoot(state);
  const ref = decodeRef(location);
  const pg = startIndex === 0 ? 1 : Math.floor(startIndex / limit) + 1;

  if (ref.type === "site") {
    const site = requireSite(state, ref.key);
    const spider = getSpider(site, state);
    const result = await spider.home();
    const all = categoryListToFolders(result.class ?? [], site.key);
    return {
      items: all.items.slice(startIndex, startIndex + limit),
      totalCount: all.totalCount,
    };
  }

  if (ref.type === "cat") {
    const site = requireSite(state, ref.key);
    const spider = getSpider(site, state);
    const result = await spider.category(ref.tid, pg);
    const entryList = vodListToEntries(
      result.list ?? [],
      site.key,
      result.total,
    );
    return entryList;
  }

  if (ref.type === "vod") {
    const site = requireSite(state, ref.key);
    const spider = getSpider(site, state);
    // Episode list for multi-episode items
    const detailResult = await spider.detail([ref.id]);
    const detail = detailResult.list?.[0];
    if (!detail) return { items: [], totalCount: 0 };
    const eps = parseEpisodes(detail);
    const items = eps.map((ep) => ({
      id: encodeRef({
        type: "ep",
        key: ref.key,
        id: ref.id,
        flag: ep.flag,
        epUrl: ep.url,
      }),
      title: eps.length > 1 ? ep.name : (detail.vod_name ?? ep.name),
      type: "Playable" as const,
      cover: detail.vod_pic ?? null,
    }));
    return {
      items: items.slice(startIndex, startIndex + limit),
      totalCount: items.length,
    };
  }

  if (ref.type === "live-source") {
    const live = state.config.lives?.[ref.index];
    if (!live) return { items: [], totalCount: 0 };
    const result = await fetchLiveChannels(live);
    const all = liveChannelsToEntries(result.channels);
    return {
      items: all.items.slice(startIndex, startIndex + limit),
      totalCount: all.totalCount,
    };
  }

  return { items: [], totalCount: 0 };
}

// ── search ────────────────────────────────────────────────────────────────────

export async function search(
  state: CatVodClientState,
  _scopeLocation: string,
  query: string,
): Promise<EntryInfo[]> {
  const results: EntryInfo[] = [];
  for (const site of state.config.sites) {
    if (site.searchable === 0) continue;
    const handler = HANDLER_BY_TYPE.get(site.type);
    if (!handler) continue;
    try {
      const spider = getSpider(site, state);
      if (!spider.search) continue; // Skip if search not supported
      const result = await spider.search(query, 1);
      const entryList = vodListToEntries(
        result.list ?? [],
        site.key,
        result.total,
      );
      results.push(...entryList.items);
    } catch (_) {}
  }
  return results;
}

// ── getDetail ─────────────────────────────────────────────────────────────────

export async function getDetail(
  state: CatVodClientState,
  itemRef: string,
): Promise<EntryDetail> {
  const ref = decodeRef(itemRef);

  if (ref.type === "vod") {
    const site = requireSite(state, ref.key);
    const spider = getSpider(site, state);
    const detailResult = await spider.detail([ref.id]);
    const detail = detailResult.list?.[0];
    if (!detail) return {} as EntryDetail;
    return vodDetailToEntryDetail(detail);
  }

  // no detail
  return {} as EntryDetail;
}

// ── getPlaybackSpec ───────────────────────────────────────────────────────────

export async function getPlaybackSpec(
  state: CatVodClientState,
  itemRef: string,
  _startMs: number,
): Promise<PlaybackSpec> {
  const ref = decodeRef(itemRef);

  // Direct episode ref → already has the URL
  if (ref.type === "ep") {
    const site = requireSite(state, ref.key);
    const spider = getSpider(site, state);
    const result = await spider.play(ref.flag, ref.epUrl);
    return playResultToSpec(result, ref.epUrl);
  }

  // Vod ref with single episode → resolve inline
  if (ref.type === "vod") {
    const site = requireSite(state, ref.key);
    const spider = getSpider(site, state);
    const detailResult = await spider.detail([ref.id]);
    const detail = detailResult.list?.[0];
    if (!detail) throw new Error("No episodes found");
    const eps = parseEpisodes(detail);
    if (eps.length === 0) throw new Error("No episodes found");
    const result = await spider.play(eps[0].flag, eps[0].url);
    return playResultToSpec(result, eps[0].url);
  }

  // Live channel → direct URL
  if (ref.type === "live") {
    return {
      url: ref.url,
      title: ref.name,
    } as PlaybackSpec;
  }

  throw new Error(
    `getPlaybackSpec: unsupported ref type ${(ref as { type: string }).type}`,
  );
}

// ── Dispatch helpers ──────────────────────────────────────────────────────────

async function initHandlers(state: CatVodClientState): Promise<void> {
  for (const handler of SPIDER_HANDLERS) {
    if ("init" in handler) {
      await (handler as SpiderHandlerWithInit).init(state).catch(() => {}); // Allow init to fail silently
    }
  }
}

async function listRoot(state: CatVodClientState): Promise<EntryList> {
  await initHandlers(state);

  const available: SiteEntry[] = [];
  const unavailable: SiteEntry[] = [];

  for (const site of state.config.sites) {
    const handler = HANDLER_BY_TYPE.get(site.type);
    if (handler && (!("canHandle" in handler) || (handler as SpiderHandlerWithInit).canHandle(site, state))) {
      available.push(site);
    } else {
      unavailable.push(site);
    }
  }

  const items: EntryList["items"] = available.map((site) => ({
    id: encodeRef({ type: "site", key: site.key }),
    title: site.name,
    type: "Folder" as const,
    cover: null,
  }));

  if (unavailable.length > 0) {
    items.push({
      id: encodeRef({ type: "unsupported", count: unavailable.length }),
      title: `${unavailable.length} site${unavailable.length > 1 ? "s" : ""} unsupported`,
      type: "Folder" as const,
      cover: null,
    });
  }

  if (state.config.lives?.length) {
    for (let i = 0; i < state.config.lives.length; i++) {
      items.push({
        id: encodeRef({ type: "live-source", index: i }),
        title: state.config.lives[i].name,
        type: "Folder" as const,
        cover: null,
      });
    }
  }
  return { items, totalCount: items.length };
}

function getSiteMap(state: CatVodClientState): Map<string, SiteEntry> {
  if (!state._siteMap) {
    state._siteMap = new Map(state.config.sites.map((s) => [s.key, s]));
  }
  return state._siteMap;
}

function requireSite(state: CatVodClientState, key: string): SiteEntry {
  const site = getSiteMap(state).get(key);
  if (!site) throw new Error(`Site not found: ${key}`);
  return site;
}

