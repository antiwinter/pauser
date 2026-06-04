import type { EntryInfo, EntryDetail, EntryList, PlaybackSpec, ExternalUrl, SubtitleTrack } from '../../utils/types.js';
import type { CatVodItem, CatVodDetail, CatVodCategory, CatVodSub, CatVodPlayResult } from './spider/types.js';
import type { M3UChannel } from './iptv.js';

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
      id: JSON.stringify({ type: 'cat', key: siteKey, tid: String(c.type_id) }),
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
  totalCount?: number,
): EntryList {
  return {
    items: items.map((item) => vodItemToEntry(item, siteKey)),
    totalCount: totalCount ?? items.length,
  };
}

/**
 * Convert IPTV M3U channels to OpenTune entries
 */
export function liveChannelsToEntries(channels: M3UChannel[]): EntryList {
  return {
    items: channels.map((ch) => ({
      id: JSON.stringify({ type: 'live', name: ch.name, url: ch.url }),
      title: ch.name,
      type: 'Playable' as const,
      cover: ch.logo ?? null,
    })),
    totalCount: channels.length,
  };
}

/**
 * Convert CatVod play result to OpenTune playback spec
 */
export function playResultToSpec(
  result: CatVodPlayResult,
  title: string = '',
): PlaybackSpec {
  const subtitleTracks: SubtitleTrack[] = (result.subs ?? []).map((sub: CatVodSub, i: number) => ({
    trackId: `catvod-sub-${i}`,
    label: sub.name ?? '',
    language: sub.lang ?? null,
    isDefault: i === 0,
    isForced: false,
    externalRef: sub.url,
  }));
  return {
    url: result.play_url ?? result.url ?? null,
    headers: result.header ?? {},
    mimeType: result.type ?? null,
    bitrate: null,
    title,
    durationMs: null,
    subtitleTracks,
    hooksState: {},
  };
}

// ── Legacy Item-Level Converters ─────────────────────────────────────────────
// These are still used by the list converters above

export function vodItemToEntry(item: CatVodItem, siteKey: string): EntryInfo {
  const vodId = String(item.vod_id);
  // msearch: IDs are meta-search launchers — browsing them yields episodes, so treat as Folder
  const type = vodId.startsWith('msearch:') ? 'Folder' : 'Playable';
  return {
    id: JSON.stringify({ type: 'vod', key: siteKey, id: vodId }),
    title: item.vod_name ?? vodId,
    type,
    cover: item.vod_pic ?? null,
    overview: item.vod_blurb ?? item.vod_content ?? null,
    communityRating: item.vod_score ? parseFloat(item.vod_score) : null,
    genres: item.type_name ? [item.type_name] : null,
  };
}

export function vodDetailToEntryDetail(item: CatVodDetail): EntryDetail {
  const sources   = splitField(item.vod_play_from);
  const urlGroups = splitField(item.vod_play_url);

  const externalUrls: ExternalUrl[] = sources.flatMap((src, i) =>
    (urlGroups[i] ?? '').split('#')
      .map((ep) => ep.trim())
      .filter(Boolean)
      .map((ep) => {
        const dollar = ep.indexOf('$');
        const epName = dollar >= 0 ? ep.slice(0, dollar) : ep;
        const epUrl  = dollar >= 0 ? ep.slice(dollar + 1) : ep;
        return { name: `${src} / ${epName}`, url: epUrl };
      })
  );

  const totalEps = externalUrls.length;

  return {
    title:       item.vod_name ?? '',
    overview:    item.vod_content ?? item.vod_blurb ?? null,
    logo:        null,
    backdrop:    item.vod_pic ? [item.vod_pic] : [],
    isMedia:     totalEps <= 1,
    rating:      item.vod_score ? parseFloat(item.vod_score) : null,
    bitrate:     null,
    externalUrls,
    year:        item.vod_year ? parseInt(item.vod_year, 10) : null,
    providerIds: {},
    streams:     [],
    etag:        null,
  };
}

// ── Shared episode list ───────────────────────────────────────────────────────

export interface ParsedEpisode {
  flag: string;
  name: string;
  url: string;
}

export function parseEpisodes(item: CatVodDetail): ParsedEpisode[] {
  const sources   = splitField(item.vod_play_from);
  const urlGroups = splitField(item.vod_play_url);
  const episodes: ParsedEpisode[] = [];

  for (let i = 0; i < sources.length; i++) {
    const flag = sources[i];
    for (const ep of (urlGroups[i] ?? '').split('#')) {
      const trimmed = ep.trim();
      if (!trimmed) continue;
      const dollar = trimmed.indexOf('$');
      const name   = dollar >= 0 ? trimmed.slice(0, dollar) : trimmed;
      const url    = dollar >= 0 ? trimmed.slice(dollar + 1) : trimmed;
      episodes.push({ flag, name, url });
    }
  }
  return episodes;
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function splitField(s?: string): string[] {
  return (s ?? '').split('$$$').map((p) => p.trim()).filter(Boolean);
}
