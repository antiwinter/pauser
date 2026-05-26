import type { EntryList, EntryDetail } from '../../../utils/types.js';
import { vodItemToEntry, vodDetailToEntryDetail, type CatVodDetail } from '../mapper.js';

export async function cmsHome(api: string, siteKey: string): Promise<EntryList> {
  const resp = await host.http.get({ url: `${api}?ac=list` });
  const data = JSON.parse(resp.body);
  const cats: Array<{ type_id: string | number; type_name?: string }> = data.class ?? [];
  return {
    items: cats.map((c) => ({
      id:    JSON.stringify({ type: 'cat', key: siteKey, tid: String(c.type_id) }),
      title: c.type_name ?? String(c.type_id),
      type:  'Folder' as const,
      cover: null,
    })),
    totalCount: cats.length,
  };
}

export async function cmsCategory(
  api: string,
  siteKey: string,
  tid: string,
  pg: number,
): Promise<EntryList> {
  const resp = await host.http.get({ url: `${api}?ac=videolist&t=${tid}&pg=${pg}` });
  const data = JSON.parse(resp.body);
  return {
    items:      (data.list ?? []).map((item: unknown) => vodItemToEntry(item as never, siteKey)),
    totalCount: data.total ?? 0,
  };
}

export async function cmsDetail(api: string, id: string): Promise<CatVodDetail> {
  const resp = await host.http.get({ url: `${api}?ac=detail&ids=${id}` });
  const data = JSON.parse(resp.body);
  return data.list?.[0] ?? { vod_id: id, vod_name: id };
}

export async function cmsSearch(
  api: string,
  siteKey: string,
  keyword: string,
  pg: number,
): Promise<EntryList> {
  const resp = await host.http.get({
    url: `${api}?ac=videolist&wd=${encodeURIComponent(keyword)}&pg=${pg}`,
  });
  const data = JSON.parse(resp.body);
  return {
    items:      (data.list ?? []).map((item: unknown) => vodItemToEntry(item as never, siteKey)),
    totalCount: data.total ?? 0,
  };
}

export function buildDetail(raw: CatVodDetail): EntryDetail {
  return vodDetailToEntryDetail(raw);
}
