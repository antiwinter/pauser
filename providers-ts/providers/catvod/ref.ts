/**
 * CatVod itemRef encoding/decoding.
 *
 * Fixed-width length-prefixed format — each field is preceded by its 4-digit
 * decimal length. The first character is a type tag that identifies the ref
 * shape. No separator is used between length and field, or between fields:
 * the decoder reads exactly 4 digits for length, then that many chars for
 * the field.
 *
 * Why length-prefix: source-site vod ids can contain `-`, `###`, `:`, `@`,
 * and other punctuation (e.g. msearch launcher ids). A naive `dash-joined`
 * string breaks the moment an id contains a dash — which crashes the app
 * when the user navigates into such an item. Length-prefixing eliminates
 * the delimiter collision. Fixed-width (4 decimal digits) avoids the
 * ambiguity of variable-width prefixes (where `8` is length 8 vs length 80,
 * and where the trailing colon of one field collides with the next length).
 *
 *   "s"                              → site
 *   "c<key_len><key><tid_len><tid>"
 *   "v<key_len><key><tid_len><tid><id_len><id>"
 *   "e<key_len><key><tid_len><tid><id_len><id><ep_idx_len><ep_idx><flag_idx_len><flag_idx>"
 *   "l"                              → live source
 *   "i<key_len><key><ch_idx_len><ch_idx>"
 *   "u<count_len><count>"            → unsupported
 *
 * Legacy dash-joined refs are accepted on decode for back-compat with
 * state already persisted in EntryStateEntity (pre-refactor users).
 */

export interface SiteRef        { type: 'site';        key: string }
export interface CatRef         { type: 'cat';         key: string; tid: string }
export interface VodRef         { type: 'vod';         key: string; tid: string; id: string }
export interface EpRef          { type: 'ep';          key: string; tid: string; id: string; epIndex: number; flagIndex: number }
export interface LiveSourceRef  { type: 'live-source'; key: string }
export interface LiveRef        { type: 'live';        key: string; channelIndex: number }
export interface UnsupportedRef { type: 'unsupported'; count: number }

export type CatVodRef = SiteRef | CatRef | VodRef | EpRef | LiveSourceRef | LiveRef | UnsupportedRef

const LEN_WIDTH = 4;

function padLen(n: number): string {
  if (n < 0 || n > 9999) throw new Error(`ref field length out of range: ${n}`);
  return n.toString().padStart(LEN_WIDTH, '0');
}

function concatFields(...parts: string[]): string {
  return parts.map((p) => padLen(p.length) + p).join('');
}

function splitPrefixed(s: string): string[] {
  const out: string[] = [];
  let i = 0;
  while (i < s.length) {
    if (i + LEN_WIDTH > s.length) throw new Error('Truncated length prefix in ref');
    const len = parseInt(s.slice(i, i + LEN_WIDTH), 10);
    if (isNaN(len) || len < 0) throw new Error('Bad length in ref');
    const start = i + LEN_WIDTH;
    const end = start + len;
    if (end > s.length) throw new Error('Truncated ref');
    out.push(s.slice(start, end));
    i = end;
  }
  return out;
}

export function encodeRef(ref: CatVodRef): string {
  switch (ref.type) {
    case 'site':         return 's' + concatFields(ref.key);
    case 'cat':          return 'c' + concatFields(ref.key, ref.tid);
    case 'vod':          return 'v' + concatFields(ref.key, ref.tid, ref.id);
    case 'ep':           return 'e' + concatFields(ref.key, ref.tid, ref.id, String(ref.epIndex), String(ref.flagIndex));
    case 'live-source':  return 'l' + concatFields(ref.key);
    case 'live':         return 'i' + concatFields(ref.key, String(ref.channelIndex));
    case 'unsupported':  return 'u' + concatFields(String(ref.count));
  }
}

/** Try the legacy dash-joined format first, fall back to the new length-prefixed format. */
export function decodeRef(s: string): CatVodRef {
  if (s.length === 0) {
    throw new Error('Empty ref');
  }
  try {
    return decodeLegacy(s);
  } catch (_) {
    return decodeNew(s);
  }
}

function decodeNew(s: string): CatVodRef {
  const typeChar = s[0];
  const body = s.slice(1);
  const parts = splitPrefixed(body);
  switch (typeChar) {
    case 's': {
      if (parts.length !== 1) throw new Error('site ref: wrong field count');
      return { type: 'site', key: parts[0] };
    }
    case 'l': {
      if (parts.length !== 1) throw new Error('live-source ref: wrong field count');
      return { type: 'live-source', key: parts[0] };
    }
    case 'c': {
      if (parts.length !== 2) throw new Error('cat ref: wrong field count');
      return { type: 'cat', key: parts[0], tid: parts[1] };
    }
    case 'v': {
      if (parts.length !== 3) throw new Error('vod ref: wrong field count');
      return { type: 'vod', key: parts[0], tid: parts[1], id: parts[2] };
    }
    case 'e': {
      if (parts.length !== 5) throw new Error('ep ref: wrong field count');
      return {
        type: 'ep',
        key: parts[0],
        tid: parts[1],
        id: parts[2],
        epIndex: parseInt(parts[3], 10),
        flagIndex: parseInt(parts[4], 10),
      };
    }
    case 'i': {
      if (parts.length !== 2) throw new Error('live ref: wrong field count');
      return { type: 'live', key: parts[0], channelIndex: parseInt(parts[1], 10) };
    }
    case 'u': {
      if (parts.length !== 1) throw new Error('unsupported ref: wrong field count');
      return { type: 'unsupported', count: parseInt(parts[0], 10) };
    }
  }
  throw new Error(`Invalid ref type tag: ${typeChar}`);
}

function decodeLegacy(s: string): CatVodRef {
  if (s.startsWith('u')) {
    return { type: 'unsupported', count: parseInt(s.slice(1), 10) };
  }

  const parts = s.split('-');
  const key = parts[0];
  // Legacy keys are always 's' or 'l' followed by 5 hex chars.
  if (!/^[sl][0-9a-f]{5}$/.test(key)) {
    throw new Error(`Invalid ref format: ${s}`);
  }

  if (key.startsWith('l')) {
    if (parts.length === 1) {
      return { type: 'live-source', key };
    }
    return { type: 'live', key, channelIndex: parseInt(parts[1], 10) };
  }

  if (key.startsWith('s')) {
    if (parts.length === 1) {
      return { type: 'site', key };
    }
    if (parts.length === 2) {
      return { type: 'cat', key, tid: parts[1] };
    }
    if (parts.length === 3) {
      return { type: 'vod', key, tid: parts[1], id: parts[2] };
    }
    if (parts.length === 5) {
      return {
        type: 'ep',
        key,
        tid: parts[1],
        id: parts[2],
        epIndex: parseInt(parts[3], 10),
        flagIndex: parseInt(parts[4], 10),
      };
    }
  }

  throw new Error(`Invalid ref format: ${s}`);
}
