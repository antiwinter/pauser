/**
 * index.ts — Rollup IIFE entry point for the Emby provider.
 *
 * One engine = one endpoint client. Module-level `state` holds the single
 * configured client; no clientId map needed.
 */
import { makeClientState } from './provider.js';
import { listEntry, getPlaybackSources, test, getEntries } from './client.js';
import { updateEntryState, setDeviceAuth } from './hooks.js';
import type { EmbyHooksCtx } from './hooks.js';
import type { EmbyClientState } from './client.js';
import type {
  ValidationResult,
  EntryList,
  PlaybackSource,
  PlatformInfo,
  QueryOptions,
} from '../../utils/types.js';

let state: EmbyClientState | null = null;

export default {

  async test(): Promise<ValidationResult> {
    return test(state!, state!.capabilities, state!.capabilities.deviceName);
  },

  async init(args: {
    credentials: Record<string, string>;
    deviceInfo: PlatformInfo;
  }): Promise<void> {
    setDeviceAuth({
      clientName: 'Insomnia',
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

  async getPlaybackSources(args: {
    itemRef: string;
    startMs: number;
  }): Promise<{ sources: PlaybackSource[]; ctx: EmbyHooksCtx }> {
    return getPlaybackSources(state!, args.itemRef, args.startMs ?? 0);
  },

  async updateEntryState(args: {
    itemRef: string;
    key: string;
    value: string | null;
    ctx?: EmbyHooksCtx;
  }): Promise<void> {
    const ctx = args.ctx;
    if (!ctx) return;
    await updateEntryState(ctx, args.key, args.value);
  },
};
