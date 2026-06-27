import type {
  EntryList,
  EntryInfo,
  PlaybackSource,
  ValidationResult,
} from "../../utils/types.js";
import type { SiteEntry, LiveEntry } from "./config.js";
import { decodeRef, encodeRef } from "./ref.js";
import {
  parseEpisodes,
  categoryListToFolders,
  vodListToEntries,
  liveChannelsToEntries,
  playResultToSource,
} from "./mapper.js";
import { initSpiders, getSpider, getConfig, canHandleSite } from "./spider/index.js";
import { fetchConfig } from "./config.js";

// ── Client State ──────────────────────────────────────────────────────────────

export interface CatVodClientState {
  rawCredentials: Record<string, string>;
}

// ── test() ───────────────────────────────────────────────────────────────────

export async function test(state: CatVodClientState): Promise<ValidationResult> {
  const configUrl = state.rawCredentials['config_url'];
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
  _state: CatVodClientState,
  location: string | null,
  startIndex: number,
  limit: number,
): Promise<EntryList> {
  if (location === null) return await listRoot();
  const ref = decodeRef(location);
  if (ref.type === 'unsupported') return { items: [], totalCount: 0 };
  const pg = startIndex === 0 ? 1 : Math.floor(startIndex / limit) + 1;
  const spider = getSpider(ref.key);

  if (ref.type === "site") {
    const result = await spider.home();
    const all = categoryListToFolders(result.class ?? [], ref.key);
    return {
      items: all.items.slice(startIndex, startIndex + limit),
      totalCount: all.totalCount,
    };
  }

  if (ref.type === "cat") {
    const result = await spider.category(ref.tid, pg);
    const entryList = vodListToEntries(
      result.list ?? [],
      ref.key,
      ref.tid,
      result.total,
    );
    return entryList;
  }

  if (ref.type === "vod") {
    const detailResult = await spider.detail([ref.id]);
    const detail = detailResult.list?.[0];
    if (!detail) return { items: [], totalCount: 0 };
    const eps = parseEpisodes(detail);
    const items = eps.map((ep, epIndex) => ({
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
      items: items.slice(startIndex, startIndex + limit),
      totalCount: items.length,
    };
  }

  if (ref.type === "live-source") {
    if (!spider.channels) return { items: [], totalCount: 0 };
    const result = await spider.channels();
    const liveEntry = getConfig().sites[ref.key];
    const ua = liveEntry && liveEntry.type === 'live' ? liveEntry.ua : undefined;
    const all = liveChannelsToEntries(result.channels, ref.key, ua);
    return {
      items: all.items.slice(startIndex, startIndex + limit),
      totalCount: all.totalCount,
    };
  }

  return { items: [], totalCount: 0 };
}

// ── search ────────────────────────────────────────────────────────────────────

export async function search(
  _state: CatVodClientState,
  _scopeLocation: string,
  query: string,
): Promise<EntryInfo[]> {
  const config = getConfig();
  const results: EntryInfo[] = [];
  for (const [key, entry] of Object.entries(config.sites)) {
    if (entry.type !== 'site') continue;
    const site = entry as SiteEntry;
    if (site.searchable === 0) continue;
    try {
      const spider = getSpider(key);
      if (!spider.search) continue; // Skip if search not supported
      const result = await spider.search(query, 1);
      const entryList = vodListToEntries(
        result.list ?? [],
        key,
        '', // No tid for search results
        result.total,
      );
      results.push(...entryList.items);
    } catch (_) {}
  }
  return results;
}

// ── getPlaybackSources ────────────────────────────────────────────────────────

export async function getPlaybackSources(
  _state: CatVodClientState,
  itemRef: string,
): Promise<PlaybackSource[]> {
  const ref = decodeRef(itemRef);
  if (ref.type === 'unsupported') throw new Error("Unsupported ref type");

  const spider = getSpider(ref.key);

  // Direct episode ref → resolve flag and URL from vod detail
  if (ref.type === "ep") {
    const detailResult = await spider.detail([ref.id]);
    const detail = detailResult.list?.[0];
    if (!detail) throw new Error("Vod not found");
    const eps = parseEpisodes(detail);
    const ep = eps[ref.epIndex];
    if (!ep) throw new Error("Episode not found");
    const result = await spider.play(ep.flag, ep.url);
    return [playResultToSource(result)];
  }

  // Vod ref with single episode → resolve inline
  if (ref.type === "vod") {
    const detailResult = await spider.detail([ref.id]);
    const detail = detailResult.list?.[0];
    if (!detail) throw new Error("No episodes found");
    const eps = parseEpisodes(detail);
    if (eps.length === 0) throw new Error("No episodes found");
    const result = await spider.play(eps[0].flag, eps[0].url);
    return [playResultToSource(result)];
  }

  // Live channel → resolve via spider
  if (ref.type === "live") {
    if (!spider.channels) throw new Error("Live source has no channels");
    const result = await spider.channels();
    const channel = result.channels[ref.channelIndex];
    if (!channel) throw new Error("Channel not found");
    const playResult = await spider.play('', channel.url);
    return [playResultToSource(playResult)];
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
        type: "Folder" as const,
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
