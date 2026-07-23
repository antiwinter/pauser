import type { CatVodCategoryResult, CatVodDetailResult, CatVodItem, CatVodSpider } from './types.js';
import type { SiteEntry, LiveEntry, CatVodConfig } from '../config.js';
import cmsHandler from './cms.js';
import { FatalSpiderInitError } from './jar.js';
import jarHandler from './jar.js';
import drpyHandler from './drpy.js';
import { createIptvSpider } from './iptv.js';


// ── Site handler registry ────────────────────────────────────────────────────
// Only VOD site types (CMS, JAR, drpy) — IPTV lives are routed separately.

interface BaseSpiderHandler {
  name: string;
  type: number[];
  createSpider: (site: SiteEntry) => CatVodSpider;
  canHandle?: (site: SiteEntry) => boolean;
  init?: (config: CatVodConfig) => Promise<void>;
}

type SpiderHandler = BaseSpiderHandler;

const SPIDER_HANDLERS: SpiderHandler[] = [cmsHandler, jarHandler, drpyHandler];

// ── Global state ──────────────────────────────────────────────────────────────

let globalConfig: CatVodConfig | null = null;
let spiderCache: Map<string, CatVodSpider> | null = null;
let snapshot = new Map<string, CatVodItem>();

export async function initSpiders(config: CatVodConfig): Promise<void> {
  globalConfig = config;
  spiderCache = new Map();
  snapshot = new Map();

  for (const handler of SPIDER_HANDLERS) {
    if (handler.init) {
      await handler.init(config).catch(() => {});
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
// Wraps a spider with a Proxy that:
//   1. Catches FatalSpiderInitError on any method and marks the site disabled
//      so the next getSpider() call throws "Site X disabled" and searchAllSites
//      filters it out — no caller-side try/catch needed.
//   2. Returns a per-method safe fallback so the failing call resolves normally
//      and the rest of the cross-site search/listEntry keeps working.
//   3. Preserves the per-method post-processing: rememberItems (snapshot for
//      detail fallback), [search] log line, dumpResult, detail snapshot-fill.

// Safe fallbacks per method — what the wrapper returns when FatalSpiderInitError
// fires. The shape must match the return type so callers don't have to special-case.
const FATAL_FALLBACK: Partial<Record<keyof CatVodSpider, unknown>> = {
  home:      { class: [] },
  homeVideo: { list: [], total: 0 },
  category:  { list: [], total: 0 },
  detail:    { list: [] },
  play:      { url: '' },
  search:    { list: [], total: 0 },
  channels:  { channels: [] },
  proxy:     null,
};

// Methods that produce list-shaped results and should populate the snapshot
// (used by detail() to fall back to the most recent list snapshot if the
// spider returns nothing for a particular id).
const REMEMBER_METHODS = new Set<keyof CatVodSpider>(['home', 'homeVideo', 'category', 'search']);

function snapshotKey(siteKey: string, id: string): string {
  return `${siteKey}-${id}`;
}

function rememberItems(siteKey: string, result: CatVodCategoryResult): void {
  const items = new Map<string, CatVodItem>();
  for (const item of result.list ?? []) {
    if (item.vod_id == null) continue;
    items.set(snapshotKey(siteKey, String(item.vod_id)), item);
  }
  snapshot = items;
}

function markDisabled(key: string, error: FatalSpiderInitError): void {
  const entry = globalConfig?.sites[error.siteKey];
  if (entry?.type === 'site') entry.disabled = error.disableReason;
  console.warn(`[catvod:disabled] site=${error.siteKey} api=${error.api} reason=${error.disableReason} ext=${error.ext}`);
}

// Builds the dumpResult label for a method call — matches the pre-Proxy naming.
function dumpLabel(method: keyof CatVodSpider, args: unknown[]): string {
  switch (method) {
    case 'category': return `category-${args[0]}-${args[1]}`;
    case 'detail':   return `detail-${(args[0] as string[]).join(',')}`;
    case 'play':     return `play-${args[0]}`;
    case 'search':   return `search-${args[0]}-${args[1]}`;
    default:         return String(method);
  }
}

function wrapSpider(inner: CatVodSpider, key: string): CatVodSpider {
  return new Proxy(inner, {
    get(target, prop, _receiver) {
      const value = (target as unknown as Record<string | symbol, unknown>)[prop];
      if (value === undefined) return undefined;
      if (typeof value !== 'function') return value;

      const method = prop as keyof CatVodSpider;

      // Sync hook (isVideoFormat) — wrap to swallow throws and return false.
      // The other methods are async, so the wrapper returns a Promise.
      if (method === 'isVideoFormat') {
        return (url: string) => {
          try {
            return (value as (u: string) => boolean).call(target, url);
          } catch {
            return false;
          }
        };
      }

      return async (...args: unknown[]) => {
        const t0 = Date.now();
        try {
          const result = await (value as (...a: unknown[]) => Promise<unknown>).apply(target, args);

          // Post-process: snapshot for detail fallback.
          if (REMEMBER_METHODS.has(method) && (result as CatVodCategoryResult | undefined)?.list) {
            rememberItems(key, result as CatVodCategoryResult);
          }

          // Post-process: detail-specific fill from snapshot. If the spider
          // returned an item for the id use it; otherwise substitute the
          // most recent snapshot for that site+id.
          if (method === 'detail' && result) {
            const ids = args[0] as string[];
            const r = result as CatVodDetailResult;
            const details = new Map((r.list ?? []).map((item) => [String(item.vod_id), item]));
            r.list = ids
              .map((id) => details.get(id) ?? snapshot.get(snapshotKey(key, id)))
              .filter((item): item is CatVodItem => item != null);
          }

          // Post-process: [search] log line.
          if (method === 'search') {
            const r = result as CatVodCategoryResult | undefined;
            const listLen = r?.list?.length ?? 0;
            console.warn(`[search] site=${key} q="${args[0]}" pg=${args[1]} list=${listLen} ttl=${Date.now() - t0}ms`);
          }

          await dumpResult(key, dumpLabel(method, args), result);
          return result;
        } catch (error) {
          if (error instanceof FatalSpiderInitError) {
            markDisabled(key, error);
            return FATAL_FALLBACK[method];
          }
          // Non-fatal errors propagate to the caller.
          throw error;
        }
      };
    },
  });
}

// ── Spider resolution ─────────────────────────────────────────────────────────

function resolveHandler(site: SiteEntry): SpiderHandler | null {
  for (const handler of SPIDER_HANDLERS) {
    if (!handler.type.includes(site.siteType)) continue;
    if (handler.canHandle && !handler.canHandle(site)) continue;
    return handler;
  }
  return null;
}

export function getSpider(key: string): CatVodSpider {
  if (!globalConfig || !spiderCache) {
    throw new Error('Spiders not initialized');
  }

  const disabledEntry = globalConfig.sites[key];
  const disabledReason = disabledEntry?.type === 'site' ? (disabledEntry.disabled ?? null) : null;
  if (disabledReason) {
    throw new Error(`Site ${key} disabled: ${disabledReason}`);
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
  const handler = resolveHandler(site);
  if (!handler) {
    throw new Error(`No available handler for site type ${site.siteType} api=${site.api}`);
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
  const handler = resolveHandler(site);
  if (!handler) return false;
  return true;
}
