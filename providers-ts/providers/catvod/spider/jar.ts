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
let jarBootPromise: Promise<string> | null = null;
const jarHandleByUrl = new Map<string, string>();

let relayBaseUrl: string | null = null;

export async function resetSpiders(jarUrl?: string, md5?: string): Promise<void> {
  spiderHandles.clear();
  jarHandleByUrl.clear();
  // Use clearInstances rather than clear() — the JAR's native .so is process-global and
  // doesn't reinitialize cleanly on a second getLoader() call, leaving the secondary
  // loader's Context reference null.
  await host.jar.clearInstances();
}

const CATVOD_INIT         = 'com.github.catvod.spider.Init';
const CATVOD_DEX_NATIVE   = 'com.github.catvod.spider.DexNative';
const CATVOD_INIT_ORIGIN  = 'com.github.catvod.spider.InitOrigin';

/** Returns the primary handle for the loaded JAR. Callers thread the handle through every
 *  subsequent loadClass / reflect / registerLoader call — Kotlin identifies the loader by
 *  handle, not by URL, so warming the cache from a {path} source and looking up by URL
 *  used to silently miss. */
export async function ensureJar(jarUrl: string, md5?: string): Promise<string> {
  const cached = jarHandleByUrl.get(jarUrl);
  if (cached) return cached;
  if (jarBootPromise) return jarBootPromise;
  jarBootPromise = (async () => {
    // The catvod-shim.jar is auto-fused into the app classloader by
    // [JsProviderLoader] at bundle load time — by the time we get here, the
    // shim classes (CATVOD_INIT, CATVOD_INIT_ORIGIN, …) are visible via the
    // app classloader and adoptParent('context') resolves them naturally.
    // Kotlin stages Url/Buffer sources to `code_cache/jars/<safe>.jar`; Path
    // sources are hardlinked `sandbox/<rel>` → `code_cache/jars/<safe>.jar`.
    // The handle returned by load() unifies lookups regardless of source variant.
    const cachePath = await cachePathFor(jarUrl);
    let handle: string;
    if (await host.fs.exists({ path: cachePath })) {
      // Warm-path MD5 only: base64 + checksum allocates ~2.3× file size on the JS heap; cold path trusts the server's md5 param.
      const buf = await host.fs.read({ path: cachePath, encoding: 'base64' });
      const digest = await host.crypto.checksum({ input: buf, algo: 'md5', encoding: 'base64' });
      if (!md5 || digest === md5) {
        handle = await host.jar.load({ source: { path: cachePath } });
      } else {
        handle = await host.jar.load({ source: { url: jarUrl } });
      }
    } else {
      handle = await host.jar.load({ source: { url: jarUrl } });
    }
    // clinit extracts & System.loadLibrary's the .so; fails fast on wrong arch.
    await host.jar.loadClass({ handle, cls: CATVOD_DEX_NATIVE });
    // Init.init(Context) — sets Application ref, kicks off secondary-loader
    // construction on a background thread.
    await host.jar.reflect({ handle, cls: CATVOD_INIT, method: 'init', args: [] });
    // Poll Init.loader() until the secondary DexClassLoader is ready.
    // `reflect` returns the parsed value already (host dispatcher JSON-parses once);
    // a second JSON.parse here would break when the bridge returns an instance handle
    // like `obj_22` (no surrounding quotes after the host-side parse).
    let instanceHandle: string | null = null;
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      const raw = await host.jar.reflect({ handle, cls: CATVOD_INIT, method: 'loader' });
      if (raw && raw !== 'null') { instanceHandle = raw; break; }
      await host.timer.sleep({ ms: 50 });
    }
    if (!instanceHandle) throw new Error(`Init.loader() returned null after 5000ms for ${jarUrl}`);
    // Register the ClassLoader as secondary so reflect finds classes in it.
    await host.jar.registerLoader({ handle, instanceHandle });
    // Patch secondary loader's parent → app classloader (shim classes auto-injected at bundle load).
    await host.jar.adoptParent({ childKey: `secondary:${handle}`, parentKey: 'context' });
    // InitOrigin.init(Context) on the secondary loader — wires the catvod
    // plugin system into our HTTP server.
    await host.jar.reflect({ handle, cls: CATVOD_INIT_ORIGIN, method: 'init', args: [] });
    // Stable token "catvod": some JARs build loopback URLs of the form
    // http://127.0.0.1:<port>/proxy?... and call back into the app to resolve them. The
    // host exposes the recipe at http://127.0.0.1:<server-port>/relay/catvod.
    const reg = await host.relay.register({
      cls: 'com.github.catvod.spider.Proxy',
      method: 'proxy',
      token: 'catvod',
    });
    relayBaseUrl = reg.baseUrl;
    jarHandleByUrl.set(jarUrl, handle);
    return handle;
  })();
  try {
    return await jarBootPromise;
  } catch (e) {
    // A failed boot must be retryable — drop the cached promise so the next caller
    // can re-attempt the load+boot sequence.
    jarBootPromise = null;
    throw e;
  }
}

async function cachePathFor(jarUrl: string): Promise<string> {
  const urlKey = (await host.crypto.checksum({ input: jarUrl, algo: 'sha-256' })).slice(0, 16);
  return `jars/${urlKey}.jar`;
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
  handle: string,
  api: string,
  ext: string,
  siteKey: string,
): Promise<string> {
  const cached = spiderHandles.get(siteKey);
  if (cached) return cached;

  const cls = spiderClass(api);
  const spiderInstance = await host.jar.reflect({ handle, cls, method: 'newInstance', args: [] });
  if (cls.endsWith('Guard')) {
    // Guard init(ctx, ext) populates the wrapped spider via Init.getSpider; without it
    // homeContent returns {"class":[]}. A failure on homeContent means the wrapped
    // spider is still null (boot race) — drop the handle so the next caller retries.
    await host.jar.reflect({ handle, cls, method: 'init', instance: spiderInstance, args: [ext] }).catch(() => undefined);
    try {
      await host.jar.reflect({ handle, cls, method: 'homeContent', instance: spiderInstance, args: [false] });
    } catch {
      spiderHandles.delete(siteKey);
      throw new Error(`loadSpider: ${cls} init failed`);
    }
  } else {
    await host.jar.reflect({ handle, cls, method: 'init', instance: spiderInstance, args: [ext] }).catch(() => undefined);
  }
  spiderHandles.set(siteKey, spiderInstance);
  return spiderInstance;
}

function spiderClass(api: string): string {
  return `com.github.catvod.spider.${api.replace(/^csp_/, '')}`;
}

function createJarSpider(
  jarUrl: string,
  md5: string | undefined,
  api: string,
  ext: string,
  siteKey: string,
): CatVodSpider {
  let handlePromise: Promise<string> | null = null;
  let spiderInstancePromise: Promise<string> | null = null;

  const getHandle = async (): Promise<string> => {
    if (!handlePromise) {
      handlePromise = ensureJar(jarUrl, md5);
    }
    return handlePromise;
  };

  const getSpiderInstance = async (): Promise<string> => {
    const handle = await getHandle();
    if (!spiderInstancePromise) {
      spiderInstancePromise = loadSpider(handle, api, ext, siteKey);
    }
    return spiderInstancePromise;
  };

  const cls = spiderClass(api);

  return {
    async home(filter?: boolean): Promise<CatVodHomeResult> {
      const handle = await getHandle();
      const instance = await getSpiderInstance();
      const raw = await host.jar.reflect({
        handle, cls, method: 'homeContent', instance, args: [filter ?? false],
      });
      const data = parseReflectResult(raw) as CatVodHomeResult;
      return normalizeHome(data);
    },

    async category(tid: string, pg: number, filter?: boolean, extend?: CatVodFilterExtend): Promise<CatVodCategoryResult> {
      const handle = await getHandle();
      const instance = await getSpiderInstance();
      const raw = await host.jar.reflect({
        handle, cls, method: 'categoryContent',
        instance, args: [tid, String(pg), filter ?? false, extend ?? {}],
      });
      const data = parseReflectResult(raw) as CatVodCategoryResult;
      return normalizeCategory(data);
    },

    async detail(ids: string[]): Promise<CatVodDetailResult> {
      const handle = await getHandle();
      const instance = await getSpiderInstance();
      const raw = await host.jar.reflect({
        handle, cls, method: 'detailContent',
        instance, args: [ids],
      });
      const data = parseReflectResult(raw, true) as CatVodDetailResult;
      return normalizeDetail(data);
    },

    async play(flag: string, epUrl: string, vipFlags?: string[]): Promise<CatVodPlayResult> {
      const handle = await getHandle();
      const instance = await getSpiderInstance();
      const raw = await host.jar.reflect({
        handle, cls, method: 'playerContent',
        instance, args: [flag, epUrl, vipFlags ?? []],
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
      const instance = await getSpiderInstance();
      const raw = await host.jar.reflect({
        handle, cls, method: 'searchContent',
        instance, args: [query, quick ?? false, String(pg)],
      });
      const data = parseReflectResult(raw) as CatVodCategoryResult;
      return normalizeSearch(data, { useListLength: true });
    },
  };
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
