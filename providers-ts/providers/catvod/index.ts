import { getFieldsSpec } from './provider.js';
import { fetchConfig, parseSpiderField } from './config.js';
import { test, listEntry, search, getPlaybackSpec } from './client.js';
import { resetSpiders as resetJarSpiders } from './spider/jar.js';
import { resetSpiders as resetDrpySpiders } from './spider/drpy.js';
import type { CatVodClientState } from './client.js';
import type {
  ProviderFieldSpec,
  ValidationResult,
  EntryList,
  EntryInfo,
  PlaybackSpec,
  QueryOptions,
} from '../../utils/types.js';

/** Opaque capability flags provided by the host at init time */
type PlatformCapabilities = Record<string, unknown>;

let state: CatVodClientState | null = null;

(globalThis as unknown as Record<string, unknown>).opentuneProvider = {

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
    state = { rawCredentials: args.credentials, config };
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

  async getPlaybackSpec(args: {
    itemRef: string;
    startMs: number;
  }): Promise<PlaybackSpec> {
    return getPlaybackSpec(state!, args.itemRef, args.startMs);
  },

  async updateEntryState(_args: {
    itemRef: string;
    key: string;
    value: string | null;
    state?: Record<string, unknown>;
  }): Promise<void> {},

  async resetSpiders(): Promise<void> {
    const jar = state ? parseSpiderField(state.config?.spider) : undefined;
    await resetJarSpiders(jar?.url, jar?.md5);
    resetDrpySpiders();
  },
};
