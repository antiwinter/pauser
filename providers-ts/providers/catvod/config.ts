export interface CatVodConfig {
  spider?: string;
  sites:   Record<string, SiteEntry | LiveEntry>;
  hosts?:  string[];
  wallpaper?: string;
  logo?: string;
}

export interface SiteEntry {
  type: 'site';
  key:           string;
  name:          string;
  siteType:      number;    // 0/1/2 = CMS, 3 = JAR, 4/9/10 = drpy2
  api:           string;
  ext?:          string | Record<string, unknown>;
  searchable?:   0 | 1;
  quickSearch?:  0 | 1;
  timeout?:      number;
}

export interface LiveEntry {
  type: 'live';
  name:        string;
  url:         string;
  playerType:  number;
  ua?:         string;
  timeout?:    number;
}

interface RawConfig {
  spider?: string;
  sites:   Array<Omit<SiteEntry, 'type' | 'siteType'> & { type: number }>;
  lives?:  Array<Omit<LiveEntry, 'type'> & { type: 0 }>;
  hosts?:  string[];
  wallpaper?: string;
  logo?: string;
}

// Strip // line comments (JSONC — common in CatVod configs)
function parseJsonc(text: string): RawConfig {
  return JSON.parse(text.replace(/^\s*\/\/.*$/gm, ''));
}

async function hashKey(input: string, prefix: string): Promise<string> {
  const hash = await host.crypto.sha256({ input });
  return prefix + hash.slice(0, 5);
}

async function processConfig(raw: RawConfig): Promise<CatVodConfig> {
  const sites: Record<string, SiteEntry | LiveEntry> = {};

  for (const rawSite of raw.sites) {
    const hashedKey = await hashKey(rawSite.key, 's');
    sites[hashedKey] = {
      type: 'site',
      key: rawSite.key,
      name: rawSite.name,
      siteType: rawSite.type,
      api: rawSite.api,
      ext: rawSite.ext,
      searchable: rawSite.searchable,
      quickSearch: rawSite.quickSearch,
      timeout: rawSite.timeout,
    };
  }

  if (raw.lives) {
    for (const rawLive of raw.lives) {
      const hashedKey = await hashKey(rawLive.name, 'l');
      sites[hashedKey] = {
        type: 'live',
        name: rawLive.name,
        url: rawLive.url,
        playerType: rawLive.playerType,
        ua: rawLive.ua,
        timeout: rawLive.timeout,
      };
    }
  }

  return {
    spider: raw.spider,
    sites,
    hosts: raw.hosts,
    wallpaper: raw.wallpaper,
    logo: raw.logo,
  };
}

export async function fetchConfig(url: string): Promise<CatVodConfig> {
  const resp = await host.http.get({ url });
  const body = resp.body.trim();

  let raw: RawConfig;
  try {
    raw = parseJsonc(body);
  } catch (_) {
    // Config may be base64-encoded (embedded in JPEG/BMP)
    const b64 = body.match(/[A-Za-z0-9+/]{200,}={0,2}/);
    if (b64) {
      // atob returns Latin-1 bytes; decodeURIComponent(escape(...)) re-encodes them as UTF-8
      try {
        raw = parseJsonc(decodeURIComponent(escape(atob(b64[0]))));
      } catch (_) {
        throw new Error(`Cannot parse CatVod config from ${url}`);
      }
    } else {
      throw new Error(`Cannot parse CatVod config from ${url}`);
    }
  }

  return await processConfig(raw);
}

export function siteExt(site: SiteEntry): string {
  if (!site.ext) return '';
  return typeof site.ext === 'string' ? site.ext : JSON.stringify(site.ext);
}

export function parseSpiderField(spider?: string): { url: string; md5?: string } | null {
  if (!spider) return null;
  // Support: "url;md5;hash", "url;md5=hash", "url?md5=hash"
  let url = spider;
  let md5: string | undefined;
  const sepMatch = spider.match(/^(.*?)[;?]md5[=;](.+)$/i);
  if (sepMatch) {
    url = sepMatch[1];
    md5 = sepMatch[2];
  }
  return { url, md5 };
}
