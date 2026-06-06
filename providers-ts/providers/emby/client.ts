/**
 * client.ts — Emby provider client implementation.
 * Mirrors EmbyProviderInstance.kt.
 */
import { EmbyApi, BROWSE_FIELDS_STR } from './api.js';
import { toListItem } from './mapper.js';
import { resolvePlaybackUrl, playMethod, normalizeBaseUrl } from './urls.js';
import { fmtToMime } from '../../utils/mimes.js';
import { buildDeviceProfile } from './device-profile.js';
import type { DeviceProfile } from './dto.js';
import type {
  EntryInfo,
  EntryList,
  PlaybackSpec,
  SubtitleTrack,
  PlatformInfo,
  ValidationResult,
} from '../../utils/types.js';

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

export interface EmbyClientState {
  rawCredentials: Record<string, string>;  // raw form values — used by test()
  credentials?: EmbyCredentials;           // populated by test()
  deviceProfile: DeviceProfile;
  capabilities: PlatformInfo;
}

// ── test() ───────────────────────────────────────────────────────────────────

export async function test(
  state: EmbyClientState,
  deviceInfo: PlatformInfo,
  deviceName: string,
): Promise<ValidationResult> {
  try {
    const raw = state.rawCredentials;
    const baseUrl  = normalizeBaseUrl(raw['base_url'] ?? '');
    const username = (raw['username'] ?? '').trim();
    const password = raw['password'] ?? '';

    const unauthApi = new EmbyApi(baseUrl, '', '');
    const auth = await unauthApi.authenticateByName({ Username: username, Pw: password });
    const token  = auth.AccessToken;
    const userId = auth.User?.Id;
    if (!token)  throw new Error('No access token returned');
    if (!userId) throw new Error('No user id returned');

    const api = new EmbyApi(baseUrl, token, userId);
    const info = await api.getSystemInfo();

    const name = info.ServerName ?? baseUrl;

    // Populate enriched credentials for subsequent calls
    state.credentials = {
      baseUrl,
      userId,
      accessToken: token,
      serverId: info.Id ?? '',
    };
    state.deviceProfile = buildDeviceProfile(state.capabilities, deviceName);

    return {
      success: true,
      fields: {
        base_url:     baseUrl,
        user_id:      userId,
        access_token: token,
        server_id:    info.Id ?? '',
        name,
      },
    };
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    return { success: false, error: msg };
  }
}

function requireState(state: EmbyClientState): EmbyClientState & { credentials: EmbyCredentials } {
  if (!state.credentials) throw new Error('Emby not authenticated — call test() first');
  return state as EmbyClientState & { credentials: EmbyCredentials };
}

export async function listEntry(
  state: EmbyClientState,
  location: string | null,
  startIndex: number,
  limit: number,
  options?: import('../../utils/types.js').QueryOptions,
): Promise<EntryList> {
  const { credentials } = requireState(state);
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
      recursive: options?.recursive ?? false,
      startIndex,
      limit,
      fields: BROWSE_FIELDS_STR,
      sortBy: options?.sortBy ?? undefined,
      sortOrder: options?.sortOrder ?? undefined,
      includeItemTypes: options?.filterByType ?? undefined,
    });
    return {
      items: result.Items.map((i) => toListItem(i, credentials.baseUrl, credentials.accessToken)).filter(Boolean) as EntryInfo[],
      totalCount: result.TotalRecordCount,
    };
  }
}

export async function getEntries(
  state: EmbyClientState,
  itemRefs: string[],
): Promise<EntryList> {
  const { credentials } = requireState(state);
  const api = new EmbyApi(credentials.baseUrl, credentials.accessToken, credentials.userId);
  const items = await Promise.all(
    itemRefs.map(async (ref) => {
      try {
        const item = await api.getItem(ref, BROWSE_FIELDS_STR);
        return toListItem(item, credentials.baseUrl, credentials.accessToken);
      } catch {
        return null;
      }
    }),
  );
  const valid = items.filter(Boolean) as EntryInfo[];
  return { items: valid, totalCount: valid.length };
}

export async function search(
  state: EmbyClientState,
  scopeLocation: string,
  query: string,
): Promise<EntryInfo[]> {
  const q = query.trim();
  if (!q) return [];
  const { credentials } = requireState(state);
  const api = new EmbyApi(credentials.baseUrl, credentials.accessToken, credentials.userId);
  const result = await api.getItems({
    parentId: scopeLocation || null,
    includeItemTypes: 'Series,Folder',
    recursive: true,
    searchTerm: q,
    startIndex: 0,
    limit: 100,
    fields: BROWSE_FIELDS_STR,
  });
  return result.Items.map((i) => toListItem(i, credentials.baseUrl, credentials.accessToken)).filter(Boolean) as EntryInfo[];
}

const BITMAP_CODECS = new Set([
  'pgssub', 'hdmv_pgs_subtitle', 'dvd_subtitle', 'dvbsub',
  'dvb_subtitle', 'xsub', 'microdvd',
]);

export async function getPlaybackSpec(
  state: EmbyClientState,
  itemRef: string,
  startMs: number,
): Promise<PlaybackSpec> {
  const s = requireState(state); const credentials = s.credentials; const deviceProfile = s.deviceProfile; const capabilities = s.capabilities;
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
  const rawContainer =
    (source.TranscodingContainer && source.TranscodingContainer.trim()) ||
    (source.Container && source.Container.trim()) ||
    '';
  const mimeType = rawContainer ? fmtToMime(rawContainer) : null;
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

  const mediaCodecs = (source.MediaStreams ?? [])
    .filter((s) => s.Type === 'Video' || s.Type === 'Audio')
    .map((s) => ({
      codec: (s.Codec ?? '').toLowerCase(),
      bitDepth: s.BitDepth ?? null,
    }))
    .filter((s) => s.codec);

  return {
    url,
    headers,
    mimeType,
    subtitleTracks,
    hooksState,
    mediaCodecs,
  };
}
