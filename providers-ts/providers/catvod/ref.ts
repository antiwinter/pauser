/**
 * CatVod itemRef encoding/decoding.
 *
 * Compact string format using hashed keys with a dash separator:
 *   key | key-tid | key-tid-id | key-tid-id-epIdx-flagIdx | key-channelIdx | u<count>
 *
 * Free-string segments (key, tid, id) are percent-encoded with `-` also escaped
 * to %2D, so the dash separator is unambiguous regardless of whether the
 * segment contents contain dashes (e.g. URLs embedded in msearch: vod_ids).
 *
 * Key format:
 *   s<hash5> → site (s + first 5 chars of sha256(cfg.key))
 *   l<hash5> → live source (l + first 5 chars of sha256(cfg.name))
 *
 * Examples:
 *   "s6aed7"              → site root
 *   "s6aed7-c123"         → category 123 (2 segments, tid only)
 *   "s6aed7--vod456"      → vod 456 (3 segments, empty tid)
 *   "s6aed7-c123-vod456"  → vod 456 in category 123 (3 segments)
 *   "s6aed7--vod456-0-1"  → episode (5 segments: key, tid, id, epIdx, flagIdx)
 *   "l6aed7-5"            → live channel 5 (2 segments)
 */

export interface SiteRef        { type: 'site';        key: string }
export interface CatRef         { type: 'cat';         key: string; tid: string }
export interface VodRef         { type: 'vod';         key: string; tid: string; id: string }
export interface EpRef          { type: 'ep';          key: string; tid: string; id: string; epIndex: number; flagIndex: number }
export interface LiveSourceRef  { type: 'live-source'; key: string }
export interface LiveRef        { type: 'live';        key: string; channelIndex: number }
export interface UnsupportedRef { type: 'unsupported'; count: number }

export type CatVodRef = SiteRef | CatRef | VodRef | EpRef | LiveSourceRef | LiveRef | UnsupportedRef

function encSeg(s: string): string {
  return encodeURIComponent(s).replace(/-/g, '%2D');
}

function decSeg(s: string): string {
  return decodeURIComponent(s);
}

export function encodeRef(ref: CatVodRef): string {
  switch (ref.type) {
    case 'site':
      return ref.key;
    case 'cat':
      return `${encSeg(ref.key)}-${encSeg(ref.tid)}`;
    case 'vod':
      return `${encSeg(ref.key)}-${encSeg(ref.tid)}-${encSeg(ref.id)}`;
    case 'ep':
      return `${encSeg(ref.key)}-${encSeg(ref.tid)}-${encSeg(ref.id)}-${ref.epIndex}-${ref.flagIndex}`;
    case 'live-source':
      return ref.key;
    case 'live':
      return `${encSeg(ref.key)}-${ref.channelIndex}`;
    case 'unsupported':
      return `u${ref.count}`;
  }
}

export function decodeRef(s: string): CatVodRef {
  // Unsupported sites
  if (s.startsWith('u')) {
    return { type: 'unsupported', count: parseInt(s.slice(1), 10) };
  }

  const parts = s.split('-');
  const key = decSeg(parts[0]);

  // Live refs (prefix 'l')
  if (key.startsWith('l')) {
    if (parts.length === 1) {
      return { type: 'live-source', key };
    }
    return { type: 'live', key, channelIndex: parseInt(parts[1], 10) };
  }

  // Site refs (prefix 's')
  if (key.startsWith('s')) {
    if (parts.length === 1) {
      return { type: 'site', key };
    }
    if (parts.length === 2) {
      return { type: 'cat', key, tid: decSeg(parts[1]) };
    }
    if (parts.length === 3) {
      return { type: 'vod', key, tid: decSeg(parts[1]), id: decSeg(parts[2]) };
    }
    if (parts.length === 5) {
      return {
        type: 'ep',
        key,
        tid: decSeg(parts[1]),
        id: decSeg(parts[2]),
        epIndex: parseInt(parts[3], 10),
        flagIndex: parseInt(parts[4], 10),
      };
    }
  }

  throw new Error(`Invalid ref format: ${s}`);
}