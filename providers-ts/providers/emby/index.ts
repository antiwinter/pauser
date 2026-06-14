/**
 * index.ts — Rollup IIFE entry point for the Emby provider.
 *
 * One engine = one endpoint client. Module-level `state` holds the single
 * configured client; no clientId map needed.
 */
import { getFieldsSpec, makeClientState } from './provider.js';
import { listEntry, search, getPlaybackSpec, test, getEntries } from './client.js';
import { updateEntryState, setDeviceAuth } from './hooks.js';
import type { EmbyHooksState } from './hooks.js';
import type { EmbyClientState } from './client.js';
import type {
  ProviderFieldSpec,
  ValidationResult,
  EntryList,
  EntryInfo,
  PlaybackSpec,
  PlatformInfo,
  QueryOptions,
} from '../../utils/types.js';

let state: EmbyClientState | null = null;

(globalThis as unknown as Record<string, unknown>).opentuneProvider = {

  // ── Provider-level (called from a fresh temp engine) ──────────────────

  providesArt: true,

  async getFieldsSpec(): Promise<ProviderFieldSpec[]> {
    return getFieldsSpec();
  },

  async test(): Promise<ValidationResult> {
    return test(state!, state!.capabilities, state!.capabilities.deviceName);
  },

  // ── Client init (called once per engine, replaces createClient) ───

  async init(args: {
    credentials: Record<string, string>;
    deviceInfo: PlatformInfo;
  }): Promise<void> {
    setDeviceAuth({
      clientName: 'OpenTune',
      deviceName: args.deviceInfo.deviceName,
      deviceId: args.deviceInfo.deviceId,
      clientVersion: args.deviceInfo.clientVersion,
    });
    state = makeClientState(args.credentials, args.deviceInfo, args.deviceInfo.deviceName);
    const c = args.credentials;
    if (c['access_token'] && c['user_id']) {
      state.credentials = {
        baseUrl:     c['base_url'] ?? '',
        userId:      c['user_id'],
        accessToken: c['access_token'],
        serverId:    c['server_id'] ?? '',
      };
    }
  },

  // ── Client methods ──────────────────────────────────────────────────

  async listEntry(args: {
    location: string | null;
    startIndex: number;
    limit: number;
    options?: QueryOptions;
  }): Promise<EntryList> {
    return listEntry(state!, args.location, args.startIndex, args.limit, args.options);
  },

  async getEntries(args: {
    itemRefs: string[];
  }): Promise<EntryList> {
    return getEntries(state!, args.itemRefs);
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

  async updateEntryState(args: {
    itemRef: string;
    key: string;
    value: string | null;
    state?: EmbyHooksState;
  }): Promise<void> {
    const hookState = args.state;
    if (!hookState) return;
    await updateEntryState(hookState, args.key, args.value);
  },
};
