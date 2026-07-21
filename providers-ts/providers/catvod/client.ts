import type {
  EntryList,
  EntryInfo,
  PlaybackSource,
  ValidationResult,
} from "../../utils/types.js";
import type { SiteEntry, LiveEntry } from "./config.js";
import type { CatVodCategoryResult, CatVodPlayResult } from "./spider/types.js";
import { decodeRef, encodeRef } from "./ref.js";
import {
  parseEpisodes,
  categoryListToFolders,
  vodListToEntries,
  vodItemToEntry,
  liveChannelsToEntries,
  playResultToSource,
} from "./mapper.js";
import { initSpiders, getSpider, getConfig, canHandleSite } from "./spider/index.js";
import { needsSniff, sniffPlayUrl } from "./spider/sniffer.js";
import { fetchConfig } from "./config.js";

// ── Client State ──────────────────────────────────────────────────────────────

export interface CatVodClientState {
  rawCredentials: Record<string, string>;
}


// ── test() ───────────────────────────────────────────────────────────────────

export async function test(args: {
  values?: Record<string, string>;
  credentials?: Record<string, string>;
}): Promise<ValidationResult> {
  const values = args.values ?? args.credentials ?? {};
  const configUrl = values['config_url'] ?? '';
  if (!configUrl) {
    return { success: false, error: 'config_url is required' };
  }

  const config = await fetchConfig(configUrl);
  await initSpiders(config);

  const siteCount = Object.keys(config.sites).length;
  return {
    success: true,
    fields: {
      config_url: configUrl,
      name: `CatVod (${siteCount} sources)`,
    },
  };
}

// ── listEntry ─────────────────────────────────────────────────────────────────

export async function listEntry(
  state: CatVodClientState,
  location: string | null,
  startIndex: number,
  limit: number,
  options?: import("../../utils/types.js").QueryOptions,
): Promise<EntryList> {
 const searchTerm = options?.searchTerm?.trim();
  if (searchTerm) {
    // spider ignores limit, may return more than requested
    const all = await searchAllSites(state, searchTerm);
    return { items: all, totalCount: all.length };
  }

  if (location === null) return await listRoot();
  const ref = decodeRef(location);
  if (ref.type === 'unsupported') return { items: [], totalCount: 0 };
  const pg = limit > 0 ? Math.floor(startIndex / limit) + 1 : 1;
  const spider = getSpider(ref.key);

  if (ref.type === "site") {
    const result = await spider.home();
    return categoryListToFolders(result.class ?? [], ref.key);
  }

  if (ref.type === "cat") {
    const result = await spider.category(ref.tid, pg);
    return vodListToEntries(
      result.list ?? [],
      ref.key,
      ref.tid,
      result.total,
    );
  }

  if (ref.type === "vod") {
    // msearch: vod_ids are Douban-side placeholders whose real resolution is a
    // cross-site search by title (mirrors fongmi's detailEmpty → search fallback).
    if (ref.id.startsWith('msearch:')) {
      const title = ref.id.split('###')[1] ?? '';
      if (!title) return { items: [], totalCount: 0 };
      const results = await searchAllSites(state, title);
      return { items: results, totalCount: results.length };
    }
    const detailResult = await spider.detail([ref.id]);
    const detail = detailResult.list?.[0];
    if (!detail) return { items: [], totalCount: 0 };
    const eps = parseEpisodes(detail);
    const items = eps.map((ep, epIndex): EntryInfo => ({
      ref: encodeRef({
        type: "ep",
        key: ref.key,
        tid: ref.tid,
        id: ref.id,
        epIndex,
        flagIndex: ep.flagIndex,
      }),
      title: eps.length > 1 ? ep.name : (detail.vod_name ?? ep.name),
      type: "Video" as const,
      cover: detail.vod_pic ?? null,
    }));
    return {
      items,
      totalCount: items.length,
    };
  }

  if (ref.type === "live-source") {
    if (!spider.channels) return { items: [], totalCount: 0 };
    const result = await spider.channels();
    const liveEntry = getConfig().sites[ref.key];
    const ua = liveEntry && liveEntry.type === 'live' ? liveEntry.ua : undefined;
    return liveChannelsToEntries(result.channels, ref.key, ua);
  }

  return { items: [], totalCount: 0 };
}

// ── search ────────────────────────────────────────────────────────────────────

// All sites run concurrently; each is capped so a slow spider doesn't stall
// the whole search. drpy spiders self-serialize on the drpy lock (they share
// one QuickJS context + mutable globals), so they still execute one at a time;
// JAR/CMS run free and overlap their HTTP/reflect waits.
const SEARCH_SITE_TIMEOUT_MS = 6_000;

function withTimeout<T>(p: Promise<T>, ms: number, fallback: T): Promise<T> {
  return Promise.race([p, host.timer.sleep({ ms }).then(() => fallback)]);
}

async function searchOneSite(key: string, q: string): Promise<EntryInfo[]> {
  try {
    const spider = getSpider(key);
    if (!spider.search) return [];
    const result = await withTimeout(
      spider.search(q, 1),
      SEARCH_SITE_TIMEOUT_MS,
      { list: [], total: 0 } as CatVodCategoryResult,
    );
    return vodListToEntries(result.list ?? [], key, '', result.total).items;
  } catch { return []; }
}

async function searchAllSites(
  _state: CatVodClientState,
  query: string,
): Promise<EntryInfo[]> {
  const q = query.trim();
  if (!q) return [];
  const config = getConfig();
  const keys: string[] = [];
  for (const [key, entry] of Object.entries(config.sites)) {
    if (entry.type !== 'site') continue;
    const site = entry as SiteEntry;
    if (site.searchable === 0) continue;
    keys.push(key);
  }

  // Emit each site's results as they resolve so the UI can paint incrementally
  // instead of waiting on the slowest spider (or its timeout). The final
  // flat (config-order) list is returned for the wrapper's terminal emission
  // and cache payload; the wrapper emits it as isComplete=true.
  const settled: EntryInfo[][] = new Array(keys.length);
  await Promise.all(keys.map(async (key, i) => {
    const items = await searchOneSite(key, q);
    settled[i] = items;
    if (items.length) {
      await host.notification.send({
        method: 'emit-entries',
        message: 'partial',
        result: { data: items, isComplete: false },
      });
    }
  }));
  return settled.flat();
}

// ── getEntries ───────────────────────────────────────────────────────────────

// Resolves itemRefs back to EntryInfo — used by the detail screen's header refresh.
// For vod refs we re-fetch the detail so the metadata/childCount stay current.

export async function getEntries(
  _state: CatVodClientState,
  itemRefs: string[],
): Promise<EntryInfo[]> {
  const out: EntryInfo[] = [];
  for (const itemRef of itemRefs) {
    const ref = decodeRef(itemRef);
    if (ref.type === 'vod') {
      let detail = null;
      try {
        const spider = getSpider(ref.key);
        detail = (await spider.detail([ref.id])).list?.[0] ?? null;
      } catch { /* fall through to original item */ }
      out.push(vodItemToEntry(detail, ref.key, ref.tid, itemRef, ref.id));
      continue;
    }
    out.push({ ref: itemRef, title: itemRef, type: 'Folder', cover: null });
  }
  return out;
}

// ── getPlaybackSources ────────────────────────────────────────────────────────

// Resolve a spider play result into a PlaybackSource, sniffing the webpage URL through a
// headless WebView first when the spider flags parse=1 (url is a page, not a media stream).
async function resolvePlaySource(result: CatVodPlayResult): Promise<PlaybackSource> {
  if (needsSniff(result) && result.url) {
    const sniffed = await sniffPlayUrl(result.url, result.header, result.click);
    if (sniffed) {
      return playResultToSource({
        ...result,
        url: sniffed.url,
        play_url: sniffed.url,
        parse: 0,
        header: { ...result.header, ...sniffed.headers },
      });
    }
    // Sniff failed — fall through to the raw result so the player at least gets the page URL.
  }
  return playResultToSource(result);
}

export async function getPlaybackSources(
  _state: CatVodClientState,
  itemRef: string,
  _startMs: number,
): Promise<PlaybackSource[]> {
  const ref = decodeRef(itemRef);
  if (ref.type === 'unsupported') throw new Error("Unsupported ref type");

  if (ref.type === "ep") {
    const spider = getSpider(ref.key);
    const detailResult = await spider.detail([ref.id]);
    const detail = detailResult.list?.[0];
    if (!detail) throw new Error("Vod not found");
    const eps = parseEpisodes(detail);
    const ep = eps[ref.epIndex];
    if (!ep) throw new Error("Episode not found");
    const result = await spider.play(ep.flag, ep.url);
    return [await resolvePlaySource(result)];
  }

  // Vod ref with single episode → resolve inline
  if (ref.type === "vod") {
    const spider = getSpider(ref.key);
    const detailResult = await spider.detail([ref.id]);
    const detail = detailResult.list?.[0];
    if (!detail) throw new Error("No episodes found");
    const eps = parseEpisodes(detail);
    if (eps.length === 0) throw new Error("No episodes found");
    const result = await spider.play(eps[0].flag, eps[0].url);
    return [await resolvePlaySource(result)];
  }

  if (ref.type === "live") {
    throw new Error("CatVod live entries already include playback sources; host should use EntryInfo.sources instead of calling getPlaybackSources.");
  }

  throw new Error(
    `getPlaybackSources: unsupported ref type ${(ref as { type: string }).type}`,
  );
}

// ── Dispatch helpers ──────────────────────────────────────────────────────────

async function listRoot(): Promise<EntryList> {
  const config = getConfig();
  const available: Array<[SiteEntry | LiveEntry, string]> = [];
  const unavailable: SiteEntry[] = [];

  for (const [key, entry] of Object.entries(config.sites)) {
    if (canHandleSite(key)) {
      available.push([entry, key]);
    } else if (entry.type === 'site') {
      unavailable.push(entry as SiteEntry);
    }
  }

  const items: EntryList["items"] = available.map(([entry, key]) => {
    if (entry.type === 'site') {
      return {
        ref: encodeRef({ type: "site", key }),
        title: entry.name,
        type: "Folder" as const,
        cover: null,
      };
    } else {
      return {
        ref: encodeRef({ type: "live-source", key }),
        title: entry.name,
        type: "Livepak" as const,
        cover: null,
      };
    }
  });

  if (unavailable.length > 0) {
    items.push({
      ref: encodeRef({ type: "unsupported", count: unavailable.length }),
      title: `${unavailable.length} site${unavailable.length > 1 ? "s" : ""} unsupported`,
      type: "Folder" as const,
      cover: null,
    });
  }

  return { items, totalCount: items.length };
}
