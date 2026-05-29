/**
 * index.ts — Rollup IIFE entry point for the Telegram provider.
 * Wires globalThis.opentuneProvider for the QuickJS runtime.
 */
import { getFieldsSpec, validateFields } from './provider.js';
import {
  listEntry, search, getDetail, getPlaybackSpec, getSprite,
  onPlaybackReady, onProgressTick, onStop,
  getEntries, getTaggedEntries, tagEntry,
} from './instance.js';
import type {
  ProviderFieldSpec, ValidationResult, EntryList, EntryInfo,
  EntryDetail, PlaybackSpec, PlatformCapabilities, HooksState,
} from '../../utils/types.js';

(globalThis as unknown as Record<string, unknown>).opentuneProvider = {

  // ── Provider-level (called from a fresh temp engine) ──────────────────

  providesArt: false,

  async getFieldsSpec(): Promise<ProviderFieldSpec[]> {
    return getFieldsSpec();
  },

  async validateFields(args: { values: Record<string, string> }): Promise<ValidationResult> {
    return validateFields(args);
  },

  // ── Instance init (called once per engine) ────────────────────────────

  async init(args: {
    credentials: Record<string, string>;
    capabilities: PlatformCapabilities;
  }): Promise<void> {
    // Telegram initializes via QR auth; credentials populated after
    // QR confirmation. No additional setup needed here.
  },

  // ── Instance methods ──────────────────────────────────────────────────

  async listEntry(args: {
    location: string | null;
    startIndex: number;
    limit: number;
  }): Promise<EntryList> {
    return listEntry(args);
  },

  async search(args: {
    scopeLocation: string;
    query: string;
  }): Promise<EntryInfo[]> {
    return search(args);
  },

  async getDetail(args: { itemRef: string }): Promise<EntryDetail> {
    return getDetail(args);
  },

  async getPlaybackSpec(args: {
    itemRef: string;
    startMs: number;
  }): Promise<PlaybackSpec> {
    return getPlaybackSpec(args);
  },

  async getSprite(args: {
    itemRef: string;
    ts: number;
  }): Promise<string | null> {
    return getSprite(args);
  },

  // ── Playback hooks ────────────────────────────────────────────────────

  async onPlaybackReady(args: {
    hooksState: HooksState;
    positionMs: number;
    playbackRate: number;
  }): Promise<void> {
    await onPlaybackReady(args);
  },

  async onProgressTick(args: {
    hooksState: HooksState;
    positionMs: number;
    playbackRate: number;
    isPaused: boolean;
  }): Promise<void> {
    await onProgressTick(args);
  },

  async onStop(args: {
    hooksState: HooksState;
    positionMs: number;
  }): Promise<void> {
    await onStop(args);
  },

  // ── Optional endpoint methods ─────────────────────────────────────────

  async getEntries(args: { itemRefs: string[] }): Promise<EntryList> {
    return getEntries(args);
  },

  async getTaggedEntries(args: {
    tag: string;
    scopeLocation: string | null;
    startIndex: number;
    limit: number;
  }): Promise<EntryList> {
    return getTaggedEntries(args);
  },

  async tagEntry(args: { itemRef: string; tag: string; value: boolean }): Promise<void> {
    return tagEntry(args);
  },
};
