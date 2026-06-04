import type {
  CatVodSpider,
  CatVodHomeResult,
  CatVodCategoryResult,
  CatVodDetailResult,
  CatVodPlayResult,
  CatVodFilterExtend,
} from '../types.js';
import type { SiteEntry } from '../config.js';

/**
 * Creates a CMS spider (苹果CMS / MacCMS) — types 0/1/2
 * These are plain HTTP APIs with standardized endpoints
 */
function createCmsSpider(api: string): CatVodSpider {
  return {
    async home(_filter?: boolean): Promise<CatVodHomeResult> {
      const resp = await host.http.get({ url: `${api}?ac=list` });
      const data = JSON.parse(resp.body);
      return { class: data.class ?? [], filters: data.filters };
    },

    async category(tid: string, pg: number, _filter?: boolean, _extend?: CatVodFilterExtend): Promise<CatVodCategoryResult> {
      const resp = await host.http.get({ url: `${api}?ac=videolist&t=${tid}&pg=${pg}` });
      const data = JSON.parse(resp.body);
      return {
        list: data.list ?? [],
        total: data.total ?? 0,
        pagecount: data.pagecount,
        msg: data.msg,
      };
    },

    async detail(ids: string[]): Promise<CatVodDetailResult> {
      const resp = await host.http.get({ url: `${api}?ac=detail&ids=${ids.join(',')}` });
      const data = JSON.parse(resp.body);
      return { list: data.list ?? [] };
    },

    async play(_flag: string, epUrl: string, _vipFlags?: string[]): Promise<CatVodPlayResult> {
      // CMS sites return direct URLs — no additional resolution needed
      return { url: epUrl };
    },

    async search(query: string, pg: number, _quick?: boolean): Promise<CatVodCategoryResult> {
      const resp = await host.http.get({
        url: `${api}?ac=videolist&wd=${encodeURIComponent(query)}&pg=${pg}`,
      });
      const data = JSON.parse(resp.body);
      return {
        list: data.list ?? [],
        total: data.total ?? 0,
        msg: data.msg,
      };
    },
  };
}

export default {
  name: 'cms',
  type: [0, 1, 2],
  createSpider: (site: SiteEntry) => createCmsSpider(site.api),
};
