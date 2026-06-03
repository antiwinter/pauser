import type {
  CatVodSpider,
  CatVodHomeResult,
  CatVodCategoryResult,
  CatVodDetail,
  CatVodPlayResult,
} from '../types.js';

/**
 * Creates a CMS spider (苹果CMS / MacCMS) — types 0/1/2
 * These are plain HTTP APIs with standardized endpoints
 */
function createCmsSpider(api: string): CatVodSpider {
  return {
    async home(): Promise<CatVodHomeResult> {
      const resp = await host.http.get({ url: `${api}?ac=list` });
      const data = JSON.parse(resp.body);
      return { class: data.class ?? [] };
    },

    async category(tid: string, pg: number): Promise<CatVodCategoryResult> {
      const resp = await host.http.get({ url: `${api}?ac=videolist&t=${tid}&pg=${pg}` });
      const data = JSON.parse(resp.body);
      return {
        list: data.list ?? [],
        total: data.total ?? 0,
      };
    },

    async detail(id: string): Promise<CatVodDetail> {
      const resp = await host.http.get({ url: `${api}?ac=detail&ids=${id}` });
      const data = JSON.parse(resp.body);
      return data.list?.[0] ?? { vod_id: id, vod_name: id };
    },

    async play(_flag: string, epUrl: string): Promise<CatVodPlayResult> {
      // CMS sites return direct URLs — no additional resolution needed
      return { url: epUrl };
    },

    async search(query: string, pg: number): Promise<CatVodCategoryResult> {
      const resp = await host.http.get({
        url: `${api}?ac=videolist&wd=${encodeURIComponent(query)}&pg=${pg}`,
      });
      const data = JSON.parse(resp.body);
      return {
        list: data.list ?? [],
        total: data.total ?? 0,
      };
    },
  };
}

export default {
  name: 'cms',
  type: [0, 1, 2],
  createSpider: (api: string) => createCmsSpider(api),
};
