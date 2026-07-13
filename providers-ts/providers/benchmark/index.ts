import type { ValidationResult, EntryList, PlaybackSource, QueryOptions } from '../../utils/types.js';
import { runBench } from './bench.js';

export default {

  async test(): Promise<ValidationResult> {
    return { success: true, fields: {} };
  },

  async getQr(): Promise<{ token: string; qrData: string }> {
    const fields = runBench();
    // Encode results as JSON in the token — pollQr in Kotlin decodes it directly
    // without needing a second JS engine invocation.
    return { token: JSON.stringify(fields), qrData: 'Benchmark complete' };
  },

  async pollQr(_args: { token: string }): Promise<{ status: string; fields?: Record<string, string> }> {
    // Not called — Kotlin short-circuits when token is a JSON object.
    return { status: 'scanning' };
  },

  async init(_args: { credentials: Record<string, string>; capabilities: Record<string, unknown> }): Promise<void> {},

  async listEntry(_args: { location: string | null; startIndex: number; limit: number; options?: QueryOptions }): Promise<EntryList> {
    return { items: [], totalCount: 0 };
  },

  async search(_args: { scopeLocation: string; query: string }): Promise<[]> {
    return [];
  },

  async getPlaybackSources(_args: { itemRef: string; startMs: number }): Promise<PlaybackSource[]> {
    throw new Error('not supported');
  },

  async updateEntryState(_args: {
    itemRef: string;
    key: string;
    value: string | null;
    state?: Record<string, unknown>;
  }): Promise<void> {},
};
