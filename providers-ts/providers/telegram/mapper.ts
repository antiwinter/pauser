/**
 * mapper.ts — maps shim JSON responses to OpenTune EntryInfo / EntryDetail / PlaybackSpec.
 */
import type {
  EntryInfo,
  EntryDetail,
  PlaybackSpec,
  SubtitleTrack,
  EntryType,
} from '../../utils/types.js';
import type {
  ShimEntryItem,
  ShimEntryList,
  ShimEntryDetail,
  ShimPlaybackSpec,
} from './dto.js';

export function parseEntryList(raw: string): { items: EntryInfo[]; totalCount: number } {
  const obj: ShimEntryList = JSON.parse(raw);
  const items: EntryInfo[] = (obj.items ?? obj.list ?? []).map(toEntryInfo);
  return { items, totalCount: obj.totalCount ?? obj.total ?? items.length };
}

export function parseEntryDetail(raw: string): EntryDetail {
  const obj: ShimEntryDetail = JSON.parse(raw);
  return {
    title: obj.title ?? '',
    overview: obj.overview ?? null,
    logo: obj.logo ?? null,
    backdrop: obj.backdrop ?? [],
    isMedia: obj.isMedia ?? false,
    rating: obj.rating ?? null,
    bitrate: obj.bitrate ?? null,
    externalUrls: obj.externalUrls ?? [],
    year: obj.year ?? null,
    providerIds: obj.providerIds ?? {},
    streams: (obj.streams ?? []).map((s) => ({
      index: s.index ?? 0,
      type: s.type ?? '',
      codec: s.codec ?? null,
      title: s.title ?? null,
      language: s.language ?? null,
      isDefault: s.isDefault ?? false,
      isForced: s.isForced ?? false,
    })),
    etag: obj.etag ?? null,
  };
}

export function parsePlaybackSpec(raw: string): PlaybackSpec {
  const obj: ShimPlaybackSpec = JSON.parse(raw);
  return {
    url: obj.url ?? '',
    headers: obj.headers ?? {},
    mimeType: obj.mimeType ?? null,
    title: obj.title ?? 'Telegram Video',
    durationMs: obj.durationMs ?? null,
    subtitleTracks: (obj.subtitleTracks ?? []) as SubtitleTrack[],
    hooksState: obj.hooksState ?? {},
  };
}

// ── Internal ────────────────────────────────────────────────────────────────

function toEntryInfo(item: ShimEntryItem): EntryInfo {
  return {
    id: item.id,
    title: item.title,
    type: normalizeEntryType(item.type),
    cover: item.cover ?? null,
    userData: item.userData ?? null,
    originalTitle: item.originalTitle ?? null,
    genres: item.genres ?? null,
    communityRating: item.communityRating ?? null,
    studios: item.studios ?? null,
    childCount: item.childCount ?? null,
  };
}

function normalizeEntryType(raw: string | undefined): EntryType {
  if (!raw) return 'Other';
  const lower = raw.toLowerCase();
  if (lower === 'folder') return 'Folder';
  if (lower === 'series') return 'Series';
  if (lower === 'season') return 'Season';
  if (lower === 'episode') return 'Episode';
  if (lower === 'playable') return 'Playable';
  if (lower === 'digipak') return 'Digipak';
  if (lower === 'image') return 'Image';
  return 'Other';
}
