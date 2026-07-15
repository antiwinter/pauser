import type { CatVodDetailResult, CatVodSpider } from './types.js';
import type { SiteEntry, LiveEntry, CatVodConfig } from '../config.js';
import cmsHandler from './cms.js';
import jarHandler from './jar.js';
import drpyHandler from './drpy.js';
import { createIptvSpider } from './iptv.js';

// ── Site handler registry ────────────────────────────────────────────────────
// Only VOD site types (CMS, JAR, drpy) — IPTV lives are routed separately.

interface BaseSpiderHandler {
  name: string;
  type: number[];
  createSpider: (site: SiteEntry) => CatVodSpider;
}

interface SpiderHandlerWithInit extends BaseSpiderHandler {
  init: (config: CatVodConfig) => Promise<void>;
  canHandle: (site: SiteEntry) => boolean;
}

type SpiderHandler = BaseSpiderHandler | SpiderHandlerWithInit;

const SPIDER_HANDLERS: SpiderHandler[] = [cmsHandler, jarHandler, drpyHandler];

const HANDLER_BY_TYPE = new Map<number, SpiderHandler>();
for (const h of SPIDER_HANDLERS) {
  for (const t of h.type) {
    if (!HANDLER_BY_TYPE.has(t)) HANDLER_BY_TYPE.set(t, h);
  }
}

// ── Global state ──────────────────────────────────────────────────────────────

let globalConfig: CatVodConfig | null = null;
let spiderCache: Map<string, CatVodSpider> | null = null;

export async function initSpiders(config: CatVodConfig): Promise<void> {
  globalConfig = config;
  spiderCache = new Map();

  for (const handler of SPIDER_HANDLERS) {
    if ("init" in handler) {
      await (handler as SpiderHandlerWithInit).init(config).catch(() => {});
    }
  }
}

// ── Response dump ─────────────────────────────────────────────────────────────
// Saves parsed spider results to dump/ for debugging — the sandbox scopes by provider.

async function dumpResult(key: string, method: string, result: unknown): Promise<void> {
  try {
    await host.fs.write({
      path: `dump/${key}-${method}.json`,
      content: JSON.stringify(result, null, 2),
    });
  } catch { /* ignore dump failures */ }
}

// ── Spider wrapper ────────────────────────────────────────────────────────────
// Wraps a spider so every method call dumps its result before returning.

function wrapSpider(inner: CatVodSpider, key: string): CatVodSpider {
  let detailCacheKey: string | null = null;
  let detailCacheResult: CatVodDetailResult | null = null;
  const spider: CatVodSpider = {
    async home(filter?: boolean) {
      const result = await inner.home(filter);
      await dumpResult(key, 'home', result);
      return result;
    },

    async category(tid, pg, filter?, extend?) {
      const result = await inner.category(tid, pg, filter, extend);
      await dumpResult(key, `category-${tid}-${pg}`, result);
      return result;
    },

    async detail(ids) {
      const cacheKey = JSON.stringify(ids);
      if (detailCacheKey === cacheKey && detailCacheResult) return detailCacheResult;
      const result = await inner.detail(ids);
      detailCacheKey = cacheKey;
      detailCacheResult = result;
      await dumpResult(key, `detail-${ids.join(',')}`, result);
      return result;
    },

    async play(flag, id, vipFlags?) {
      const result = await inner.play(flag, id, vipFlags);
      await dumpResult(key, `play-${flag}`, result);
      return result;
    },
  };

  if (inner.homeVideo) {
    spider.homeVideo = async () => {
      const result = await inner.homeVideo!();
      await dumpResult(key, 'homeVideo', result);
      return result;
    };
  }
  if (inner.search) {
    spider.search = async (query, pg, quick?) => {
      const t0 = Date.now();
      const result = await inner.search!(query, pg, quick);
      const listLen = result?.list?.length ?? 0;
      console.warn(`[search] site=${key} q="${query}" pg=${pg} list=${listLen} ttl=${Date.now() - t0}ms`);
      await dumpResult(key, `search-${query}-${pg}`, result);
      return result;
    };
  }
  if (inner.isVideoFormat) {
    spider.isVideoFormat = (url) => inner.isVideoFormat!(url);
  }
  if (inner.proxy) {
    spider.proxy = async (params) => inner.proxy!(params);
  }
  if (inner.channels) {
    spider.channels = async () => {
      const result = await inner.channels!();
      await dumpResult(key, 'channels', result);
      return result;
    };
  }

  return spider;
}

// ── Spider resolution ─────────────────────────────────────────────────────────

export function getSpider(key: string): CatVodSpider {
  if (!globalConfig || !spiderCache) {
    throw new Error('Spiders not initialized');
  }

  const cached = spiderCache.get(key);
  if (cached) return cached;

  const entry = globalConfig.sites[key];
  if (!entry) {
    throw new Error(`Entry not found: ${key}`);
  }

  // Live entries → IPTV spider (bypasses site handler registry)
  if (entry.type === 'live') {
    const spider = wrapSpider(createIptvSpider(entry as LiveEntry), key);
    spiderCache.set(key, spider);
    return spider;
  }

  // Site entries → type-based handler routing
  const site = entry as SiteEntry;
  const handler = HANDLER_BY_TYPE.get(site.siteType);
  if (!handler) {
    throw new Error(`No available handler for site type ${site.siteType}`);
  }

  const spider = wrapSpider(handler.createSpider(site), key);
  spiderCache.set(key, spider);
  return spider;
}

export function getConfig(): CatVodConfig {
  if (!globalConfig) {
    throw new Error('Config not initialized');
  }
  return globalConfig;
}

export function canHandleSite(key: string): boolean {
  if (!globalConfig) return false;

  const entry = globalConfig.sites[key];
  if (!entry) return false;

  // Live/IPTV entries are always handleable
  if (entry.type === 'live') return true;

  if (entry.type !== 'site') return false;
  const site = entry as SiteEntry;
  const handler = HANDLER_BY_TYPE.get(site.siteType);
  if (!handler) return false;

  if ('canHandle' in handler) {
    return (handler as SpiderHandlerWithInit).canHandle(site);
  }

  return true;
}
