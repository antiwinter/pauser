export interface CatVodConfig {
  spider?: string;
  sites:   SiteEntry[];
  lives?:  LiveEntry[];
  hosts?:  string[];
}

export interface SiteEntry {
  key:           string;
  name:          string;
  type:          number;    // 0/1/2 = CMS, 3 = JAR, 4/9/10 = drpy2
  api:           string;
  ext?:          string | Record<string, unknown>;
  searchable?:   0 | 1;
  quickSearch?:  0 | 1;
  timeout?:      number;
}

export interface LiveEntry {
  name:        string;
  type:        0;
  url:         string;
  playerType:  number;
  ua?:         string;
  timeout?:    number;
}

// Strip // line comments (JSONC — common in CatVod configs)
function parseJsonc(text: string): CatVodConfig {
  return JSON.parse(text.replace(/^\s*\/\/.*$/gm, ''));
}

export async function fetchConfig(url: string): Promise<CatVodConfig> {
  const resp = await host.http.get({ url });
  const body = resp.body.trim();

  try { return parseJsonc(body); } catch (_) {}

  // Config may be base64-encoded (embedded in JPEG/BMP)
  const b64 = body.match(/[A-Za-z0-9+/]{200,}={0,2}/);
  if (b64) {
    // atob returns Latin-1 bytes; decodeURIComponent(escape(...)) re-encodes them as UTF-8
    try { return parseJsonc(decodeURIComponent(escape(atob(b64[0])))); } catch (_) {}
  }

  throw new Error(`Cannot parse CatVod config from ${url}`);
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
