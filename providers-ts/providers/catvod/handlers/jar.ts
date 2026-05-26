import type { EntryList, EntryDetail, PlaybackSpec } from '../../../utils/types.js';
import type { CatVodItem, CatVodDetail } from '../mapper.js';
import { vodItemToEntry, vodDetailToEntryDetail, parseEpisodes } from '../mapper.js';
import { encodeRef } from '../ref.js';

// Spider instance handles keyed by siteKey — one engine = one endpoint = module-level cache
const spiderHandles = new Map<string, string>();

// ── JAR bootstrap ─────────────────────────────────────────────────────────────

export async function ensureJar(jarUrl: string, md5?: string): Promise<void> {
  await host.jar.load({ url: jarUrl, md5 });
  // Init.init() bootstraps encrypted JARs — not present in all JARs, ignore error
  await host.jar.reflect({
    url: jarUrl,
    cls: 'com.github.catvod.spider.Init',
    method: 'init',
    args: [],
  }).catch(() => undefined);
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
  const handle = await host.jar.reflect({ url: jarUrl, cls, method: 'newInstance', args: [] });
  await host.jar.reflect({ url: jarUrl, cls, method: 'init', instance: handle, args: [ext] });
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
  const data = JSON.parse(raw);
  return data.list?.[0] ?? { vod_id: id };
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
