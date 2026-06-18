// ── CatVod Protocol Types ────────────────────────────────────────────────────
// Pure CatVod/TVBox protocol data structures — no OpenTune concepts
//
// Tags on fields indicate source specificity:
//   [standard]  — core protocol, consumed by both fongmi-tv and tvboxosc clients
//   [protocol+] — protocol extension; spiders should return these, older clients may ignore
//   [MacCMS]    — MacCMS JSON API (cms handler, types 0/1/2) only

// ── CatVodItem ───────────────────────────────────────────────────────────────
// Base video object — returned in category, search, and home lists

export interface CatVodItem {
  /** Unique identifier for this video [standard] */
  vod_id: string | number;
  /** Display title [standard] */
  vod_name?: string;
  /** Cover/thumbnail image URL [standard] */
  vod_pic?: string;
  /** Short synopsis / blurb text [MacCMS] — AbsJsonVod.vod_blurb */
  vod_blurb?: string;
  /** Full description HTML (detail view) [standard] */
  vod_content?: string;
  /** Rating score, e.g. "4.4" [MacCMS] — AbsJsonVod.vod_score */
  vod_score?: string;
  /** Release year, e.g. "2024" [standard] */
  vod_year?: string;
  /** Episode/update status, e.g. "全36集", "更新至10集", "HD" [standard] — Vod.vodRemarks / Movie.Video.note */
  vod_remarks?: string;
  /** Production region, e.g. "大陆", "美国" [standard] — Vod.vodArea / Movie.Video.area */
  vod_area?: string;
  /** Cast list [standard] — Vod.vodActor / Movie.Video.actor */
  vod_actor?: string;
  /** Director name [standard] — Vod.vodDirector / Movie.Video.director */
  vod_director?: string;
  /** Item tag — "folder" marks a non-playable navigable node [standard] — Vod.vodTag / Movie.Video.tag */
  vod_tag?: string;
  /** Category name this item belongs to [standard] */
  type_name?: string;
}

// ── CatVodDetail ─────────────────────────────────────────────────────────────

export interface CatVodDetail extends CatVodItem {
  /** Play source names separated by "$$$", e.g. "youku$$$qq$$$iqiyi" [standard] */
  vod_play_from?: string;
  /** Episode URLs separated by "$$$" for sources, "#" for episodes, "$" between name and URL.
   *  e.g. "EP1$http://url1#EP2$http://url2$$$EP1$http://url3" [standard] */
  vod_play_url?: string;
}

// ── Filter ───────────────────────────────────────────────────────────────────
// Category filter definitions — used for genre/year/area selection

export interface CatVodFilterValue {
  /** Display label shown to user, e.g. "2024", "动作" */
  n: string;
  /** Actual filter value passed back via extend param */
  v: string;
}

export interface CatVodFilter {
  /** Filter key — matches the extend param key, e.g. "area", "year", "sort" [standard] */
  key: string;
  /** Display name for this filter group, e.g. "地区", "年份" [standard] */
  name: string;
  /** List of selectable values for this filter [standard] */
  value: CatVodFilterValue[];
}

// ── CatVodCategory ───────────────────────────────────────────────────────────
// A category (aka class/type) — returned by homeContent()

export interface CatVodCategory {
  /** Unique category identifier [standard] — Class.typeId / MovieSort.SortData.id */
  type_id: string | number;
  /** Display name, e.g. "电影", "电视剧" [standard] */
  type_name?: string;
  /** Category flag — "1" means folder/navigable category [standard] — Class.typeFlag / SortData.flag */
  type_flag?: string;
  /** Filter definitions for this category — enables filtering by year, area, genre, etc. [standard] — Class.filters / SortData.filters */
  filters?: CatVodFilter[];
}

// ── Subtitle ─────────────────────────────────────────────────────────────────
// External subtitle track — returned by playerContent()

export interface CatVodSub {
  /** Subtitle file URL [standard] */
  url: string;
  /** Display label for the subtitle track [standard] */
  name: string;
  /** Language code, e.g. "zh", "en" [protocol+] */
  lang?: string;
  /** MIME type / format hint, e.g. "text/srt", "application/x-subrip" [protocol+] */
  format?: string;
}

// ── Danmaku ──────────────────────────────────────────────────────────────────
// Danmaku/barrage overlay data — returned by playerContent()

export interface CatVodDanmaku {
  /** Display name / label [protocol+] */
  name: string;
  /** Danmaku data source URL [protocol+] */
  url: string;
}

// ── DRM ──────────────────────────────────────────────────────────────────────
// DRM configuration — returned by playerContent()

export interface CatVodDrm {
  /** DRM license key or ID [protocol+] */
  key: string;
  /** DRM system type — "widevine", "playready", "clearkey" [protocol+] */
  type: string;
  /** Force use of this DRM key even if content is unencrypted [protocol+] */
  force_key?: boolean;
  /** HTTP headers to include with license request [protocol+] */
  header?: Record<string, string>;
}

// ── CatVod API Response Types ────────────────────────────────────────────────

export interface CatVodHomeResult {
  /** List of categories — always present in homeContent() [standard] — Result.types / AbsSortXml.classes */
  class?: CatVodCategory[];
  /** Recently-updated videos for home feed — returned by homeVideoContent() [standard] — Result.list */
  list?: CatVodItem[];
  /** Global filter definitions keyed by category ID [protocol+] — Result.filters */
  filters?: Record<string, CatVodFilter[]>;
  /** Error or status message from the spider [standard] — Result.msg / AbsXml.msg */
  msg?: string;
}

export interface CatVodCategoryResult {
  /** List of video items in this category [standard] — Result.list / Movie.videoList */
  list?: CatVodItem[];
  /** Total number of matching items across all pages [MacCMS] — AbsJson.total / AbsJson.recordcount */
  total?: number;
  /** Total number of pages available [standard] — Result.pagecount / Movie.pagecount */
  pagecount?: number;
  /** Filter definitions returned with this category result [standard] */
  filters?: Record<string, CatVodFilter[]>;
  /** Error or status message [standard] — AbsJson.msg */
  msg?: string;
}

export interface CatVodDetailResult {
  /** List of video details — typically one item per detailContent() call [standard] */
  list?: CatVodDetail[];
}

export interface CatVodPlayResult {
  /** Direct playback URL — used when parse=0 or no resolution needed [standard] — Result.url */
  url?: string;
  /** Resolved playback URL after parsing/jx — separate from url [protocol+] — Result.playUrl */
  play_url?: string;
  /** HTTP headers required for playback (referer, user-agent, etc.) [standard] — Result.header */
  header?: Record<string, string>;
  /** MIME type hint for the player [standard] — Result.format */
  type?: string;
  /** Whether the URL needs parsing/resolving: 1 = needs parsing, 0 = direct [standard] — Result.parse */
  parse?: number;
  /** Whether to use jx (proxy/decode) player: 1 = use jx, 0 = standard [protocol+] — Result.jx */
  jx?: number;
  /** External subtitle tracks [protocol+] — Result.subs */
  subs?: CatVodSub[];
  /** Danmaku/barrage overlay sources — bullet comments scrolling across video [protocol+] — Result.danmaku */
  danmaku?: CatVodDanmaku[];
  /** DRM configuration for the stream [protocol+] — Result.drm */
  drm?: CatVodDrm;
  /** Current play source flag name (echo of input) [protocol+] — Result.flag */
  flag?: string;
  /** Video format hint (mp4, m3u8, etc.) [protocol+] — Result.format */
  format?: string;
  /** Episode or stream description [protocol+] — Result.desc */
  desc?: string;
  /** Poster/thumbnail image for the player UI [protocol+] — Result.artwork */
  artwork?: string;
  /** Click behavior override for the player [protocol+] — Result.click */
  click?: string;
  /** Resume playback position in milliseconds [protocol+] — Result.position */
  position?: number;
}

// ── IPTV Channel Types ────────────────────────────────────────────────────────
// M3U/IPTV live channel data — used by IPTV spiders' channels() method

export interface M3UChannel {
  name: string;
  url: string;
  logo?: string;
}

export interface IptvChannelListResult {
  channels: M3UChannel[];
}

// ── Unified CatVod Spider Interface ──────────────────────────────────────────
// All handler types (cms, drpy, jar, iptv) implement this interface

/** Filter selections passed to categoryContent() — key-value pairs where key matches CatVodFilter.key */
export type CatVodFilterExtend = Record<string, string>;

export interface CatVodSpider {
  /**
   * Get the home screen — categories and optionally filter definitions
   * @param filter whether to include filter definitions in the result [standard] — Spider.homeContent(filter)
   */
  home(filter?: boolean): Promise<CatVodHomeResult>;

  /**
   * Get recently-updated videos for home display [standard] — Spider.homeVideoContent()
   * Optional — not all spiders implement this; returns same shape as category results
   */
  homeVideo?(): Promise<CatVodCategoryResult>;

  /**
   * Get items in a category, paginated with optional filters
   * @param tid category ID
   * @param pg page number (1-based)
   * @param filter whether filters are enabled [standard]
   * @param extend filter key-value selections (e.g. {area: "大陆", year: "2024"}) [standard] — Spider.categoryContent extend param
   */
  category(tid: string, pg: number, filter?: boolean, extend?: CatVodFilterExtend): Promise<CatVodCategoryResult>;

  /**
   * Get full details for specific items
   * @param ids array of vod IDs — native API accepts List<String> for batch lookup [standard] — Spider.detailContent(ids)
   */
  detail(ids: string[]): Promise<CatVodDetailResult>;

  /**
   * Resolve playback URL for an episode
   * @param flag play source flag (e.g. "youku", "qq")
   * @param id episode URL or ID
   * @param vipFlags list of VIP source flags to skip for resolution [standard] — Spider.playerContent vipFlags
   */
  play(flag: string, id: string, vipFlags?: string[]): Promise<CatVodPlayResult>;

  /**
   * Search for items (optional — not all sources support search)
   * @param query search keyword
   * @param pg page number
   * @param quick whether to use quick search mode (limited results, faster) [standard] — Spider.searchContent quick param
   */
  search?(query: string, pg: number, quick?: boolean): Promise<CatVodCategoryResult>;

  /**
   * Check if a URL should be treated as video format
   * Used by spider-based players for webview video sniffing [standard] — Spider.isVideoFormat()
   */
  isVideoFormat?(url: string): boolean;

  /**
   * Handle proxy/image requests routed through the spider
   * Returns [statusCode, contentType, body] or null [standard] — Spider.proxy() / proxyLocal()
   */
  proxy?(params: Record<string, string>): Promise<[number, string, any] | null>;

  /**
   * Get live TV channels (IPTV/M3U) — optional, only IPTV spiders implement this
   * Returns the parsed channel list from an M3U source
   */
  channels?(): Promise<IptvChannelListResult>;
}

