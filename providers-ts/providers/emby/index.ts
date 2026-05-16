/**
 * index.ts — Rollup IIFE entry point for the Emby provider.
 *
 * One engine = one server instance. Module-level `state` holds the single
 * configured instance; no instanceId map needed.
 */
import { getFieldsSpec, validateFields, makeInstanceState } from './provider.js';
import { listEntry, search, getDetail, getPlaybackSpec } from './instance.js';
import { onPlaybackReady, onProgressTick, onStop, setDeviceAuth } from './hooks.js';
import type { EmbyHooksState } from './hooks.js';
import type { EmbyInstanceState } from './instance.js';
import type {
  ServerFieldSpec,
  ValidationResult,
  EntryList,
  EntryInfo,
  EntryDetail,
  PlaybackSpec,
  PlatformCapabilities,
} from '../../utils/types.js';

let state: EmbyInstanceState | null = null;

(globalThis as unknown as Record<string, unknown>).opentuneProvider = {

  // ── Provider-level (called from a fresh temp engine) ──────────────────

  providesCover: true,

  async getFieldsSpec(): Promise<ServerFieldSpec[]> {
    return getFieldsSpec();
  },

  async validateFields(args: { values: Record<string, string> }): Promise<ValidationResult> {
    return validateFields(args.values);
  },

  // ── Instance init (called once per engine, replaces createInstance) ───

  async init(args: {
    credentials: Record<string, string>;
    capabilities: PlatformCapabilities;
  }): Promise<void> {
    const info = await host.platform.getPlatformInfo();
    setDeviceAuth({
      clientName: 'OpenTune',
      deviceName: info.deviceName,
      deviceId: info.deviceId,
      clientVersion: info.clientVersion,
    });
    state = makeInstanceState(args.credentials, args.capabilities, info.deviceName);
  },

  // ── Instance methods ──────────────────────────────────────────────────

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

  // ── Playback hooks ────────────────────────────────────────────────────

  async onPlaybackReady(args: {
    hooksState: EmbyHooksState;
    positionMs: number;
    playbackRate: number;
  }): Promise<void> {
    await onPlaybackReady(args.hooksState, args.positionMs, args.playbackRate);
  },

  async onProgressTick(args: {
    hooksState: EmbyHooksState;
    positionMs: number;
    playbackRate: number;
    isPaused: boolean;
  }): Promise<void> {
    await onProgressTick(args.hooksState, args.positionMs, args.playbackRate, args.isPaused);
  },

  async onStop(args: {
    hooksState: EmbyHooksState;
    positionMs: number;
  }): Promise<void> {
    await onStop(args.hooksState, args.positionMs);
  },
};
