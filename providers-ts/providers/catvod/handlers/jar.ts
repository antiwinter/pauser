import type { EntryList, EntryDetail, PlaybackSpec } from '../../../utils/types.js';
import type { CatVodItem, CatVodDetail } from '../mapper.js';
import { vodItemToEntry, vodDetailToEntryDetail, parseEpisodes } from '../mapper.js';
import { encodeRef } from '../ref.js';

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

async function getSpider(
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

export async function jarHome(
  jarUrl: string,
  md5: string | undefined,
  api: string,
  ext: string,
  siteKey: string,
): Promise<EntryList> {
  await ensureJar(jarUrl, md5);
  const handle = await getSpider(jarUrl, api, ext, siteKey);
  const cls    = spiderClass(api);
  const raw    = await host.jar.reflect({
    url: jarUrl, cls, method: 'homeContent', instance: handle, args: [false],
  });
  const data = JSON.parse(raw);
  return {
    items: ((data.class ?? []) as Array<{ type_id: string | number; type_name?: string }>).map((c) => ({
      id:    encodeRef({ type: 'cat', key: siteKey, tid: String(c.type_id) }),
      title: c.type_name ?? String(c.type_id),
      type:  'Folder' as const,
      cover: null,
    })),
    totalCount: (data.class ?? []).length,
  };
}

export async function jarCategory(
  jarUrl: string,
  api: string,
  ext: string,
  siteKey: string,
  tid: string,
  pg: number,
): Promise<EntryList> {
  const handle = await getSpider(jarUrl, api, ext, siteKey);
  const cls    = spiderClass(api);
  const raw    = await host.jar.reflect({
    url: jarUrl, cls, method: 'categoryContent',
    instance: handle, args: [tid, String(pg), false, {}],
  });
  const data = JSON.parse(raw);
  return {
    items:      ((data.list ?? []) as CatVodItem[]).map((item) => vodItemToEntry(item, siteKey)),
    totalCount: data.total ?? 0,
  };
}

export async function jarDetail(
  jarUrl: string,
  api: string,
  ext: string,
  siteKey: string,
  id: string,
): Promise<CatVodDetail> {
  const handle = await getSpider(jarUrl, api, ext, siteKey);
  const cls    = spiderClass(api);
  const raw    = await host.jar.reflect({
    url: jarUrl, cls, method: 'detailContent',
    instance: handle, args: [[id]],
  });
  const data = raw && raw !== 'null' ? JSON.parse(raw) : {};
  return data.list?.[0] ?? { vod_id: id, vod_name: id };
}

export async function jarPlay(
  jarUrl: string,
  api: string,
  ext: string,
  siteKey: string,
  flag: string,
  epUrl: string,
): Promise<PlaybackSpec> {
  const handle = await getSpider(jarUrl, api, ext, siteKey);
  const cls    = spiderClass(api);
  const raw    = await host.jar.reflect({
    url: jarUrl, cls, method: 'playerContent',
    instance: handle, args: [flag, epUrl, []],
  });
  const data = JSON.parse(raw);
  return {
    url:            data.url ?? null,
    headers:        data.header ?? {},
    mimeType:       data.type ?? null,
    title:          '',
    durationMs:     null,
    subtitleTracks: [],
    hooksState:     {},
  };
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function spiderClass(api: string): string {
  return `com.github.catvod.spider.${api.replace(/^csp_/, '')}`;
}
