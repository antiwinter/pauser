/**
 * mapper.ts — Maps Emby API DTOs to the OpenTune provider contract types.
 * Mirrors EmbyProviderInstance.toListItem() in Kotlin.
 */
import type { BaseItemDto } from './dto.js';
import type { EntryInfo, EntryType, MediaCodecInfo } from '../../utils/types.js';
import { imageUrl } from './urls.js';

const CONTAINER_TYPES = new Set([
  'BoxSet', 'MusicAlbum', 'MusicArtist',
  'Playlist', 'CollectionFolder', 'UserView',
]);

export function toListItem(
  item: BaseItemDto,
  baseUrl: string,
  accessToken: string,
): EntryInfo | null {
  const id = item.Id;
  if (!id) return null;

  const type = item.Type ?? '';
  let entryType: EntryType;
  if (type === 'Series')              entryType = 'Series';
  else if (type === 'Season')         entryType = 'Season';
  else if (type === 'Episode')        entryType = 'Episode';
  else if (type === 'Folder')         entryType = 'Digipak';
  else if (CONTAINER_TYPES.has(type)) entryType = 'Folder';
  else                                entryType = 'Playable';

  const primaryTag = item.ImageTags?.['Primary'];
  const cover = primaryTag
    ? imageUrl({ baseUrl, itemId: id, imageType: 'Primary', tag: primaryTag, accessToken })
    : null;

  // Logo
  const logoTag = item.ImageTags?.['Logo'];
  const logo = logoTag
    ? imageUrl({ baseUrl, itemId: id, imageType: 'Logo', tag: logoTag, accessToken, maxHeight: 160 })
    : null;

  // Backdrops
  const backdrop = (item.BackdropImageTags ?? []).map((tag, index) =>
    imageUrl({ baseUrl, itemId: id, imageType: 'Backdrop', tag, accessToken, maxHeight: 1080, index })
  );

  // Bitrate / duration / dimensions from MediaSources
  const source = item.MediaSources?.[0];
  const bitrate = source?.Bitrate ?? null;
  const durationMs = item.RunTimeTicks != null ? Math.floor(item.RunTimeTicks / 10_000) : null;

  const videoStream = source?.MediaStreams?.find((s) => s.Type === 'Video');
  const width = videoStream?.Width ?? null;
  const height = videoStream?.Height ?? null;

  // Media codecs
  const mediaCodecs: MediaCodecInfo[] = (source?.MediaStreams ?? [])
    .filter((s) => s.Type === 'Video' || s.Type === 'Audio')
    .map((s) => ({
      codec: (s.Codec ?? '').toLowerCase(),
      bitDepth: s.BitDepth ?? null,
    }))
    .filter((s) => s.codec);

  const ud = item.UserData;
  return {
    id,
    title: item.Name ?? id,
    type: entryType,
    cover,
    userData: ud
      ? {
          positionMs: Math.floor((ud.PlaybackPositionTicks ?? 0) / 10_000),
          isFavorite: ud.IsFavorite ?? false,
          played:     ud.Played ?? false,
        }
      : null,
    originalTitle:   item.OriginalTitle ?? null,
    genres:          item.Genres ?? null,
    communityRating: item.CommunityRating ?? null,
    studios:         item.Studios?.map((s) => s.Name ?? '').filter(Boolean) ?? null,
    etag:            item.Etag ?? null,
    indexNumber:     item.IndexNumber ?? null,
    overview:        item.Overview ?? null,
    childCount:      item.ChildCount ?? null,
    collectionType:  item.CollectionType?.toLowerCase() ?? null,
    // new detail fields
    parentId:        item.ParentId ?? null,
    seriesId:        item.SeriesId ?? null,
    seasonNumber:    item.ParentIndexNumber ?? null,
    logo,
    backdrop,
    bitrate,
    year:            item.ProductionYear ?? null,
    durationMs,
    width,
    height,
    officialRating:  item.OfficialRating ?? null,
    mediaCodecs,
  };
}
