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
  type_name?: string;
}

export interface CatVodDetail extends CatVodItem {
  vod_play_from?: string;  // "source1$$$source2"
  vod_play_url?: string;   // "EP1$url1#EP2$url2$$$..."
}

export interface CatVodCategory {
  type_id: string | number;
  type_name?: string;
}

// ── CatVod API Response Types ────────────────────────────────────────────────

export interface CatVodHomeResult {
  class?: CatVodCategory[];
}

export interface CatVodCategoryResult {
  list?: CatVodItem[];
  total?: number;
  pagecount?: number;
}

export interface CatVodDetailResult {
  list?: CatVodDetail[];
}

export interface CatVodPlayResult {
  url?: string;
  header?: Record<string, string>;
  type?: string;  // mimeType
}

// ── Unified CatVod Spider Interface ──────────────────────────────────────────
// All handler types (cms, drpy, jar, iptv) implement this interface

export interface CatVodSpider {
  /**
   * Get the home screen — typically a list of categories/types
   */
  home(): Promise<CatVodHomeResult>;

  /**
   * Get items in a category, paginated
   */
  category(tid: string, pg: number): Promise<CatVodCategoryResult>;

  /**
   * Get full details for a specific item
   */
  detail(id: string): Promise<CatVodDetail>;

  /**
   * Resolve playback URL for an episode
   */
  play(flag: string, epUrl: string): Promise<CatVodPlayResult>;

  /**
   * Search for items (optional — not all sources support search)
   */
  search?(query: string, pg: number): Promise<CatVodCategoryResult>;
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
