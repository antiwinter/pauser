/**
 * urls.ts — URL normalization and image URL construction for Emby.
 */

export function normalizeBaseUrl(input: string): string {
  let s = input.trim().replace(/\/+$/, '');
  if (!s.startsWith('http://') && !s.startsWith('https://')) {
    s = 'http://' + s;
  }
  // Ensure trailing slash for consistent concatenation
  return s.endsWith('/') ? s : s + '/';
}

export function imageUrl(opts: {
  baseUrl: string;
  itemId: string;
  imageType: string;
  tag: string;
  accessToken: string | null | undefined;
  maxHeight?: number;
  index?: number | null;
}): string {
  const base = normalizeBaseUrl(opts.baseUrl).replace(/\/$/, '');
  const tokenPart = opts.accessToken ? `&api_key=${opts.accessToken}` : '';
  const indexPart = opts.index != null ? `/${opts.index}` : '';
  const maxH = opts.maxHeight ?? 220;
  return `${base}/Items/${opts.itemId}/Images/${opts.imageType}${indexPart}?maxHeight=${maxH}&tag=${opts.tag}${tokenPart}`;
}

export function resolvePlaybackUrl(baseUrl: string, source: import('./dto.js').MediaSourceInfo): string {
  const relative =
    (source.TranscodingUrl && source.TranscodingUrl.trim()) ||
    (source.DirectStreamUrl && source.DirectStreamUrl.trim());
  if (!relative) throw new Error('No playback URL in MediaSourceInfo');
  if (relative.startsWith('http://') || relative.startsWith('https://')) {
    return relative;
  }
  const base = normalizeBaseUrl(baseUrl).replace(/\/$/, '');
  return `${base}/${relative.replace(/^\//, '')}`;
}

export function playMethod(source: import('./dto.js').MediaSourceInfo): string {
  if (source.TranscodingUrl && source.TranscodingUrl.trim()) return 'Transcode';
  if (source.DirectStreamUrl && source.DirectStreamUrl.trim()) return 'DirectStream';
  return 'DirectPlay';
}
