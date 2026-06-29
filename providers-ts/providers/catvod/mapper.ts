import type { EntryInfo, EntryList, PlaybackSource, SubtitleTrack } from '../../utils/types.js';
import type { CatVodItem, CatVodDetail, CatVodCategory, CatVodSub, CatVodPlayResult, M3UChannel } from './spider/types.js';
import { encodeRef } from './ref.js';

// ── CatVod → OpenTune Conversion Functions ───────────────────────────────────
// Centralized mapping layer — all handlers return CatVod types, these functions
// convert them to OpenTune types

/**
 * Convert CatVod category list (home screen) to OpenTune folder entries
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
 * Convert CatVod item list to OpenTune entries
 */
export function vodListToEntries(
  items: CatVodItem[],
  siteKey: string,
  tid: string,
  totalCount?: number,
  categoryName?: string,
): EntryList {
  return {
    items: items.map((item) => vodItemToEntry(item, siteKey, tid, categoryName)),
    totalCount: totalCount ?? items.length,
  };
}

/**
 * Convert IPTV M3U channels to OpenTune entries, merging duplicate names.
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
      sources: sources.length > 1 ? sources : undefined,
    });
  }

  return { items, totalCount: items.length };
}

/**
 * Convert CatVod play result to a single PlaybackSource
 */
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
  return {
    url: result.play_url ?? result.url ?? '',
    headers: result.header ?? {},
    mimeType: result.type ?? null,
    subtitleTracks,
    mediaCodecs: [],
  };
}

// ── Legacy Item-Level Converters ─────────────────────────────────────────────
// These are still used by the list converters above

// ── Legacy Item-Level Converters ─────────────────────────────────────────────
// These are still used by the list converters above

const SERIES_RE = /剧|劇|综艺|綜藝|动漫|動漫|番剧|番劇|动画|動畫/;
const MOVIE_RE  = /电影|電影/;

function classifyVodType(vodId: string, typeName: string | undefined): 'Folder' | 'Series' | 'Movie' {
  // msearch: IDs are meta-search launchers — clicking them fans out to a
  // cross-source search in listEntry; presented as a Folder.
  if (vodId.startsWith('msearch:')) return 'Folder';
  const t = (typeName ?? '').trim();
  if (SERIES_RE.test(t)) return 'Series';
  if (MOVIE_RE.test(t))  return 'Movie';
  return 'Movie';
}

export function vodItemToEntry(item: CatVodItem, siteKey: string, tid: string, categoryName?: string): EntryInfo {
  const vodId = String(item.vod_id);
  // Prefer per-item type_name; fall back to the owning category's name (most
  // CMS sites omit type_name in category responses, so the home class cache
  // is the reliable signal that an item from category 2 is a TV series).
  const typeNameForClass = item.type_name ?? categoryName;
  const type = classifyVodType(vodId, typeNameForClass);
  return {
    ref: encodeRef({ type: 'vod', key: siteKey, tid, id: vodId }),
    title: item.vod_name ?? vodId,
    type,
    cover: item.vod_pic ?? null,
    overview: item.vod_blurb ?? item.vod_content ?? null,
    communityRating: item.vod_score ? parseFloat(item.vod_score) : null,
    genres: typeNameForClass ? [typeNameForClass] : null,
  };
}

export function vodDetailToEntryInfo(item: CatVodDetail): EntryInfo {
  const sources   = splitField(item.vod_play_from);
  const urlGroups = splitField(item.vod_play_url);

  const episodeCount = sources.flatMap((src, i) =>
    (urlGroups[i] ?? '').split('#')
      .map((ep) => ep.trim())
      .filter(Boolean)
  ).length;

  return {
    ref:         item.vod_id ? String(item.vod_id) : '',
    title:       item.vod_name ?? '',
    type:        'Movie',
    cover:       item.vod_pic ?? null,
    overview:    item.vod_content ?? item.vod_blurb ?? null,
    childCount:  episodeCount,
    communityRating: item.vod_score ? parseFloat(item.vod_score) : null,
    genres:      item.type_name ? [item.type_name] : null,
    backdrop:    item.vod_pic ? [item.vod_pic] : [],
    year:        item.vod_year ? parseInt(item.vod_year, 10) : null,
  };
}

// ── Shared episode list ───────────────────────────────────────────────────────

export interface ParsedEpisode {
  flag: string;
  flagIndex: number;
  name: string;
  url: string;
}

export function parseEpisodes(item: CatVodDetail): ParsedEpisode[] {
  const sources   = splitField(item.vod_play_from);
  const urlGroups = splitField(item.vod_play_url);
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

function splitField(s?: string): string[] {
  return (s ?? '').split('$$$').map((p) => p.trim()).filter(Boolean);
}
