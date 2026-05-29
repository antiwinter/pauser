import type { EntryInfo, EntryDetail, ExternalUrl } from '../../utils/types.js';

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

// ── DTO types ─────────────────────────────────────────────────────────────────

export interface CatVodItem {
  vod_id: string | number;
  vod_name?: string;
  vod_pic?: string;
  vod_blurb?: string;
  vod_content?: string;
  vod_score?: string;
  vod_year?: string;
  type_name?: string;
}

export interface CatVodDetail extends CatVodItem {
  vod_play_from?: string;
  vod_play_url?: string;
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function splitField(s?: string): string[] {
  return (s ?? '').split('$$$').map((p) => p.trim()).filter(Boolean);
}
