/**
 * bridge.ts — Rollup IIFE entry point.
 * Sets globalThis.opentuneProvider with all provider bridge methods.
 * Mirrors the expected OpenTuneProviderBridge TypeScript interface.
 */
import { getFieldsSpec, validateFields, bootstrap, makeInstanceState } from './provider.js';
import { loadBrowsePage, searchItems, loadDetail, resolvePlayback } from './instance.js';
import { onPlaybackReady, onProgressTick, onStop } from './hooks.js';
import type { EmbyHooksState } from './hooks.js';
import type { EmbyInstanceState } from './instance.js';

interface InstanceEntry {
  state: EmbyInstanceState;
}

const instances = new Map<number, InstanceEntry>();
let nextId = 1;

function requireInstance(id: number): InstanceEntry {
  const inst = instances.get(id);
  if (!inst) throw new Error(`Unknown instance ${id}`);
  return inst;
}

(globalThis as unknown as Record<string, unknown>).opentuneProvider = {
  /** Called once after bundle loads with device codec capabilities. */
  async bootstrap(args: {
    capabilities: import('../src/types.js').CodecCapabilities;
    deviceName: string;
    deviceId: string;
    clientVersion: string;
  }): Promise<void> {
    bootstrap(args.capabilities, args.deviceName, args.deviceId, args.clientVersion);
  },

  async getFieldsSpec(): Promise<import('../src/types.js').ServerFieldSpec[]> {
    return getFieldsSpec();
  },

  async validateFields(args: { values: Record<string, string> }): Promise<import('../src/types.js').ValidationResult> {
    return validateFields(args.values);
  },

  async createInstance(args: { values: Record<string, string> }): Promise<number> {
    const state = makeInstanceState(args.values);
    const id = nextId++;
    instances.set(id, { state });
    return id;
  },

  async destroyInstance(args: { instanceId: number }): Promise<void> {
    instances.delete(args.instanceId);
  },

  async loadBrowsePage(args: {
    instanceId: number;
    location: string | null;
    startIndex: number;
    limit: number;
  }): Promise<import('../src/types.js').BrowsePageResult> {
    const { state } = requireInstance(args.instanceId);
    return loadBrowsePage(state, args.location, args.startIndex, args.limit);
  },

  async searchItems(args: {
    instanceId: number;
    scopeLocation: string;
    query: string;
  }): Promise<import('../src/types.js').MediaListItem[]> {
    const { state } = requireInstance(args.instanceId);
    return searchItems(state, args.scopeLocation, args.query);
  },

  async loadDetail(args: {
    instanceId: number;
    itemRef: string;
  }): Promise<import('../src/types.js').MediaDetailModel> {
    const { state } = requireInstance(args.instanceId);
    return loadDetail(state, args.itemRef);
  },

  async resolvePlayback(args: {
    instanceId: number;
    itemRef: string;
    startMs: number;
  }): Promise<import('../src/types.js').PlaybackSpec> {
    const { state } = requireInstance(args.instanceId);
    return resolvePlayback(state, args.itemRef, args.startMs);
  },

  async onPlaybackReady(args: {
    instanceId: number;
    hooksState: EmbyHooksState;
    positionMs: number;
    playbackRate: number;
  }): Promise<void> {
    requireInstance(args.instanceId);
    await onPlaybackReady(args.hooksState, args.positionMs, args.playbackRate);
  },

  async onProgressTick(args: {
    instanceId: number;
    hooksState: EmbyHooksState;
    positionMs: number;
    playbackRate: number;
  }): Promise<void> {
    requireInstance(args.instanceId);
    await onProgressTick(args.hooksState, args.positionMs, args.playbackRate);
  },

  async onStop(args: {
    instanceId: number;
    hooksState: EmbyHooksState;
    positionMs: number;
  }): Promise<void> {
    requireInstance(args.instanceId);
    await onStop(args.hooksState, args.positionMs);
  },
};
