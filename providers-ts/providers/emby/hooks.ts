/**
 * hooks.ts — Emby playback progress reporting via updateEntryState.
 */
import { EmbyApi, setGlobalAuth } from './api.js';
import type { DeviceProfile } from './dto.js';

export { setGlobalAuth as setDeviceAuth };

export interface EmbyHooksState {
  itemId: string;
  playMethod: string;
  playSessionId: string | null;
  mediaSourceId: string | null;
  liveStreamId: string | null;
  baseUrl: string;
  userId: string;
  accessToken: string;
  deviceProfile: DeviceProfile;
}

type SessionMeta = {
  hasReportedPlaying: boolean;
  positionMs: number;
  playbackRate: number;
};

const sessionMeta = new Map<string, SessionMeta>();

function metaKey(state: EmbyHooksState): string {
  return state.playSessionId ?? state.itemId;
}

function getMeta(state: EmbyHooksState): SessionMeta {
  const key = metaKey(state);
  let meta = sessionMeta.get(key);
  if (!meta) {
    meta = { hasReportedPlaying: false, positionMs: 0, playbackRate: 1 };
    sessionMeta.set(key, meta);
  }
  return meta;
}

export async function updateEntryState(
  state: EmbyHooksState,
  key: string,
  value: string | null,
): Promise<void> {
  const meta = getMeta(state);
  switch (key) {
    case 'positionMs':
      meta.positionMs = Number(value ?? 0);
      return;
    case 'speed':
      meta.playbackRate = Number(value ?? 1);
      return;
    case 'playingState':
      if (value === 'PLAYING') {
        if (!meta.hasReportedPlaying) {
          await reportPlaying(state, meta.positionMs, meta.playbackRate);
          meta.hasReportedPlaying = true;
        } else {
          await reportProgress(state, meta.positionMs, meta.playbackRate, false);
        }
      } else if (value === 'PAUSED') {
        await reportProgress(state, meta.positionMs, meta.playbackRate, true);
      } else if (value === 'STOPPED') {
        await reportStopped(state, meta.positionMs);
        sessionMeta.delete(metaKey(state));
      }
      return;
    case 'favorite':
      // Remote favorite sync not implemented for Emby yet.
      return;
    default:
      return;
  }
}

async function reportPlaying(
  state: EmbyHooksState,
  positionMs: number,
  playbackRate: number,
): Promise<void> {
  const api = new EmbyApi(state.baseUrl, state.accessToken, state.userId);
  const ticks = positionMs * 10_000;
  await api.reportPlaying({
    ItemId:        state.itemId,
    MediaSourceId: state.mediaSourceId,
    PlaySessionId: state.playSessionId,
    LiveStreamId:  state.liveStreamId,
    PlayMethod:    state.playMethod,
    PositionTicks: ticks,
    PlaybackRate:  playbackRate,
  });
}

async function reportProgress(
  state: EmbyHooksState,
  positionMs: number,
  playbackRate: number,
  isPaused: boolean,
): Promise<void> {
  const api = new EmbyApi(state.baseUrl, state.accessToken, state.userId);
  const ticks = positionMs * 10_000;
  await api.reportProgress({
    ItemId:        state.itemId,
    MediaSourceId: state.mediaSourceId,
    PlaySessionId: state.playSessionId,
    LiveStreamId:  state.liveStreamId,
    PlayMethod:    state.playMethod,
    PositionTicks: ticks,
    PlaybackRate:  playbackRate,
    IsPaused:      isPaused,
  });
}

async function reportStopped(
  state: EmbyHooksState,
  positionMs: number,
): Promise<void> {
  const api = new EmbyApi(state.baseUrl, state.accessToken, state.userId);
  const ticks = positionMs * 10_000;
  await api.reportStopped({
    ItemId:        state.itemId,
    MediaSourceId: state.mediaSourceId,
    PlaySessionId: state.playSessionId,
    LiveStreamId:  state.liveStreamId,
    PositionTicks: ticks,
  });
}
