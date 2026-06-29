import type {
  CatVodSpider,
  CatVodHomeResult,
  CatVodCategoryResult,
  CatVodDetailResult,
  CatVodPlayResult,
  CatVodFilterExtend,
} from './types.js';
import type { SiteEntry } from '../config.js';
import { siteExt } from '../config.js';
import {
  normalizeHome,
  normalizeCategory,
  normalizeDetail,
  normalizePlay,
  normalizeSearch,
  safeJsonParse,
} from './normalize.js';

// ── Globals expected by drpy2 spiders ────────────────────────────────────────
// Set once at module init — before any spider code is eval'd.

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const _g = globalThis as Record<string, any>;
const _dispatchSync = _g['__hostDispatchSync'] as
  (ns: string, name: string, argsJson: string) => { status: number; body: string; headers: Record<string, string> };

// Per-spider local storage — flat namespace keyed by "siteKey:field".
// `local` is a single global shared by every drpy spider in this context; it
// routes by [_currentSite], which is set under the drpy lock (withDrpyLock)
// so the active site is always correct. drpy calls are serialized by that lock
// because the spiders share mutable globals (local) and cannot interleave.
const _localStore: Record<string, unknown> = {};
let _currentSite = '';
_g['local'] = {
  get:    (k: string) => { const v = _localStore[_currentSite + ':' + k]; return v !== undefined ? v : null; },
  set:    (k: string, v: unknown) => { _localStore[_currentSite + ':' + k] = v; },
  delete: (k: string) => { delete _localStore[_currentSite + ':' + k]; },
};
_g['_http'] = (url: string, opts: Record<string, unknown> = {}) => {
  const method = String(opts['method'] || 'GET').toLowerCase() === 'post' ? 'post' : 'get';
  const result = _dispatchSync('http', method, JSON.stringify({
    url,
    headers: opts['headers'],
    body:    opts['body'] ?? opts['data'],
  }));
  return { ok: result.status >= 200 && result.status < 300, status: result.status,
           content: result.body, body: result.body, headers: result.headers, url };
};

// Inlined from https://github.com/FongMi/TV — drpy2 spiders depend on these wrappers
_g['req'] = (url: string, opts: Record<string, unknown> = {}) =>
  _g['http'](url, Object.assign({ async: false }, opts));
_g['http'] = (url: string, opts: Record<string, unknown> = {}) => {
  if (opts?.['async'] === false) return _g['_http'](url, opts);
  return new Promise((resolve) =>
    _g['_http'](url, Object.assign({ complete: (res: unknown) => resolve(res) }, opts)),
  ).catch((err: Error) => {
    _g.console?.error?.(err.name, err.message, err.stack);
    return { ok: false, status: 500, url };
  });
};

// ── Spider contract ───────────────────────────────────────────────────────────

interface SpiderObject {
  init(ext: unknown): unknown;
  home(filter: boolean): unknown;
  category(tid: string, pg: string, filter: boolean, extend: Record<string, unknown>): unknown;
  detail(id: string): unknown;
  play(flag: string, id: string, vipFlags: unknown[]): unknown;
  search(key: string, quick: boolean, pg?: string): unknown;
}

// ── Module state ──────────────────────────────────────────────────────────────

const spiders = new Map<string, SpiderObject>();

// ── Spider lifecycle ──────────────────────────────────────────────────────────

async function loadSpider(api: string, ext: string, siteKey: string): Promise<SpiderObject> {
  const cached = spiders.get(siteKey);
  if (cached) return cached;

  // Fetch the spider script and strip ES module syntax so it runs as a classic script
  const code = (await host.http.get({ url: api })).body
    .replace(/^export\s+default\s+/gm, 'var __default_export__ = ')
    .replace(/^export\s+/gm, '')
    .replace(/^import\s+.*?from\s+['"][^'"]+['"]\s*;?\s*$/gm, '');

  // Indirect eval loads into global scope; each spider's __jsEvalReturn() closure is independent
  // eslint-disable-next-line no-eval
  (0, eval)(code);

  // Capture and clean up the factory / direct assignment
  const g = globalThis as Record<string, unknown>;
  const factory = g['__jsEvalReturn'] as (() => SpiderObject) | undefined;
  const spider: SpiderObject = factory ? factory() : (g['__JS_SPIDER__'] as SpiderObject);
  if (factory) delete g['__jsEvalReturn'];

  if (!spider) throw new Error(`drpy: no spider exported from ${api}`);

  const extVal = (ext.startsWith('{') || ext.startsWith('[')) ? JSON.parse(ext) : ext;
  await Promise.resolve(spider.init(extVal));

  spiders.set(siteKey, spider);
  return spider;
}

// Calls a spider method; handles both sync (string/object) and async (Promise) returns.
// drpy spiders occasionally swallow internal HTTP errors and return null/undefined or an
// empty/non-JSON string; downstream normalizers already default missing fields to [],
// so collapse any of those into an empty object instead of letting JSON.parse throw.
async function spiderCall<T>(spider: SpiderObject, method: keyof SpiderObject, ...args: unknown[]): Promise<T> {
  const fn = spider[method] as (...a: unknown[]) => unknown;
  const raw = await Promise.resolve(fn.apply(spider, args));
  return safeJsonParse(raw, 'drpy', method, 'returned non-JSON:') as T;
}

// ── drpy serialization ────────────────────────────────────────────────────────
// All drpy spiders share one QuickJS context and its mutable globals (notably
// `local`, which routes by _currentSite). Serialize every drpy call so the
// active site can't be clobbered mid-call by another drpy site's call — which
// also fixes the previous last-load-wins `local` bug. JAR/CMS spiders don't
// touch these globals and aren't gated, so search can still parallelize them.
let _drpyLock: Promise<unknown> = Promise.resolve();
function withDrpyLock<T>(siteKey: string, fn: () => Promise<T>): Promise<T> {
  const run = _drpyLock.then(async () => {
    const prev = _currentSite;
    _currentSite = siteKey;
    try { return await fn(); }
    finally { _currentSite = prev; }
  });
  _drpyLock = run.catch(() => undefined);
  return run as Promise<T>;
}

// ── Public API ────────────────────────────────────────────────────────────────

/**
 * Creates a drpy2/drpy3 JS spider — types 4/9/10
 * These are JavaScript files that are eval'd and implement the Spider interface
 */
function createDrpySpider(api: string, ext: string, siteKey: string): CatVodSpider {
  // Cache the loaded spider promise to avoid concurrent loads
  let spiderPromise: Promise<SpiderObject> | null = null;

  const getSpider = async (): Promise<SpiderObject> => {
    if (!spiderPromise) {
      spiderPromise = loadSpider(api, ext, siteKey);
    }
    return spiderPromise;
  };

  return {
    async home(filter?: boolean): Promise<CatVodHomeResult> {
      return withDrpyLock(siteKey, async () => {
        const spider = await getSpider();
        const data = await spiderCall<CatVodHomeResult>(
          spider, 'home', filter ?? false,
        );
        return normalizeHome(data);
      });
    },

    async category(tid: string, pg: number, filter?: boolean, extend?: CatVodFilterExtend): Promise<CatVodCategoryResult> {
      return withDrpyLock(siteKey, async () => {
        const spider = await getSpider();
        const data = await spiderCall<CatVodCategoryResult>(
          spider, 'category', tid, String(pg), filter ?? false, extend ?? {},
        );
        return normalizeCategory(data, { totalFallback: data.pagecount });
      });
    },

    async detail(ids: string[]): Promise<CatVodDetailResult> {
      return withDrpyLock(siteKey, async () => {
        const spider = await getSpider();
        const data = await spiderCall<CatVodDetailResult>(spider, 'detail', ids.join(','));
        return normalizeDetail(data);
      });
    },

    async play(flag: string, epUrl: string, vipFlags?: string[]): Promise<CatVodPlayResult> {
      return withDrpyLock(siteKey, async () => {
        const spider = await getSpider();
        const data = await spiderCall<CatVodPlayResult>(spider, 'play', flag, epUrl, vipFlags ?? []);
        return normalizePlay(data);
      });
    },

    async search(query: string, pg: number, quick?: boolean): Promise<CatVodCategoryResult> {
      return withDrpyLock(siteKey, async () => {
        const spider = await getSpider();
        const data = await spiderCall<CatVodCategoryResult>(spider, 'search', query, quick ?? false, String(pg));
        return normalizeSearch(data, { useListLength: true });
      });
    },
  };
}

export function resetSpiders(): void {
  spiders.clear();
  // Clear per-site local store entries
  for (const k of Object.keys(_localStore)) delete _localStore[k];
}

export default {
  name: 'drpy',
  type: [4, 9, 10],
  createSpider: (site: SiteEntry) =>
    createDrpySpider(site.api, siteExt(site), site.key),
};
