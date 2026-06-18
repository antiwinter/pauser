import type {
  CatVodSpider,
  CatVodHomeResult,
  CatVodCategoryResult,
  CatVodDetailResult,
  CatVodPlayResult,
  IptvChannelListResult,
  M3UChannel,
} from './types.js';
import type { LiveEntry } from '../config.js';
import { normalizeHome, normalizePlay } from './normalize.js';

/**
 * Creates an IPTV/M3U spider for a live entry.
 * These are M3U channel lists that provide live TV channels.
 * Routed directly by spider/index.ts — not part of the site handler registry.
 */
export function createIptvSpider(live: LiveEntry): CatVodSpider {
  let channelCache: M3UChannel[] | null = null;

  async function fetchAndParse(): Promise<M3UChannel[]> {
    if (channelCache) return channelCache;
    const resp = await host.http.get({ url: live.url });
    channelCache = parseM3U(resp.body);
    return channelCache;
  }

  return {
    async home(): Promise<CatVodHomeResult> {
      return normalizeHome({ class: [] });
    },

    async category(): Promise<CatVodCategoryResult> {
      return { list: [], total: 0 };
    },

    async detail(): Promise<CatVodDetailResult> {
      return { list: [] };
    },

    async play(_flag: string, id: string): Promise<CatVodPlayResult> {
      const header = live.ua ? { 'User-Agent': live.ua } : undefined;
      return normalizePlay({ url: id, header });
    },

    async channels(): Promise<IptvChannelListResult> {
      const channels = await fetchAndParse();
      return { channels };
    },
  };
}

function parseM3U(content: string): M3UChannel[] {
  const lines = content.split('\n');
  const channels: M3UChannel[] = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith('#EXTINF:')) continue;
    const name = line.match(/,(.+)$/)?.[1]?.trim();
    const logo = line.match(/tvg-logo="([^"]+)"/)?.[1];
    const url  = lines[i + 1]?.trim();
    if (name && url && !url.startsWith('#')) {
      channels.push({ name, url, logo });
    }
  }
  return channels;
}
