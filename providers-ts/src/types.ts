/**
 * types.ts — contract types shared between all provider implementations.
 *
 * This file mirrors the Kotlin provider-api contracts; both sides must stay in sync.
 * The `host` global is injected by QuickJsEngine before the bundle runs.
 */

// ── Host API ──────────────────────────────────────────────────────────────────

export interface HttpRequestArgs {
  url: string;
  headers?: Record<string, string>;
  body?: string;
  contentType?: string;
}

export interface HttpResponse {
  status: number;
  body: string;
  headers: Record<string, string>;
}

export interface HostAPI {
  http: {
    get(args: HttpRequestArgs): Promise<HttpResponse>;
    post(args: HttpRequestArgs): Promise<HttpResponse>;
  };
  crypto: {
    sha256(args: { input: string }): Promise<string>;
  };
  platform: {
    getPlatformInfo(args?: null): Promise<PlatformInfo>;
  };
}

export interface PlatformInfo {
  deviceName: string;
  deviceId: string;
  clientVersion: string;
}

/** Injected by QuickJsEngine before the bundle runs. Available as a global. */
declare global {
  const host: HostAPI;
}

// ── Provider contracts ────────────────────────────────────────────────────────

export type ServerFieldKind = 'text' | 'singleLine' | 'password';

export interface ServerFieldSpec {
  id: string;
  labelKey: string;
  kind: ServerFieldKind;
  required?: boolean;
  sensitive?: boolean;
  order?: number;
  placeholderKey?: string;
}

export type ValidationResult =
  | { success: true; hash: string; displayName: string; fieldsJson: string }
  | { success: false; error: string };

export type MediaEntryKind = 'Folder' | 'Series' | 'Season' | 'Episode' | 'Playable' | 'Other';

export interface MediaUserData {
  positionMs: number;
  isFavorite: boolean;
  played: boolean;
}

export interface MediaListItem {
  id: string;
  title: string;
  kind: MediaEntryKind;
  coverUrl: string | null;
  userData: MediaUserData | null;
  originalTitle?: string | null;
  genres?: string[] | null;
  communityRating?: number | null;
  studios?: string[] | null;
  etag?: string | null;
  indexNumber?: number | null;
}

export interface BrowsePageResult {
  items: MediaListItem[];
  totalCount: number;
}

export interface ExternalUrl {
  name: string;
  url: string;
}

export interface MediaStreamInfo {
  index: number;
  type: string;
  codec: string | null;
  displayTitle: string | null;
  language: string | null;
  isDefault: boolean;
  isForced: boolean;
}

export interface MediaDetailModel {
  title: string;
  overview: string | null;
  logoUrl: string | null;
  backdropImages: string[];
  canPlay: boolean;
  communityRating: number | null;
  bitrate: number | null;
  externalUrls: ExternalUrl[];
  productionYear: number | null;
  providerIds: Record<string, string>;
  mediaStreams: MediaStreamInfo[];
  etag: string | null;
}

export interface PlaybackUrlSpec {
  url: string;
  headers?: Record<string, string>;
  mimeType?: string | null;
}

export interface SubtitleTrack {
  trackId: string;
  label: string;
  language: string | null;
  isDefault: boolean;
  isForced: boolean;
  externalRef: string | null;
}

/**
 * Opaque state blob returned by `resolvePlayback` and passed back into
 * `onPlaybackReady`, `onProgressTick`, `onStop`.
 * Providers store all server-side session parameters here.
 */
export type HooksState = Record<string, unknown>;

export interface PlaybackSpec {
  urlSpec: PlaybackUrlSpec | null;
  displayTitle: string;
  durationMs: number | null;
  subtitleTracks: SubtitleTrack[];
  subtitleHeaders?: Record<string, string>;
  hooksState: HooksState;
}

export interface CodecCapabilities {
  videoMimes: string[];
  audioMimes: string[];
  subtitleFormats: string[];
  maxVideoPixels: number;
}

// ── Bridge protocol ───────────────────────────────────────────────────────────

/** Exposed on globalThis.opentuneProvider by emby/index.ts */
export interface OpenTuneProviderBridge {
  providesCover: boolean;
  getFieldsSpec(): Promise<ServerFieldSpec[]>;
  validateFields(args: { values: Record<string, string> }): Promise<ValidationResult>;

  init(args: {
    credentials: Record<string, string>;
    capabilities: CodecCapabilities;
  }): Promise<void>;

  loadBrowsePage(args: {
    location: string | null;
    startIndex: number;
    limit: number;
  }): Promise<BrowsePageResult>;

  searchItems(args: {
    scopeLocation: string;
    query: string;
  }): Promise<MediaListItem[]>;

  loadDetail(args: { itemRef: string }): Promise<MediaDetailModel>;

  resolvePlayback(args: {
    itemRef: string;
    startMs: number;
  }): Promise<PlaybackSpec>;

  onPlaybackReady(args: {
    hooksState: HooksState;
    positionMs: number;
    playbackRate: number;
  }): Promise<void>;

  onProgressTick(args: {
    hooksState: HooksState;
    positionMs: number;
    playbackRate: number;
  }): Promise<void>;

  onStop(args: {
    hooksState: HooksState;
    positionMs: number;
  }): Promise<void>;
}
