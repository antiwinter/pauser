import type { EntryInfo, EntryList, PlaybackSource, SubtitleTrack } from '../../utils/types.js';
import type { CatVodItem, CatVodCategory, CatVodSub, CatVodPlayResult, M3UChannel } from './spider/types.js';
import { encodeRef } from './ref.js';

// ── CatVod → Insomnia Conversion Functions ───────────────────────────────────
// Centralized mapping layer — all handlers return CatVod types, these functions
// convert them to Insomnia types

/**
 * Convert CatVod category list (home screen) to Insomnia folder entries
 */
export function categoryListToFolders(
  categories: CatVodCategory[],
  siteKey: string,
): EntryList {
  return {
    items: categories.map((c) => ({
      ref: encodeRef({ type: 'cat', key: siteKey, tid: String(c.type_id) }),
      title: c.type_name ?? String(c.type_id),
      type: 'Folder' as const,
      cover: null,
    })),
    totalCount: categories.length,
  };
}

/**
 * Convert CatVod item list to Insomnia entries
 */
export function vodListToEntries(
  items: CatVodItem[],
  siteKey: string,
  tid: string,
  totalCount?: number,
): EntryList {
  return {
    items: items.map((item) => vodItemToEntry(item, siteKey, tid)),
    totalCount: totalCount ?? items.length,
  };
}

/**
 * Convert IPTV M3U channels to Insomnia entries, merging duplicate names.
 * Channels with the same name get merged into one entry with multiple sources.
 * Logo: the URL that appears most frequently wins.
 *
 * If [ua] is supplied it is applied to every merged source — matches the header that
 * [iptv.play] would attach, so callers using EntryInfo.sources get the same behaviour
 * as callers going through getPlaybackSources → spider.play.
 */
export function liveChannelsToEntries(channels: M3UChannel[], liveKey: string, ua?: string): EntryList {
  const groups = new Map<string, { urls: string[]; logoCounts: Map<string, number>; firstIndex: number }>();

  for (const [i, ch] of channels.entries()) {
    let g = groups.get(ch.name);
    if (!g) {
      g = { urls: [], logoCounts: new Map(), firstIndex: i };
      groups.set(ch.name, g);
    }
    if (!g.urls.includes(ch.url)) g.urls.push(ch.url);
    if (ch.logo) {
      g.logoCounts.set(ch.logo, (g.logoCounts.get(ch.logo) ?? 0) + 1);
    }
  }

  const headers: Record<string, string> = ua ? { 'User-Agent': ua } : {};

  const items: EntryInfo[] = [];
  for (const [, g] of groups) {
    const name = channels[g.firstIndex].name;
    const bestLogo = [...g.logoCounts.entries()]
      .sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;

    const sources: PlaybackSource[] = g.urls.map(url => ({
      url,
      headers,
      mimeType: null,
      subtitleTracks: [],
      mediaCodecs: [],
    }));

    items.push({
      ref: encodeRef({ type: 'live', key: liveKey, channelIndex: g.firstIndex }),
      title: name,
      type: 'LiveChannel' as const,
      cover: bestLogo,
      sources,
    });
  }

  return { items, totalCount: items.length };
}

/**
 * Convert CatVod play result to a single PlaybackSource
 */

// Browser UA used when a source returns no User-Agent of its own. Hides the
// player's real identity from hotlink-protecting CDNs that reject non-browser
// UAs; source-supplied UAs always take precedence.
const FALLBACK_UA =
  'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

export function playResultToSource(
  result: CatVodPlayResult,
): PlaybackSource {
  const subtitleTracks: SubtitleTrack[] = (result.subs ?? []).map((sub: CatVodSub, i: number) => ({
    trackId: `catvod-sub-${i}`,
    label: sub.name ?? '',
    language: sub.lang ?? null,
    isDefault: i === 0,
    isForced: false,
    externalRef: sub.url,
  }));
  const headers: Record<string, string> = { ...result.header };
  if (!headers['User-Agent'] && !headers['user-agent']) {
    headers['User-Agent'] = FALLBACK_UA;
  }
  return {
    url: result.play_url ?? result.url ?? '',
    headers,
    mimeType: result.type ?? null,
    subtitleTracks,
    mediaCodecs: [],
  };
}

// ── Legacy Item-Level Converters ─────────────────────────────────────────────
// These are still used by the list converters above

export function vodItemToEntry(
  item: CatVodItem | null | undefined,
  siteKey: string,
  tid: string,
  itemRef?: string,
  fallbackVodId?: string,
): EntryInfo {
  const vodId = String(item?.vod_id ?? fallbackVodId ?? itemRef ?? '');
  // msearch: IDs are Douban placeholders — browsing them fans out to a
  // cross-source search in listEntry, so they stay Folders. Every other vod
  // is a Digipak: listEntry resolves it via spider.detail into a flat episode
  // list, so a movie is just a single-episode Digipak and a series has N.
  const type = vodId.startsWith('msearch:') ? 'Folder' : 'Digipak';
  const episodes = item ? parseEpisodes(item) : [];
  const childCount = episodes.length;
  const quality = episodes[0]?.name.trim() || item?.vod_remarks?.trim() || null;
  return {
    ref: itemRef ?? encodeRef({ type: 'vod', key: siteKey, tid, id: vodId }),
    title: item?.vod_name ?? vodId,
    type,
    cover: item?.vod_pic?.trim() || null,
    overview: cleanOverview(item?.vod_blurb) ?? cleanOverview(item?.vod_content),
    childCount: childCount > 0 ? childCount : null,
    communityRating: Number.isFinite(Number(item?.vod_score)) ? Number(item?.vod_score) : null,
    genres: splitNames(item?.type_name),
    actors: splitNames(item?.vod_actor),
    directors: splitNames(item?.vod_director),
    areas: splitNames(item?.vod_area),
    languages: splitNames(item?.vod_lang),
    backdrop: [],
    year: Number.isFinite(Number.parseInt(item?.vod_year ?? '', 10)) ? Number.parseInt(item?.vod_year ?? '', 10) : null,
    quality,
  };
}

// ── Shared episode list ───────────────────────────────────────────────────────

export interface ParsedEpisode {
  flag: string;
  flagIndex: number;
  name: string;
  url: string;
}

export function parseEpisodes(item: CatVodItem): ParsedEpisode[] {
  const { sources, urlGroups } = alignPlayFields(item);
  const episodes: ParsedEpisode[] = [];

  for (let flagIndex = 0; flagIndex < sources.length; flagIndex++) {
    const flag = sources[flagIndex];
    for (const ep of (urlGroups[flagIndex] ?? '').split('#')) {
      const trimmed = ep.trim();
      if (!trimmed) continue;
      const dollar = trimmed.indexOf('$');
      const name   = dollar >= 0 ? trimmed.slice(0, dollar) : trimmed;
      const url    = dollar >= 0 ? trimmed.slice(dollar + 1) : trimmed;
      episodes.push({ flag, flagIndex, name, url });
    }
  }
  return episodes;
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function splitNames(value?: string | null): string[] | null {
  const names = (value ?? '').split(/[\s,，、/]+/).map((p) => p.trim()).filter(Boolean);
  return names.length > 0 ? names : null;
}

function cleanOverview(value?: string | null): string | null {
  const trimmed = value?.replace(/<[^>]+>/g, '').trim();
  return trimmed ? trimmed : null;
}


function splitField(s?: string): string[] {
  return (s ?? '').split('$$$').map((p) => p.trim()).filter(Boolean);
}

// Splits vod_play_from / vod_play_url into aligned per-flag groups. Many sites
// omit vod_play_from entirely when there is a single play source, so a literal
// split would yield zero sources and drop every episode — pad with empty-flag
// entries to match the url groups in that case.
function alignPlayFields(item: CatVodItem): { sources: string[]; urlGroups: string[] } {
  const sources   = splitField(item.vod_play_from);
  const urlGroups = splitField(item.vod_play_url);
  while (sources.length < urlGroups.length) sources.push('');
  return { sources, urlGroups };
}
