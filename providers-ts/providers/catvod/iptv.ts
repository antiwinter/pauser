import type { M3UChannel, IptvChannelListResult } from './types.js';
import type { LiveEntry } from './config.js';

/**
 * IPTV M3U helper — fetches and parses M3U channel lists
 * Not a spider/handler, just a utility for live TV channels
 */
export async function fetchLiveChannels(live: LiveEntry): Promise<IptvChannelListResult> {
  const resp = await host.http.get({ url: live.url });
  const channels = parseM3U(resp.body);
  return { channels };
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
