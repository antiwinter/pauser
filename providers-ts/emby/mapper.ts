/**
 * mapper.ts — Maps Emby API DTOs to the OpenTune provider contract types.
 * Mirrors EmbyProviderInstance.toListItem() in Kotlin.
 */
import type { BaseItemDto } from './dto.js';
import type { MediaListItem, MediaEntryKind } from '../src/types.js';
import { imageUrl } from './urls.js';

const CONTAINER_TYPES = new Set([
  'Folder', 'BoxSet', 'MusicAlbum', 'MusicArtist',
  'Playlist', 'CollectionFolder', 'UserView',
]);

export function toListItem(
  item: BaseItemDto,
  baseUrl: string,
  accessToken: string,
): MediaListItem | null {
  const id = item.Id;
  if (!id) return null;

  const type = item.Type ?? '';
  let kind: MediaEntryKind;
  if (type === 'Series')            kind = 'Series';
  else if (type === 'Season')       kind = 'Season';
  else if (type === 'Episode')      kind = 'Episode';
  else if (CONTAINER_TYPES.has(type)) kind = 'Folder';
  else                              kind = 'Playable';

  const primaryTag = item.ImageTags?.['Primary'];
  const coverUrl = primaryTag
    ? imageUrl({ baseUrl, itemId: id, imageType: 'Primary', tag: primaryTag, accessToken })
    : null;

  const ud = item.UserData;
  return {
    id,
    title: item.Name ?? id,
    kind,
    coverUrl,
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
  };
}
