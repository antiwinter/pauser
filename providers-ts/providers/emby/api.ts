/**
 * api.ts — Emby/Jellyfin HTTP API calls using host.http.*
 */
import type {
  AuthenticateByNameRequest,
  AuthenticationResult,
  SystemInfoDto,
  QueryResultBaseItemDto,
  BaseItemDto,
  PlaybackInfoRequest,
  PlaybackInfoResponse,
  PlaybackStartInfo,
  PlaybackProgressInfo,
  PlaybackStopInfo,
} from './dto.js';

type MediaBrowserAuth = {
  clientName: string;
  deviceName: string;
  deviceId: string;
  clientVersion: string;
};

let globalAuth: MediaBrowserAuth | null = null;

export function setGlobalAuth(auth: MediaBrowserAuth): void {
  globalAuth = auth;
}

function mediaBrowserHeader(): string {
  const a = globalAuth ?? {
    clientName: 'OpenTune',
    deviceName: 'Android',
    deviceId: 'opentune-fallback',
    clientVersion: '0.0',
  };
  const q = (s: string) => '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  return (
    `MediaBrowser Client=${q(a.clientName)}, ` +
    `Device=${q(a.deviceName)}, ` +
    `DeviceId=${q(a.deviceId)}, ` +
    `Version=${q(a.clientVersion)}`
  );
}

function baseHeaders(accessToken?: string | null): Record<string, string> {
  const mb = mediaBrowserHeader();
  const hdrs: Record<string, string> = {
    Accept: 'application/json',
    Authorization: mb,
    'X-Emby-Authorization': mb,
  };
  if (accessToken) hdrs['X-Emby-Token'] = accessToken;
  return hdrs;
}

// ── Temp dump helper: saves parsed JSON responses to cache/emby_dump/ ────────

function dumpUrl(url: string): string {
  try {
    // Strip scheme, then take everything after host:port
    const withoutScheme = url.replace(/^https?:\/\//, '');
    const slashIdx = withoutScheme.indexOf('/');
    if (slashIdx < 0) return 'unknown';
    const pathAndQuery = withoutScheme.slice(slashIdx + 1);
    const path = pathAndQuery.split('?')[0];
    return path.replace(/\//g, '-') || 'unknown';
  } catch {
    return 'unknown';
  }
}

async function dumpResponse(method: 'get' | 'post', url: string, body: string): Promise<void> {
  try {
    const slug = dumpUrl(url);
    const path = `emby_dump/${method}-${slug}.json`;
    const parsed = JSON.parse(body);
    await host.fs.write({
      path,
      content: JSON.stringify(parsed, null, 2),
    });
  } catch { /* ignore dump failures */ }
}

async function httpGet<T>(url: string, accessToken?: string | null): Promise<T> {
  const resp = await host.http.get({ url, headers: baseHeaders(accessToken) });
  if (resp.status < 200 || resp.status >= 300) {
    throw new Error(`HTTP ${resp.status} GET ${url}: ${resp.body.slice(0, 200)}`);
  }
  await dumpResponse('get', url, resp.body);
  return JSON.parse(resp.body) as T;
}

async function httpPost<T>(url: string, body: unknown, accessToken?: string | null): Promise<T> {
  const resp = await host.http.post({
    url,
    headers: { ...baseHeaders(accessToken), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    contentType: 'application/json',
  });
  if (resp.status < 200 || resp.status >= 300) {
    throw new Error(`HTTP ${resp.status} POST ${url}: ${resp.body.slice(0, 200)}`);
  }
  if (!resp.body || resp.body.trim() === '') return undefined as unknown as T;
  await dumpResponse('post', url, resp.body);
  return JSON.parse(resp.body) as T;
}

export const DETAIL_FIELDS =
  'Overview,ImageTags,BackdropImageTags,RunTimeTicks,UserData,MediaSources,' +
  'CommunityRating,Genres,Studios,ProductionYear,ProviderIds,ExternalUrls,' +
  'OriginalTitle,IndexNumber,Etag,MediaStreams';

export class EmbyApi {
  constructor(
    private readonly baseUrl: string,
    private readonly accessToken: string,
    private readonly userId: string,
  ) {}

  private url(path: string, params?: Record<string, string | number | boolean | undefined | null>): string {
    const base = this.baseUrl.replace(/\/$/, '');
    let url = `${base}/${path.replace(/^\//, '')}`;
    if (params) {
      const qs = Object.entries(params)
        .filter(([, v]) => v !== undefined && v !== null)
        .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
        .join('&');
      if (qs) url += `?${qs}`;
    }
    return url;
  }

  async authenticateByName(body: AuthenticateByNameRequest): Promise<AuthenticationResult> {
    return httpPost<AuthenticationResult>(
      this.url('Users/AuthenticateByName'),
      body,
      null,
    );
  }

  async getSystemInfo(): Promise<SystemInfoDto> {
    return httpGet<SystemInfoDto>(this.url('System/Info'), this.accessToken);
  }

  async getViews(): Promise<QueryResultBaseItemDto> {
    return httpGet<QueryResultBaseItemDto>(
      this.url(`Users/${this.userId}/Views`),
      this.accessToken,
    );
  }

  async getItems(opts: {
    parentId?: string | null;
    includeItemTypes?: string | null;
    recursive?: boolean;
    searchTerm?: string | null;
    sortBy?: string;
    sortOrder?: string;
    startIndex?: number | null;
    limit?: number | null;
    fields?: string | null;
  }): Promise<QueryResultBaseItemDto> {
    return httpGet<QueryResultBaseItemDto>(
      this.url(`Users/${this.userId}/Items`, {
        ParentId: opts.parentId,
        IncludeItemTypes: opts.includeItemTypes,
        Recursive: opts.recursive,
        SearchTerm: opts.searchTerm,
        SortBy: opts.sortBy ?? 'SortName',
        SortOrder: opts.sortOrder,
        StartIndex: opts.startIndex,
        Limit: opts.limit,
        Fields: opts.fields,
      }),
      this.accessToken,
    );
  }

  async getItem(itemId: string, fields?: string | null): Promise<BaseItemDto> {
    return httpGet<BaseItemDto>(
      this.url(`Users/${this.userId}/Items/${itemId}`, fields ? { Fields: fields } : undefined),
      this.accessToken,
    );
  }

  async getPlaybackInfo(itemId: string, body: PlaybackInfoRequest): Promise<PlaybackInfoResponse> {
    return httpPost<PlaybackInfoResponse>(
      this.url(`Items/${itemId}/PlaybackInfo`),
      body,
      this.accessToken,
    );
  }

  async reportPlaying(body: PlaybackStartInfo): Promise<void> {
    await httpPost<void>(this.url('Sessions/Playing'), body, this.accessToken);
  }

  async reportProgress(body: PlaybackProgressInfo): Promise<void> {
    await httpPost<void>(this.url('Sessions/Playing/Progress'), body, this.accessToken);
  }

  async reportStopped(body: PlaybackStopInfo): Promise<void> {
    await httpPost<void>(this.url('Sessions/Playing/Stopped'), body, this.accessToken);
  }
}
