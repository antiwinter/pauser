/**
 * instance.ts — Emby provider instance implementation.
 * Mirrors EmbyProviderInstance.kt.
 */
import { EmbyApi, BROWSE_FIELDS, DETAIL_FIELDS } from './api.js';
import { toListItem } from './mapper.js';
import { imageUrl, resolvePlaybackUrl, playMethod } from './urls.js';
import { playbackMimeTypeFromContainers } from '../src/playbackMimeType.js';
import type { DeviceProfile } from './dto.js';
import type {
  EntryDetail,
  EntryInfo,
  EntryList,
  PlaybackSpec,
  SubtitleTrack,
  PlatformCapabilities,
} from '../src/types.js';

const CONTAINER_TYPES = new Set([
  'Folder', 'BoxSet', 'MusicAlbum', 'MusicArtist',
  'Playlist', 'CollectionFolder', 'UserView',
]);
const NON_PLAYABLE_TYPES = new Set([
  ...CONTAINER_TYPES, 'Series', 'Season',
]);

export interface EmbyCredentials {
  baseUrl: string;
  userId: string;
  accessToken: string;
  serverId?: string | null;
}

export interface EmbyInstanceState {
  credentials: EmbyCredentials;
  deviceProfile: DeviceProfile;
  capabilities: PlatformCapabilities;
}

export async function listEntry(
  state: EmbyInstanceState,
  location: string | null,
  startIndex: number,
  limit: number,
): Promise<EntryList> {
  const { credentials } = state;
  const api = new EmbyApi(credentials.baseUrl, credentials.accessToken, credentials.userId);

  if (location === null) {
    const views = await api.getViews();
    return {
      items: views.Items.map((i) => toListItem(i, credentials.baseUrl, credentials.accessToken)).filter(Boolean) as EntryInfo[],
      totalCount: views.TotalRecordCount,
    };
  } else {
    const result = await api.getItems({
      parentId: location,
      recursive: false,
      startIndex,
      limit,
      fields: BROWSE_FIELDS,
    });
    return {
      items: result.Items.map((i) => toListItem(i, credentials.baseUrl, credentials.accessToken)).filter(Boolean) as EntryInfo[],
      totalCount: result.TotalRecordCount,
    };
  }
}

export async function search(
  state: EmbyInstanceState,
  scopeLocation: string,
  query: string,
): Promise<EntryInfo[]> {
  const q = query.trim();
  if (!q) return [];
  const { credentials } = state;
  const api = new EmbyApi(credentials.baseUrl, credentials.accessToken, credentials.userId);
  const result = await api.getItems({
    parentId: scopeLocation || null,
    recursive: true,
    searchTerm: q,
    startIndex: 0,
    limit: 100,
    fields: BROWSE_FIELDS,
  });
  return result.Items.map((i) => toListItem(i, credentials.baseUrl, credentials.accessToken)).filter(Boolean) as EntryInfo[];
}

export async function getDetail(
  state: EmbyInstanceState,
  itemRef: string,
): Promise<EntryDetail> {
  const { credentials } = state;
  const api = new EmbyApi(credentials.baseUrl, credentials.accessToken, credentials.userId);
  const item = await api.getItem(itemRef, DETAIL_FIELDS);
  const id = item.Id ?? itemRef;

  const logoTag = item.ImageTags?.['Logo'];
  const logo = logoTag
    ? imageUrl({ baseUrl: credentials.baseUrl, itemId: id, imageType: 'Logo', tag: logoTag, accessToken: credentials.accessToken, maxHeight: 160 })
    : null;

  const backdrop = (item.BackdropImageTags ?? []).map((tag, index) =>
    imageUrl({ baseUrl: credentials.baseUrl, itemId: id, imageType: 'Backdrop', tag, accessToken: credentials.accessToken, maxHeight: 1080, index })
  );

  const bitrate = item.MediaSources?.[0]?.Bitrate ?? null;

  const externalUrls = (item.ExternalUrls ?? []).flatMap((u) =>
    u.Name && u.Url ? [{ name: u.Name, url: u.Url }] : []
  );

  const streams = (item.MediaStreams ?? []).map((s) => ({
    index: s.Index ?? 0,
    type: s.Type ?? '',
    codec: s.Codec ?? null,
    title: s.DisplayTitle ?? null,
    language: s.Language ?? null,
    isDefault: s.IsDefault ?? false,
    isForced: s.IsForced ?? false,
  }));

  const isMedia = !NON_PLAYABLE_TYPES.has(item.Type ?? '');

  return {
    title: item.Name ?? itemRef,
    overview: item.Overview ?? null,
    logo,
    backdrop,
    isMedia,
    rating: item.CommunityRating ?? null,
    bitrate,
    externalUrls,
    year: item.ProductionYear ?? null,
    providerIds: item.ProviderIds ?? {},
    streams,
    etag: item.Etag ?? null,
  };
}

const BITMAP_CODECS = new Set([
  'pgssub', 'hdmv_pgs_subtitle', 'dvd_subtitle', 'dvbsub',
  'dvb_subtitle', 'xsub', 'microdvd',
]);

export async function getPlaybackSpec(
  state: EmbyInstanceState,
  itemRef: string,
  startMs: number,
): Promise<PlaybackSpec> {
  const { credentials, deviceProfile, capabilities } = state;
  const api = new EmbyApi(credentials.baseUrl, credentials.accessToken, credentials.userId);

  const startTicks = startMs > 0 ? startMs * 10_000 : undefined;
  const info = await api.getPlaybackInfo(itemRef, {
    Id: itemRef,
    UserId: credentials.userId,
    MaxStreamingBitrate: 120_000_000,
    StartTimeTicks: startTicks,
    DeviceProfile: deviceProfile,
    EnableDirectPlay: true,
    EnableDirectStream: true,
    EnableTranscoding: true,
    AutoOpenLiveStream: true,
    AllowVideoStreamCopy: true,
    AllowAudioStreamCopy: true,
  });

  const source = info.MediaSources[0];
  if (!source) throw new Error('No media sources');

  const url = resolvePlaybackUrl(credentials.baseUrl, source);
  const method = playMethod(source);
  const mimeType = playbackMimeTypeFromContainers(source.TranscodingContainer, source.Container);
  const item = await api.getItem(itemRef);
  const title = item.Name ?? itemRef;
  const headers = { 'X-Emby-Token': credentials.accessToken };

  const subtitleTracks: SubtitleTrack[] = (source.MediaStreams ?? []).flatMap((stream) => {
    const index = stream.Index;
    if (stream.Type !== 'Subtitle' || index == null) return [];

    const label = stream.DisplayTitle ?? stream.Language ?? `Subtitle ${index}`;
    const codec = stream.Codec?.toLowerCase() ?? '';
    const isBitmapCodec = BITMAP_CODECS.has(codec);
    const ext = codec === 'ass' || codec === 'ssa' ? 'ass'
              : codec === 'vtt' || codec === 'webvtt' ? 'vtt'
              : 'srt';

    let externalRef: string | null = null;
    if (stream.IsExternal) {
      externalRef = `${credentials.baseUrl}/Videos/${itemRef}/Subtitles/${index}/Stream.${ext}`;
    } else if (isBitmapCodec) {
      if (capabilities.subtitleFormats.includes('ass')) {
        externalRef = `${credentials.baseUrl}/Videos/${itemRef}/Subtitles/${index}/Stream.ass`;
      } else {
        return []; // skip bitmap-codec subtitles we can't render
      }
    }

    return [{
      trackId: String(index),
      label,
      language: stream.Language ?? null,
      isDefault: stream.IsDefault ?? false,
      isForced: stream.IsForced ?? false,
      externalRef,
    }];
  });

  const hooksState = {
    itemId: itemRef,
    playMethod: method,
    playSessionId: info.PlaySessionId ?? null,
    mediaSourceId: source.Id ?? null,
    liveStreamId: source.LiveStreamId ?? null,
    baseUrl: credentials.baseUrl,
    userId: credentials.userId,
    accessToken: credentials.accessToken,
    deviceProfile,
  };

  return {
    url,
    headers,
    mimeType,
    title,
    durationMs: null,
    subtitleTracks,
    hooksState,
  };
}
