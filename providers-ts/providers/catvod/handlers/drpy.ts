import type {
  CatVodSpider,
  CatVodHomeResult,
  CatVodCategoryResult,
  CatVodDetail,
  CatVodPlayResult,
} from '../types.js';

// ── Globals expected by drpy2 spiders ────────────────────────────────────────
// Set once at module init — before any spider code is eval'd.

const _g = globalThis as Record<string, unknown>;
const _dispatchSync = _g['__hostDispatchSync'] as
  (ns: string, name: string, argsJson: string) => { status: number; body: string; headers: Record<string, string> };

const _localStore: Record<string, Record<string, unknown>> = {};
_g['local'] = {
  get:    (k: string, f: string) => { const s = _localStore[k]; return s?.[f] !== undefined ? s[f] : null; },
  set:    (k: string, f: string, v: unknown) => { (_localStore[k] ??= {})[f] = v; },
  delete: (k: string, f: string) => { if (_localStore[k]) delete _localStore[k][f]; },
};
_g['setTimeout']   = (fn: () => void) => { Promise.resolve().then(fn); return 0; };
_g['clearTimeout'] = () => {};
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
    console.error(err.name, err.message, err.stack);
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
async function spiderCall<T>(spider: SpiderObject, method: keyof SpiderObject, ...args: unknown[]): Promise<T> {
  const fn = spider[method] as (...a: unknown[]) => unknown;
  const raw = await Promise.resolve(fn.apply(spider, args));
  return (typeof raw === 'string' ? JSON.parse(raw) : raw) as T;
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
    async home(): Promise<CatVodHomeResult> {
      const spider = await getSpider();
      const data = await spiderCall<{ class?: Array<{ type_id: string | number; type_name?: string }> }>(
        spider, 'home', false,
      );
      return { class: data.class ?? [] };
    },

    async category(tid: string, pg: number): Promise<CatVodCategoryResult> {
      const spider = await getSpider();
      const data = await spiderCall<CatVodCategoryResult>(
        spider, 'category', tid, String(pg), false, {},
      );
      return {
        list: data.list ?? [],
        total: data.total ?? data.pagecount ?? 0,
      };
    },

    async detail(id: string): Promise<CatVodDetail> {
      const spider = await getSpider();
      const data = await spiderCall<{ list?: CatVodDetail[] }>(spider, 'detail', id);
      return data.list?.[0] ?? ({ vod_id: id } as CatVodDetail);
    },

    async play(flag: string, epUrl: string): Promise<CatVodPlayResult> {
      const spider = await getSpider();
      const data = await spiderCall<CatVodPlayResult>(spider, 'play', flag, epUrl, []);
      return {
        url: data.url,
        header: data.header,
        type: data.type,
      };
    },

    async search(query: string, pg: number): Promise<CatVodCategoryResult> {
      const spider = await getSpider();
      const data = await spiderCall<CatVodCategoryResult>(spider, 'search', query, false, String(pg));
      return {
        list: data.list ?? [],
        total: data.list?.length ?? 0,
      };
    },
  };
}

export default {
  name: 'drpy',
  type: [4, 9, 10],
  createSpider: (api: string, ext: string, siteKey: string) => createDrpySpider(api, ext, siteKey),
};
