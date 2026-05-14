/**
 * types.ts — contract types shared between all provider implementations.
 *
 * This file mirrors the Kotlin `contracts` module; both sides must stay in sync.
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
  | { success: true; hash: string; name: string; fields: Record<string, string> }
  | { success: false; error: string };

export type EntryType = 'Folder' | 'Series' | 'Season' | 'Episode' | 'Playable' | 'Other';

export interface EntryUserData {
  positionMs: number;
  isFavorite: boolean;
  played: boolean;
}

export interface EntryInfo {
  id: string;
  title: string;
  type: EntryType;
  cover: string | null;
  userData?: EntryUserData | null;
  originalTitle?: string | null;
  genres?: string[] | null;
  communityRating?: number | null;
  studios?: string[] | null;
  etag?: string | null;
  indexNumber?: number | null;
}

export interface EntryList {
  items: EntryInfo[];
  totalCount: number;
}

export interface ExternalUrl {
  name: string;
  url: string;
}

export interface StreamInfo {
  index: number;
  type: string;
  codec: string | null;
  title: string | null;
  language: string | null;
  isDefault: boolean;
  isForced: boolean;
}

export interface EntryDetail {
  title: string;
  overview: string | null;
  logo: string | null;
  backdrop: string[];
  isMedia: boolean;
  rating: number | null;
  bitrate: number | null;
  externalUrls: ExternalUrl[];
  year: number | null;
  providerIds: Record<string, string>;
  streams: StreamInfo[];
  etag: string | null;
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
 * Opaque state blob returned by `getPlaybackSpec` and passed back into
 * `onPlaybackReady`, `onProgressTick`, `onStop`.
 */
export type HooksState = Record<string, unknown>;

export interface PlaybackSpec {
  url: string | null;
  headers: Record<string, string>;
  mimeType: string | null;
  title: string;
  durationMs: number | null;
  subtitleTracks: SubtitleTrack[];
  hooksState: HooksState;
}

export interface PlatformCapabilities {
  videoMime: string[];
  audioMime: string[];
  subtitleFormats: string[];
  maxPixels: number;
}

// ── Bridge protocol ───────────────────────────────────────────────────────────

/** Exposed on globalThis.opentuneProvider by emby/index.ts */
export interface OpenTuneProviderBridge {
  providesCover: boolean;
  getFieldsSpec(): Promise<ServerFieldSpec[]>;
  validateFields(args: { values: Record<string, string> }): Promise<ValidationResult>;

  init(args: {
    credentials: Record<string, string>;
    capabilities: PlatformCapabilities;
  }): Promise<void>;

  listEntry(args: {
    location: string | null;
    startIndex: number;
    limit: number;
  }): Promise<EntryList>;

  search(args: {
    scopeLocation: string;
    query: string;
  }): Promise<EntryInfo[]>;

  getDetail(args: { itemRef: string }): Promise<EntryDetail>;

  getPlaybackSpec(args: {
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
