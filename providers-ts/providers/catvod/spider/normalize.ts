import type {
  CatVodHomeResult,
  CatVodCategoryResult,
  CatVodDetailResult,
  CatVodPlayResult,
} from './types.js';

// ── Result Normalization Helpers ──────────────────────────────────────────────
// Centralized defensive defaults — eliminates duplicated ?? [] / ?? 0 patterns
// across cms, drpy, and jar handlers.

export function normalizeHome(data: CatVodHomeResult): CatVodHomeResult {
  return { class: data.class ?? [], filters: data.filters };
}

export function normalizeCategory(
  data: CatVodCategoryResult,
  options?: { totalFallback?: number },
): CatVodCategoryResult {
  return {
    list: data.list ?? [],
    total: data.total ?? options?.totalFallback ?? 0,
    pagecount: data.pagecount,
    filters: data.filters,
    msg: data.msg,
  };
}

export function normalizeDetail(data: CatVodDetailResult): CatVodDetailResult {
  return { list: data.list ?? [] };
}

export function normalizePlay(data: CatVodPlayResult): CatVodPlayResult {
  return data;
}

export function normalizeSearch(
  data: CatVodCategoryResult,
  options?: { useListLength?: boolean },
): CatVodCategoryResult {
  const total = options?.useListLength
    ? (data.list?.length ?? 0)
    : (data.total ?? 0);
  return {
    list: data.list ?? [],
    total,
    msg: data.msg,
  };
}

// ── JSON Parsing Helpers ──────────────────────────────────────────────────────

export function parseResponseBody(body: string): unknown {
  if (!body || body.length === 0) return {};
  try {
    return JSON.parse(body);
  } catch (e) {
    throw new Error(`Bad JSON response: ${body.slice(0, 100)}`);
  }
}

export function parseReflectResult(raw: string, allowNull?: boolean): unknown {
  if (allowNull && (!raw || raw === 'null')) return {};
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch (e) {
    throw new Error(`Bad JAR response: ${raw.slice(0, 100)}`);
  }
}
