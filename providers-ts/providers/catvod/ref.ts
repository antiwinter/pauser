/**
 * CatVod itemRef encoding/decoding.
 *
 * All refs are JSON-serialized so they survive the string-only ID field.
 *
 * Formats:
 *   SiteRef  { type:'site', key }                         → site root (homeContent)
 *   CatRef   { type:'cat',  key, tid }                    → category listing
 *   VodRef   { type:'vod',  key, id }                     → vod detail + episode list
 *   EpRef    { type:'ep',   key, id, flag, epUrl }        → episode playback
 *   LiveRef  { type:'live', name, url }                   → IPTV channel
 */

export interface SiteRef      { type: 'site'; key: string }
export interface CatRef       { type: 'cat';  key: string; tid: string }
export interface VodRef       { type: 'vod';  key: string; id: string }
export interface EpRef        { type: 'ep';   key: string; id: string; flag: string; epUrl: string }
export interface LiveRef      { type: 'live'; name: string; url: string }
export interface UnsupportedRef { type: 'unsupported'; count: number }

export type CatVodRef = SiteRef | CatRef | VodRef | EpRef | LiveRef | UnsupportedRef

export function encodeRef(ref: CatVodRef): string {
  return JSON.stringify(ref);
}

export function decodeRef(s: string): CatVodRef {
  return JSON.parse(s);
}
