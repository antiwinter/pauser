import { getFieldsSpec } from './provider.js';
import { fetchConfig, parseSpiderField } from './config.js';
import { test, listEntry, search, getEntries, getPlaybackSources } from './client.js';
import { resetSpiders as resetJarSpiders } from './spider/jar.js';
import { resetSpiders as resetDrpySpiders } from './spider/drpy.js';
import { initSpiders, getConfig } from './spider/index.js';
import type { CatVodClientState } from './client.js';
import type {
  ProviderFieldSpec,
  ValidationResult,
  EntryList,
  EntryInfo,
  PlaybackSource,
  QueryOptions,
} from '../../utils/types.js';

/** Opaque capability flags provided by the host at init time */
type PlatformCapabilities = Record<string, unknown>;

let state: CatVodClientState | null = null;

(globalThis as unknown as Record<string, unknown>).insomniaProvider = {

  providesArt: true,

  async getFieldsSpec(): Promise<ProviderFieldSpec[]> {
    return getFieldsSpec();
  },

  async test(): Promise<ValidationResult> {
    return test(state!);
  },

  async init(args: {
    credentials: Record<string, string>;
    capabilities: PlatformCapabilities;
  }): Promise<void> {
    const config = await fetchConfig(args.credentials['config_url'] ?? '');
    // Apply DNS host remaps (a=b) to this endpoint's HTTP client. Catvod-specific parsing
    // here; the host just stores from→to on the current endpoint's Dns.
    for (const entry of config.hosts ?? []) {
      const eq = entry.indexOf('=');
      if (eq <= 0) continue;
      const from = entry.slice(0, eq).trim();
      const to = entry.slice(eq + 1).trim();
      if (from && to) await host.dns.remap({ from, to });
    }
    await initSpiders(config);
    state = { rawCredentials: args.credentials };
  },

  async listEntry(args: {
    location: string | null;
    startIndex: number;
    limit: number;
    options?: QueryOptions;
  }): Promise<EntryList> {
    return listEntry(state!, args.location, args.startIndex, args.limit);
  },

  async search(args: {
    scopeLocation: string;
    query: string;
  }): Promise<EntryInfo[]> {
    return search(state!, args.scopeLocation, args.query);
  },

  async getEntries(args: {
    itemRefs: string[];
  }): Promise<EntryInfo[]> {
    return getEntries(state!, args.itemRefs);
  },

  async getPlaybackSources(args: {
    itemRef: string;
  }): Promise<PlaybackSource[]> {
    return getPlaybackSources(state!, args.itemRef);
  },

  async updateEntryState(_args: {
    itemRef: string;
    key: string;
    value: string | null;
    ctx?: Record<string, unknown>;
  }): Promise<void> {},

  async resetSpiders(): Promise<void> {
    const config = getConfig();
    const jar = config.spider ? parseSpiderField(config.spider) : undefined;
    await resetJarSpiders(jar?.url, jar?.md5);
    resetDrpySpiders();
  },
};
