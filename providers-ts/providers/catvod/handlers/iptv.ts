import type { EntryList } from '../../../utils/types.js';
import type { LiveEntry } from '../config.js';

export async function fetchLiveChannels(lives: LiveEntry[]): Promise<EntryList> {
  const items: EntryList['items'] = [];
  for (const live of lives) {
    try {
      const resp = await host.http.get({ url: live.url });
      for (const ch of parseM3U(resp.body)) {
        items.push({
          id:    JSON.stringify({ type: 'live', name: ch.name, url: ch.url }),
          title: ch.name,
          type:  'Playable' as const,
          cover: ch.logo ?? null,
        });
      }
    } catch (_) {
      // skip unreachable live source
    }
  }
  return { items, totalCount: items.length };
}

interface M3UChannel { name: string; url: string; logo?: string }

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
