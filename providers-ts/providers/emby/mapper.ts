/**
 * mapper.ts — Maps Emby API DTOs to the OpenTune provider contract types.
 * Mirrors EmbyProviderInstance.toListItem() in Kotlin.
 *
 * All fields come from BROWSE_FIELDS (api.ts) — the single source of truth.
 * MediaSources / MediaStreams are excluded: they are heavy payloads only
 * fetched at playback time via getPlaybackInfo().
 */
import type { BaseItemDto } from './api.js';
import type { EntryInfo, EntryType } from '../../utils/types.js';
import { imageUrl } from './urls.js';

const CONTAINER_TYPES = new Set([
  'BoxSet', 'MusicAlbum', 'MusicArtist',
  'Playlist', 'CollectionFolder', 'UserView',
]);

const METADATA_TYPES = new Set([
  'Person', 'Genre', 'Studio', 'Year', 'Tag', 'MusicGenre',
  'MusicArtist', 'AlbumArtist', 'CollectionFolder',
]);

/** Map Emby types to OpenTune contract types.
 * Known media types (Movie, Episode, Video, Audio, Photo, Image,
 * Program, Trailer, MusicVideo, Book, Recording) pass through as-is.
 * Container types → Folder. Everything else → Unknown. */
function resolveType(typeStr: string): EntryType {
  if (CONTAINER_TYPES.has(typeStr)) return 'Folder';
  // Media types: Movie, Video, Audio, Image, Photo, Program, Trailer,
  //              MusicVideo, Book, Recording, LiveTvChannel, etc.
  return typeStr as EntryType;
}

export function toListItem(
  item: BaseItemDto,
  baseUrl: string,
  accessToken: string,
): EntryInfo | null {
  const id = item.Id as string | undefined;
  if (!id) return null;

  const type = item.Type as string | undefined;
  const typeStr = type ?? '';
  const entryType = resolveType(typeStr);

  const imageTags = item.ImageTags as Record<string, string> | null | undefined;
  const primaryTag = imageTags?.['Primary'];
  const cover = primaryTag
    ? imageUrl({ baseUrl, itemId: id, imageType: 'Primary', tag: primaryTag, accessToken })
    : null;

  // Logo
  const logoTag = imageTags?.['Logo'];
  const logo = logoTag
    ? imageUrl({ baseUrl, itemId: id, imageType: 'Logo', tag: logoTag, accessToken, maxHeight: 160 })
    : null;

  // Backdrops
  const backdropTags = item.BackdropImageTags as string[] | null | undefined;
  const backdrop = (backdropTags ?? []).map((tag, index) =>
    imageUrl({ baseUrl, itemId: id, imageType: 'Backdrop', tag, accessToken, maxHeight: 1080, index })
  );

  const ud = item.UserData as { PlaybackPositionTicks?: number; IsFavorite?: boolean; Played?: boolean } | null | undefined;
  return {
    id,
    title: (item.Name as string | undefined) ?? id,
    type: entryType,
    cover,
    userData: ud
      ? {
          positionMs: Math.floor((ud.PlaybackPositionTicks ?? 0) / 10_000),
          isFavorite: ud.IsFavorite ?? false,
          played:     ud.Played ?? false,
        }
      : null,
    originalTitle:   item.OriginalTitle as string | null | undefined,
    genres:          item.Genres as string[] | null | undefined,
    communityRating: item.CommunityRating as number | null | undefined,
    studios:         (item.Studios as Array<{ Name?: string | null }> | null | undefined)
                       ?.map((s) => s.Name ?? '').filter(Boolean) ?? null,
    etag:            item.Etag as string | null | undefined,
    indexNumber:     item.IndexNumber as number | null | undefined,
    overview:        item.Overview as string | null | undefined,
    childCount:      item.ChildCount as number | null | undefined,
    collectionType:  (item.CollectionType as string | null | undefined)?.toLowerCase() ?? null,
    parentId:        item.ParentId as string | null | undefined,
    seriesId:        item.SeriesId as string | null | undefined,
    seasonNumber:    item.ParentIndexNumber as number | null | undefined,
    logo,
    backdrop,
    year:            item.ProductionYear as number | null | undefined,
    durationMs:      item.RunTimeTicks != null ? Math.floor((item.RunTimeTicks as number) / 10_000) : null,
    officialRating:  item.OfficialRating as string | null | undefined,
  };
}
