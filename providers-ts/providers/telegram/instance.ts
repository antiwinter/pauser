/**
 * instance.ts — listEntry, search, getDetail, getPlaybackSpec, getSprite, QR auth.
 * Cursor-based pagination cache for TDLib message search.
 */
import { shimReflect } from './api.js';
import { parseEntryList, parseEntryDetail, parsePlaybackSpec } from './mapper.js';
import type {
  EntryList,
  EntryInfo,
  EntryDetail,
  PlaybackSpec,
  HooksState,
} from '../../utils/types.js';

// Cursor cache: maps composite key → fromMessageId for TDLib pagination
const cursorCache = new Map<string, number>();

function cacheKey(chatId: string, filter: string, page: number): string {
  return `${chatId}:${filter}:${page}`;
}

// ── Content ─────────────────────────────────────────────────────────────────

export async function listEntry(args: {
  location: string | null;
  startIndex: number;
  limit: number;
}): Promise<EntryList> {
  if (args.location == null) {
    // Top level: return list of joined channels/supergroups
    const raw = await shimReflect('homeContent', [false]);
    return parseEntryList(raw);
  }
  // Sub-folder: return messages in chat/type
  const page = Math.floor(args.startIndex / Math.max(args.limit, 1)) + 1;
  const ck = cacheKey(args.location, 'all', page);
  const fromMsgId = cursorCache.get(ck) ?? 0;
  const raw = await shimReflect('categoryContent', [args.location, String(page), false, {}]);
  const result = parseEntryList(raw);
  if (result.items.length > 0) {
    // Store cursor for next page
    const lastItem = result.items[result.items.length - 1];
    const msgId = parseInt(lastItem.id.split(':').pop() ?? '0', 10);
    if (msgId > 0) {
      cursorCache.set(cacheKey(args.location, 'all', page + 1), msgId);
    }
  }
  return result;
}

export async function search(args: {
  scopeLocation: string;
  query: string;
}): Promise<EntryInfo[]> {
  const raw = await shimReflect('searchContent', [args.query, false, '1']);
  const result = parseEntryList(raw);
  return result.items;
}

export async function getDetail(args: { itemRef: string }): Promise<EntryDetail> {
  const raw = await shimReflect('detailContent', [[args.itemRef]]);
  return parseEntryDetail(raw);
}

export async function getPlaybackSpec(args: {
  itemRef: string;
  startMs: number;
}): Promise<PlaybackSpec> {
  const raw = await shimReflect('playerContent', ['telegram', args.itemRef, []]);
  return parsePlaybackSpec(raw);
}

export async function getSprite(args: {
  itemRef: string;
  ts: number;
}): Promise<string | null> {
  const raw = await shimReflect('getSprite', [args.itemRef, args.ts]);
  if (raw == null || raw === 'null') return null;
  return JSON.parse(raw);
}

// ── QR auth ─────────────────────────────────────────────────────────────────

export async function getQr(): Promise<{ token: string; qrData: string } | null> {
  const raw = await shimReflect('getQr', []);
  if (raw == null || raw === 'null') return null;
  return JSON.parse(raw);
}

export async function pollQr(args: {
  token: string;
}): Promise<QrPollResult> {
  const raw = await shimReflect('pollQr', [args.token]);
  return JSON.parse(raw);
}

export interface QrPollResult {
  status: 'NEW' | 'SCANNED' | 'CONFIRMED' | 'EXPIRED' | 'CANCELED';
  fields?: Record<string, string>;
}

// ── Playback hooks (no-op; state managed by shim) ──────────────────────────

export async function onPlaybackReady(_args: {
  hooksState: HooksState;
  positionMs: number;
  playbackRate: number;
}): Promise<void> {}

export async function onProgressTick(_args: {
  hooksState: HooksState;
  positionMs: number;
  playbackRate: number;
  isPaused: boolean;
}): Promise<void> {}

export async function onStop(_args: {
  hooksState: HooksState;
  positionMs: number;
}): Promise<void> {}

// ── Optional endpoint methods ───────────────────────────────────────────────

export async function getEntries(_args: { itemRefs: string[] }): Promise<EntryList> {
  return { items: [], totalCount: 0 };
}

export async function getTaggedEntries(_args: {
  tag: string;
  scopeLocation: string | null;
  startIndex: number;
  limit: number;
}): Promise<EntryList> {
  return { items: [], totalCount: 0 };
}

export async function tagEntry(_args: {
  itemRef: string;
  tag: string;
  value: boolean;
}): Promise<void> {}

// ── Cleanup ─────────────────────────────────────────────────────────────────

/** Clear the pagination cursor cache. */
export function clearCursorCache(): void {
  cursorCache.clear();
}
