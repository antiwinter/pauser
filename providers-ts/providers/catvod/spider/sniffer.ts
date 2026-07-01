import type { CatVodPlayResult } from './types.js';

// Default video-URL sniff pattern — mirrors fongmi's Sniffer.SNIFFER. Matches common
// media extensions and a couple of known streaming URL shapes. Used when a play result
// is flagged parse=1 (the `url` is a webpage, not a direct media URL) and no
// site-specific pattern is supplied.
const DEFAULT_VIDEO_REGEX = [
  'https?://[^\\s]{12,}\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)(?:\\?.*)?',
  'https?://.*?video/tos[^\\s]*',
];

/**
 * Resolve a webpage URL to a direct media URL by loading it in a headless WebView and
 * sniffing the first request that looks like a media stream. Returns the sniffed URL and
 * the request headers the page attached (referer/UA/cookie), or null on timeout/no-match.
 *
 * [click] (from CatVodPlayResult.click) is injected as a JS snippet after page load — e.g.
 * to dismiss an overlay or press a play button that triggers the media request.
 */
export async function sniffPlayUrl(
  pageUrl: string,
  headers?: Record<string, string>,
  click?: string,
): Promise<{ url: string; headers: Record<string, string> } | null> {
  return host.web.detect({
    url: pageUrl,
    headers,
    regex: DEFAULT_VIDEO_REGEX,
    script: click ? [click] : undefined,
  });
}

/**
 * A play result needs sniffing when the spider flags parse=1 and gives a webpage `url`
 * rather than a resolved media URL. (parse=0 / play_url present = already direct.)
 */
export function needsSniff(result: CatVodPlayResult): boolean {
  return result.parse === 1 && !result.play_url && !!result.url;
}
