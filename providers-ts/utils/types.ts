/**
 * types.ts — contract types shared between all provider implementations.
 * Mirrors the Kotlin `contracts` module; both sides must stay in sync.
 * The `host` global is injected by QuickJsEngine before the bundle runs.
 */

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
    /** `..` and absolute paths are rejected. */
    write(args: { path: string; content: string; encoding?: 'utf8' | 'base64' }): Promise<string>;
    read(args: { path: string; encoding?: 'utf8' | 'base64' }): Promise<string>;
    exists(args: { path: string }): Promise<boolean>;
    /** Recursive. */
    delete(args: { path: string }): Promise<boolean>;
  };
  crypto: {
    /** Default algo: sha-256. Returns lowercase hex. */
    checksum(args: {
      input: string;
      algo?: 'md5' | 'sha-1' | 'sha-256' | 'sha-512';
      encoding?: 'utf8' | 'base64' | 'hex';
    }): Promise<string>;
  };
  platform: {
    getPlatformInfo(args?: null): Promise<PlatformInfo>;
  };
  jar: {
    /**
     * Discriminated source:
     *  - `{ url }` — Kotlin downloads to sandbox (no integrity check).
     *  - `{ path }` — sandbox-relative; warm/cached JARs (low heap).
     *  - `{ buffer }` — base64-encoded; small/synthetic only (~134% heap).
     */
    load(args: {
      source: { url: string } | { path: string } | { buffer: string };
    }): Promise<void>;
    loadAsset(args: { name: string }): Promise<void>;
    reflect(args: {
      url: string;
      cls: string;
      method: string;
      instance?: string;
      args?: unknown[];
      factoryCls?: string;
      factoryMethod?: string;
    }): Promise<string>;
    loadClass(args: { url: string; cls: string }): Promise<void>;
    registerLoader(args: { key: string; instanceHandle: string }): Promise<void>;
    /**
     * `parentKey: 'context'` → app classloader (with bootstrap classes
     * injected). Plugin runtimes that build their own DexClassLoader need
     * this to resolve shim classes.
     */
    adoptParent(args: { childKey: string; parentKey: string }): Promise<void>;
    clear(args?: null): Promise<void>;
    clearInstances(args?: null): Promise<void>;
  };
  timer: {
    sleep(args: { ms: number }): Promise<void>;
  };
  dns: {
    remap(args: { from: string; to: string }): Promise<string>;
  };
  relay: {
    /** Token is the URL path segment under `/relay/`; must be process-unique. Rewrite provider's localhost proxy URLs to `baseUrl`. */
    register(args: { cls: string; method: string; token: string }): Promise<{ token: string; baseUrl: string }>;
  };
  web: {
    /** Android-only (WebView). Resolves first sub-resource URL matching any [regex] and no [exclude]. Injects [script] after page load. Rejects to `null` if nothing matches within [timeoutMs]. */
    detect(args: {
      url: string;
      headers?: Record<string, string>;
      regex: string[];
      exclude?: string[];
      script?: string[];
      timeoutMs?: number;
    }): Promise<{ url: string; headers: Record<string, string> } | null>;
  };
}

export interface PlatformInfo {
  deviceName: string;
  deviceId: string;
  clientVersion: string;
}

declare global {
  const host: HostAPI;
  function atob(data: string): string;
  function btoa(data: string): string;
  const console: {
    log(...args: unknown[]): void;
    warn(...args: unknown[]): void;
    error(...args: unknown[]): void;
  };
}

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
  /** Null when the provider has no resume position (e.g. series sXeY). */
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

export type ProviderCtx = Record<string, unknown>;

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
  // "h264", "hevc", "vp9", "av1"
  codec: string;
  // "video/avc", "video/hevc"
  mime: string;
  maxWidth: number;
  maxHeight: number;
  profileLevels: ProfileLevel[];
}

export interface AudioCodecInfo {
  // "aac", "ac3", "eac3", ...
  codec: string;
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

export interface InsomniaProviderBridge {
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
    startMs: number;
  }): Promise<PlaybackSource[] | { sources: PlaybackSource[]; ctx: ProviderCtx }>;

  updateEntryState(args: {
    itemRef: string;
    key: string;
    value: string | null;
    ctx?: ProviderCtx;
  }): Promise<void>;
}
