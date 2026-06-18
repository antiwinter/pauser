import type {
  CatVodSpider,
  CatVodHomeResult,
  CatVodCategoryResult,
  CatVodDetailResult,
  CatVodPlayResult,
  CatVodFilterExtend,
} from './types.js';
import type { SiteEntry, CatVodConfig } from '../config.js';
import { parseSpiderField, siteExt } from '../config.js';
import {
  normalizeHome,
  normalizeCategory,
  normalizeDetail,
  normalizePlay,
  normalizeSearch,
  parseReflectResult,
} from './normalize.js';
// Spider instance handles keyed by siteKey — one engine = one endpoint = module-level cache
const spiderHandles = new Map<string, string>();

export async function resetSpiders(jarUrl?: string, md5?: string): Promise<void> {
  spiderHandles.clear();
  // Use clearInstances rather than clear() — Guard JARs rely on native state set up by
  // Init.init(Context) / DexNative.getLoader(). Calling clear() recreates the primary
  // DexClassLoader and re-runs Init.init(), but the native .so is process-global and
  // does not reinitialize cleanly on a second getLoader() call, leaving the secondary
  // loader's Context reference null. clearInstances() drops spider handles without
  // touching the loaded JAR or native state.
  await host.jar.clearInstances();
}

// ── CatVod/spider class name constants ───────────────────────────────────────

const CATVOD_INIT         = 'com.github.catvod.spider.Init';
const CATVOD_DEX_NATIVE   = 'com.github.catvod.spider.DexNative';
const CATVOD_INIT_ORIGIN  = 'com.github.catvod.spider.InitOrigin';
const CATVOD_SHIM_ASSET   = 'catvod-shim.jar';

// ── JAR bootstrap ─────────────────────────────────────────────────────────────

export async function ensureJar(jarUrl: string, md5?: string): Promise<void> {
  await host.jar.loadAsset({ name: CATVOD_SHIM_ASSET });
  await host.jar.load({ url: jarUrl, md5 });
  await host.jar.boot({
    url: jarUrl,
    initClass: CATVOD_INIT,
    dexNativeClass: CATVOD_DEX_NATIVE,
    initOriginClass: CATVOD_INIT_ORIGIN,
  });
}

// ── Spider instance lifecycle ─────────────────────────────────────────────────

async function loadSpider(
  jarUrl: string,
  api: string,
  ext: string,
  siteKey: string,
): Promise<string> {
  const cached = spiderHandles.get(siteKey);
  if (cached) return cached;

  const cls = spiderClass(api);
  // FongMi approach: direct newInstance() — BaseSpiderGuard.<init> internally calls
  // Init.getSpider(shortName) to populate the wrapped spider field from config.db.
  const handle = await host.jar.reflect({ url: jarUrl, cls, method: 'newInstance', args: [] });
  if (cls.endsWith('Guard')) {
    // FongMi line 182: sp.homeContent(false) — preloads spider internal state
    // (cookies, sessions) that categoryContent/detailContent depend on.
    await host.jar.reflect({ url: jarUrl, cls, method: 'homeContent', instance: handle, args: [false] }).catch(() => undefined);
  } else {
    await host.jar.reflect({ url: jarUrl, cls, method: 'init', instance: handle, args: [ext] }).catch(() => undefined);
  }
  spiderHandles.set(siteKey, handle);
  return handle;
}

// ── Public API ────────────────────────────────────────────────────────────────

/**
 * Creates a JAR spider — type 3 (csp_*)
 * These are Java classes loaded from a remote JAR file
 */
function createJarSpider(
  jarUrl: string,
  md5: string | undefined,
  api: string,
  ext: string,
  siteKey: string,
): CatVodSpider {
  // Cache the spider handle loading promise
  let handlePromise: Promise<string> | null = null;

  const getHandle = async (): Promise<string> => {
    // Ensure JAR is loaded first
    await ensureJar(jarUrl, md5);
    if (!handlePromise) {
      handlePromise = loadSpider(jarUrl, api, ext, siteKey);
    }
    return handlePromise;
  };

  const cls = spiderClass(api);

  return {
    async home(filter?: boolean): Promise<CatVodHomeResult> {
      const handle = await getHandle();
      const raw = await host.jar.reflect({
        url: jarUrl, cls, method: 'homeContent', instance: handle, args: [filter ?? false],
      });
      const data = parseReflectResult(raw) as CatVodHomeResult;
      return normalizeHome(data);
    },

    async category(tid: string, pg: number, filter?: boolean, extend?: CatVodFilterExtend): Promise<CatVodCategoryResult> {
      const handle = await getHandle();
      const raw = await host.jar.reflect({
        url: jarUrl, cls, method: 'categoryContent',
        instance: handle, args: [tid, String(pg), filter ?? false, extend ?? {}],
      });
      const data = parseReflectResult(raw) as CatVodCategoryResult;
      return normalizeCategory(data);
    },

    async detail(ids: string[]): Promise<CatVodDetailResult> {
      const handle = await getHandle();
      const raw = await host.jar.reflect({
        url: jarUrl, cls, method: 'detailContent',
        instance: handle, args: [ids],
      });
      const data = parseReflectResult(raw, true) as CatVodDetailResult;
      return normalizeDetail(data);
    },

    async play(flag: string, epUrl: string, vipFlags?: string[]): Promise<CatVodPlayResult> {
      const handle = await getHandle();
      const raw = await host.jar.reflect({
        url: jarUrl, cls, method: 'playerContent',
        instance: handle, args: [flag, epUrl, vipFlags ?? []],
      });
      const data = parseReflectResult(raw) as CatVodPlayResult;
      return normalizePlay(data);
    },

    async search(query: string, pg: number, quick?: boolean): Promise<CatVodCategoryResult> {
      const handle = await getHandle();
      const raw = await host.jar.reflect({
        url: jarUrl, cls, method: 'searchContent',
        instance: handle, args: [query, quick ?? false, String(pg)],
      });
      const data = parseReflectResult(raw) as CatVodCategoryResult;
      return normalizeSearch(data, { useListLength: true });
    },
  };
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function spiderClass(api: string): string {
  return `com.github.catvod.spider.${api.replace(/^csp_/, '')}`;
}

let jarConfig: { url: string; md5?: string } | null = null;
let jarInitialized = false;
let jarFailed = false;

async function init(state: { config: { spider?: string } }): Promise<void> {
  if (jarInitialized) return;
  jarInitialized = true;

  jarConfig = parseSpiderField(state.config.spider);
  if (!jarConfig) {
    jarFailed = true;
    return;
  }

  try {
    await ensureJar(jarConfig.url, jarConfig.md5);
  } catch {
    jarFailed = true;
  }
}

function canHandle(): boolean {
  return !jarFailed;
}

export default {
  name: 'jar',
  type: [3],
  init,
  canHandle,
  createSpider: (site: SiteEntry) => {
    if (!jarConfig) throw new Error('JAR handler not initialized');
    return createJarSpider(jarConfig.url, jarConfig.md5, site.api, siteExt(site), site.key);
  },
};
