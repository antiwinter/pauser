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

const spiderHandles = new Map<string, string>();

// Serializes ensureJar() across concurrent callers; the JAR's native boot is process-global
// and a re-entrant call corrupts the secondary loader's Context reference (boot race).
let jarBootPromise: Promise<void> | null = null;

let relayBaseUrl: string | null = null;

export async function resetSpiders(jarUrl?: string, md5?: string): Promise<void> {
  spiderHandles.clear();
  // Use clearInstances rather than clear() — the JAR's native .so is process-global and
  // doesn't reinitialize cleanly on a second getLoader() call, leaving the secondary
  // loader's Context reference null.
  await host.jar.clearInstances();
}

const CATVOD_INIT         = 'com.github.catvod.spider.Init';
const CATVOD_DEX_NATIVE   = 'com.github.catvod.spider.DexNative';
const CATVOD_INIT_ORIGIN  = 'com.github.catvod.spider.InitOrigin';
const CATVOD_SHIM_ASSET   = 'catvod-shim.jar';

export async function ensureJar(jarUrl: string, md5?: string): Promise<void> {
  if (jarBootPromise) return jarBootPromise;
  jarBootPromise = (async () => {
    await host.jar.loadAsset({ name: CATVOD_SHIM_ASSET });
    await host.jar.load({ url: jarUrl, md5 });
    await host.jar.boot({
      url: jarUrl,
      initClass: CATVOD_INIT,
      dexNativeClass: CATVOD_DEX_NATIVE,
      initOriginClass: CATVOD_INIT_ORIGIN,
    });
    // Stable token "catvod": some JARs build loopback URLs of the form
    // http://127.0.0.1:<port>/proxy?... and call back into the app to resolve them. The
    // host exposes the recipe at http://127.0.0.1:<server-port>/relay/catvod.
    const reg = await host.relay.register({
      cls: 'com.github.catvod.spider.Proxy',
      method: 'proxy',
      token: 'catvod',
    });
    relayBaseUrl = reg.baseUrl;
  })();
  try {
    await jarBootPromise;
  } catch (e) {
    // A failed boot must be retryable — drop the cached promise so the next caller
    // can re-attempt the load+boot sequence.
    jarBootPromise = null;
    throw e;
  }
}

// Decodes loopback proxy URLs the JAR embeds in playerContent results. The JAR's port-probe
// loop (9978–9999) usually fails against our server (port 7920), so the URL ends up with
// port -1; we recognise either form. When the URL carries an embedded `url=<encoded>`
// upstream we extract it directly — segments point straight at the CDN, no relay needed.
function rewriteProxyUrl(url: string | undefined): string | undefined {
  if (!url) return url;
  const m = url.match(/^https?:\/\/(?:127\.0\.0\.1|localhost):-?\d+\/proxy\b(.*)$/i);
  if (!m) return url;
  const query = m[1].startsWith('?') ? m[1] : (m[1] || '');
  const upstream = decodeProxyParam(query, 'url');
  if (upstream) return upstream;
  return relayBaseUrl ? relayBaseUrl + query : url;
}

// URLSearchParams isn't bundled in QuickJS.
function decodeProxyParam(query: string, key: string): string | null {
  for (const part of query.split('&')) {
    const eq = part.indexOf('=');
    if (eq < 0) continue;
    if (decodeURIComponent(part.slice(0, eq)) === key) {
      return decodeURIComponent(part.slice(eq + 1));
    }
  }
  return null;
}

async function loadSpider(
  jarUrl: string,
  api: string,
  ext: string,
  siteKey: string,
): Promise<string> {
  const cached = spiderHandles.get(siteKey);
  if (cached) return cached;

  const cls = spiderClass(api);
  const handle = await host.jar.reflect({ url: jarUrl, cls, method: 'newInstance', args: [] });
  if (cls.endsWith('Guard')) {
    // Guard init(ctx, ext) populates the wrapped spider via Init.getSpider; without it
    // homeContent returns {"class":[]}. A failure on homeContent means the wrapped
    // spider is still null (boot race) — drop the handle so the next caller retries.
    await host.jar.reflect({ url: jarUrl, cls, method: 'init', instance: handle, args: [ext] }).catch(() => undefined);
    try {
      await host.jar.reflect({ url: jarUrl, cls, method: 'homeContent', instance: handle, args: [false] });
    } catch {
      spiderHandles.delete(siteKey);
      throw new Error(`loadSpider: ${cls} init failed`);
    }
  } else {
    await host.jar.reflect({ url: jarUrl, cls, method: 'init', instance: handle, args: [ext] }).catch(() => undefined);
  }
  spiderHandles.set(siteKey, handle);
  return handle;
}

function createJarSpider(
  jarUrl: string,
  md5: string | undefined,
  api: string,
  ext: string,
  siteKey: string,
): CatVodSpider {
  let handlePromise: Promise<string> | null = null;

  const getHandle = async (): Promise<string> => {
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
      const data = normalizePlay(parseReflectResult(raw) as CatVodPlayResult);
      if (data) {
        data.url = rewriteProxyUrl(data.url);
        data.play_url = rewriteProxyUrl(data.play_url);
      }
      return data;
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

function spiderClass(api: string): string {
  return `com.github.catvod.spider.${api.replace(/^csp_/, '')}`;
}

let jarConfig: { url: string; md5?: string } | null = null;
let jarInitialized = false;
let jarFailed = false;

async function init(config: { spider?: string }): Promise<void> {
  if (jarInitialized) return;
  jarInitialized = true;

  jarConfig = parseSpiderField(config.spider);
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

