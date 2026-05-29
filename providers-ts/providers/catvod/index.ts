import { getFieldsSpec, validateFields } from './provider.js';
import { fetchConfig, parseSpiderField } from './config.js';
import { listEntry, search, getDetail, getPlaybackSpec } from './instance.js';
import { resetSpiders } from './handlers/jar.js';
import type { CatVodState } from './instance.js';
import type {
  ProviderFieldSpec,
  ValidationResult,
  EntryList,
  EntryInfo,
  EntryDetail,
  PlaybackSpec,
  PlatformCapabilities,
} from '../../utils/types.js';

let state: CatVodState | null = null;

(globalThis as unknown as Record<string, unknown>).opentuneProvider = {

  providesArt: true,

  async getFieldsSpec(): Promise<ProviderFieldSpec[]> {
    return getFieldsSpec();
  },

  async validateFields(args: { values: Record<string, string> }): Promise<ValidationResult> {
    return validateFields(args.values);
  },

  async init(args: {
    credentials: Record<string, string>;
    capabilities: PlatformCapabilities;
  }): Promise<void> {
    const config = await fetchConfig(args.credentials['config_url'] ?? '');
    state = { config };
  },

  async listEntry(args: {
    location: string | null;
    startIndex: number;
    limit: number;
  }): Promise<EntryList> {
    return listEntry(state!, args.location, args.startIndex, args.limit);
  },

  async search(args: {
    scopeLocation: string;
    query: string;
  }): Promise<EntryInfo[]> {
    return search(state!, args.scopeLocation, args.query);
  },

  async getDetail(args: { itemRef: string }): Promise<EntryDetail> {
    return getDetail(state!, args.itemRef);
  },

  async getPlaybackSpec(args: {
    itemRef: string;
    startMs: number;
  }): Promise<PlaybackSpec> {
    return getPlaybackSpec(state!, args.itemRef, args.startMs);
  },

  async onPlaybackReady(): Promise<void> {},
  async onProgressTick(): Promise<void> {},
  async onStop():         Promise<void> {},

  async resetSpiders(): Promise<void> {
    const jar = state ? parseSpiderField(state.config.spider) : undefined;
    await resetSpiders(jar?.url, jar?.md5);
  },
};
