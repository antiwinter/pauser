/**
 * hooks.ts — Emby playback progress reporting hooks.
 * Mirrors EmbyPlaybackHooks.kt.
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

export async function onPlaybackReady(
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

export async function onProgressTick(
  state: EmbyHooksState,
  positionMs: number,
  playbackRate: number,
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
  });
}

export async function onStop(
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
