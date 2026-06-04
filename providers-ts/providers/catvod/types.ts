// ── CatVod Protocol Types ────────────────────────────────────────────────────
// Pure CatVod/TVBox protocol data structures — no OpenTune concepts

export interface CatVodItem {
  vod_id: string | number;
  vod_name?: string;
  vod_pic?: string;
  vod_blurb?: string;
  vod_content?: string;
  vod_score?: string;
  vod_year?: string;
  vod_remarks?: string;
  vod_area?: string;
  vod_actor?: string;
  vod_director?: string;
  vod_tag?: string;
  type_name?: string;
}

export interface CatVodDetail extends CatVodItem {
  vod_play_from?: string;  // "source1$$$source2"
  vod_play_url?: string;   // "EP1$url1#EP2$url2$$$..."
}

// ── Filter ───────────────────────────────────────────────────────────────────

export interface CatVodFilterValue {
  n: string;   // display label
  v: string;   // filter value
}

export interface CatVodFilter {
  key: string;
  name: string;
  init?: string;
  value: CatVodFilterValue[];
}

export interface CatVodCategory {
  type_id: string | number;
  type_name?: string;
  type_flag?: string;
  filters?: CatVodFilter[];
}

// ── Subtitle ─────────────────────────────────────────────────────────────────

export interface CatVodSub {
  url: string;
  name: string;
  lang?: string;
  format?: string;
}

// ── Danmaku ──────────────────────────────────────────────────────────────────

export interface CatVodDanmaku {
  name: string;
  url: string;
}

// ── DRM ──────────────────────────────────────────────────────────────────────

export interface CatVodDrm {
  key: string;
  type: string;
  force_key?: boolean;
  header?: Record<string, string>;
}

// ── CatVod API Response Types ────────────────────────────────────────────────

export interface CatVodHomeResult {
  class?: CatVodCategory[];
  list?: CatVodItem[];
  filters?: Record<string, CatVodFilter[]>;
  msg?: string;
}

export interface CatVodCategoryResult {
  list?: CatVodItem[];
  total?: number;
  pagecount?: number;
  filters?: Record<string, CatVodFilter[]>;
  msg?: string;
}

export interface CatVodDetailResult {
  list?: CatVodDetail[];
}

export interface CatVodPlayResult {
  url?: string;
  play_url?: string;
  header?: Record<string, string>;
  type?: string;  // mimeType
  parse?: number;  // 1 = URL needs parsing/resolving
  jx?: number;     // 1 = use jx/proxy player
  subs?: CatVodSub[];
  danmaku?: CatVodDanmaku[];
  drm?: CatVodDrm;
  flag?: string;
  format?: string;
  desc?: string;
  artwork?: string;
  click?: string;
  position?: number;
}

// ── Unified CatVod Spider Interface ──────────────────────────────────────────
// All handler types (cms, drpy, jar, iptv) implement this interface

export type CatVodFilterExtend = Record<string, string>;

export interface CatVodSpider {
  /**
   * Get the home screen — categories and optionally recently-updated videos
   * @param filter whether to include filter definitions in the result
   */
  home(filter?: boolean): Promise<CatVodHomeResult>;

  /**
   * Get recently-updated videos for home display
   */
  homeVideo?(): Promise<CatVodCategoryResult>;

  /**
   * Get items in a category, paginated with optional filters
   * @param tid category ID
   * @param pg page number
   * @param filter whether filters are enabled
   * @param extend filter key-value selections (e.g. {area: "大陆", year: "2024"})
   */
  category(tid: string, pg: number, filter?: boolean, extend?: CatVodFilterExtend): Promise<CatVodCategoryResult>;

  /**
   * Get full details for specific items
   * @param ids array of vod IDs (some sources batch-lookup)
   */
  detail(ids: string[]): Promise<CatVodDetailResult>;

  /**
   * Resolve playback URL for an episode
   * @param flag play source flag (e.g. "youku", "qq")
   * @param id episode URL or ID
   * @param vipFlags list of VIP source flags to consider
   */
  play(flag: string, id: string, vipFlags?: string[]): Promise<CatVodPlayResult>;

  /**
   * Search for items (optional — not all sources support search)
   * @param query search keyword
   * @param pg page number
   * @param quick whether to use quick search mode
   */
  search?(query: string, pg: number, quick?: boolean): Promise<CatVodCategoryResult>;

  /**
   * Check if a URL should be treated as video format
   * Used by spider-based players for webview sniffing
   */
  isVideoFormat?(url: string): boolean;

  /**
   * Handle proxy/image requests from the spider
   * Returns [statusCode, contentType, body] or null
   */
  proxy?(params: Record<string, string>): Promise<[number, string, any] | null>;
}

// ── IPTV-specific Types ──────────────────────────────────────────────────────
// IPTV is not VOD, but we model it similarly for consistency

export interface M3UChannel {
  name: string;
  url: string;
  logo?: string;
}

export interface IptvChannelListResult {
  channels: M3UChannel[];
}
