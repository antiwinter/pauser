/**
 * index.ts — Rollup IIFE entry point for the Emby provider.
 *
 * One engine = one server instance. Module-level `state` holds the single
 * configured instance; no instanceId map needed.
 */
import { getFieldsSpec, validateFields, makeInstanceState } from './provider.js';
import { loadBrowsePage, searchItems, loadDetail, resolvePlayback } from './instance.js';
import { onPlaybackReady, onProgressTick, onStop, setDeviceAuth } from './hooks.js';
import type { EmbyHooksState } from './hooks.js';
import type { EmbyInstanceState } from './instance.js';
import type {
  ServerFieldSpec,
  ValidationResult,
  BrowsePageResult,
  MediaListItem,
  MediaDetailModel,
  PlaybackSpec,
  CodecCapabilities,
} from '../src/types.js';

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
    capabilities: CodecCapabilities;
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

  async loadBrowsePage(args: {
    location: string | null;
    startIndex: number;
    limit: number;
  }): Promise<BrowsePageResult> {
    return loadBrowsePage(state!, args.location, args.startIndex, args.limit);
  },

  async searchItems(args: {
    scopeLocation: string;
    query: string;
  }): Promise<MediaListItem[]> {
    return searchItems(state!, args.scopeLocation, args.query);
  },

  async loadDetail(args: { itemRef: string }): Promise<MediaDetailModel> {
    return loadDetail(state!, args.itemRef);
  },

  async resolvePlayback(args: {
    itemRef: string;
    startMs: number;
  }): Promise<PlaybackSpec> {
    return resolvePlayback(state!, args.itemRef, args.startMs);
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
  }): Promise<void> {
    await onProgressTick(args.hooksState, args.positionMs, args.playbackRate);
  },

  async onStop(args: {
    hooksState: EmbyHooksState;
    positionMs: number;
  }): Promise<void> {
    await onStop(args.hooksState, args.positionMs);
  },
};
