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
  fs: {
    write(args: { path: string; content: string }): Promise<string>;
  };
  crypto: {
    sha256(args: { input: string }): Promise<string>;
  };
  platform: {
    getPlatformInfo(args?: null): Promise<PlatformInfo>;
  };
  jar: {
    load(args: { url: string; md5?: string }): Promise<void>;
    loadAsset(args: { name: string }): Promise<void>;
    boot(args: { url: string; initClass: string; dexNativeClass: string; initOriginClass: string }): Promise<void>;
    reflect(args: {
      url: string;
      cls: string;
      method: string;
      instance?: string;
      args?: unknown[];
      factoryCls?: string;
      factoryMethod?: string;
    }): Promise<string>;
    clear(args?: null): Promise<void>;
    clearInstances(args?: null): Promise<void>;
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
  function atob(data: string): string;
  function btoa(data: string): string;
}

// ── Provider contracts ────────────────────────────────────────────────────────

export type ProviderFieldKind = 'text' | 'singleLine' | 'password' | 'proxySelector' | 'qrCode';

export interface ProviderFieldSpec {
  id: string;
  labelKey: string;
  kind: ProviderFieldKind;
  required?: boolean;
  sensitive?: boolean;
  order?: number;
  placeholderKey?: string;
  identity?: boolean;
}

export type ValidationResult =
  | { success: true; fields: Record<string, string> }
  | { success: false; error: string };

export type EntryType = string;

export interface EntryUserData {
  /** Omitted or null when the provider has no resume position (e.g. series sXeY). */
  positionMs?: number | null;
  isFavorite: boolean;
  played: boolean;
}

export interface EntryInfo {
  ref: string;
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
  overview?: string | null;
  childCount?: number | null;
  // detail fields
  parentRef?: string | null;
  seriesRef?: string | null;
  seasonNumber?: number | null;
  logo?: string | null;
  backdrop?: string[];
  bitrate?: number | null;
  year?: number | null;
  durationMs?: number | null;
  width?: number | null;
  height?: number | null;
  officialRating?: string | null;
  filename?: string | null;
  sources?: PlaybackSource[] | null;
}

export interface QueryOptions {
  sortBy?: string | null;
  sortOrder?: 'Ascending' | 'Descending';
  recursive?: boolean;
  filterByType?: string | null;
}

export interface EntryList {
  items: EntryInfo[];
  totalCount: number;
}

export interface MediaCodecInfo {
  codec: string;
  bitDepth?: number | null;
  profile?: string | null;
  bitrate?: number | null;
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
 * Opaque state blob used in JS provider hooks, passed back into
 * `updateEntryState`.
 */
export type ProviderState = Record<string, unknown>;

export interface PlaybackSource {
  url: string;
  headers: Record<string, string>;
  mimeType: string | null;
  subtitleTracks: SubtitleTrack[];
  mediaCodecs: MediaCodecInfo[];
}

export interface ProfileLevel {
  profile: string;
  level: number;
}

export interface VideoCodecInfo {
  codec: string;    // "h264", "hevc", "vp9", "av1"
  mime: string;     // "video/avc", "video/hevc"
  maxWidth: number;
  maxHeight: number;
  profileLevels: ProfileLevel[];
}

export interface AudioCodecInfo {
  codec: string;    // "aac", "ac3", "eac3", ...
  mime: string;
}

export interface PlatformInfo {
  deviceName: string;
  deviceId: string;
  clientVersion: string;
  videoCodecs: VideoCodecInfo[];
  audioCodecs: AudioCodecInfo[];
  subtitleFormats: string[];
}

// ── Bridge protocol ───────────────────────────────────────────────────────────

/** Exposed on globalThis.opentuneProvider by providers/emby/index.ts */
export interface OpenTuneProviderBridge {
  providesArt: boolean;
  getFieldsSpec(): Promise<ProviderFieldSpec[]>;
  test(): Promise<ValidationResult>;

  init(args: {
    credentials: Record<string, string>;
    deviceInfo: PlatformInfo;
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

  getPlaybackSources(args: {
    itemRef: string;
  }): Promise<PlaybackSource[]>;

  updateEntryState(args: {
    itemRef: string;
    key: string;
    value: string | null;
    state?: ProviderState;
  }): Promise<void>;
}
