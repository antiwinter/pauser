/**
 * hooks.ts — Emby playback progress reporting via updateEntryState.
 */
import { EmbyApi, setGlobalAuth } from './api.js';
import type { DeviceProfile } from './dto.js';

export { setGlobalAuth as setDeviceAuth };

export interface EmbyHooksCtx {
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

function metaKey(ctx: EmbyHooksCtx): string {
  return ctx.playSessionId ?? ctx.itemId;
}

function getMeta(ctx: EmbyHooksCtx): SessionMeta {
  const key = metaKey(ctx);
  let meta = sessionMeta.get(key);
  if (!meta) {
    meta = { hasReportedPlaying: false, positionMs: 0, playbackRate: 1 };
    sessionMeta.set(key, meta);
  }
  return meta;
}

export async function updateEntryState(
  ctx: EmbyHooksCtx,
  key: string,
  value: string | null,
): Promise<void> {
  const meta = getMeta(ctx);
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
          await reportPlaying(ctx, meta.positionMs, meta.playbackRate);
          meta.hasReportedPlaying = true;
        } else {
          await reportProgress(ctx, meta.positionMs, meta.playbackRate, false);
        }
      } else if (value === 'PAUSED') {
        await reportProgress(ctx, meta.positionMs, meta.playbackRate, true);
      } else if (value === 'STOPPED') {
        await reportStopped(ctx, meta.positionMs);
        sessionMeta.delete(metaKey(ctx));
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
  ctx: EmbyHooksCtx,
  positionMs: number,
  playbackRate: number,
): Promise<void> {
  const api = new EmbyApi(ctx.baseUrl, ctx.accessToken, ctx.userId);
  const ticks = positionMs * 10_000;
  await api.reportPlaying({
    ItemId:        ctx.itemId,
    MediaSourceId: ctx.mediaSourceId,
    PlaySessionId: ctx.playSessionId,
    LiveStreamId:  ctx.liveStreamId,
    PlayMethod:    ctx.playMethod,
    PositionTicks: ticks,
    PlaybackRate:  playbackRate,
  });
}

async function reportProgress(
  ctx: EmbyHooksCtx,
  positionMs: number,
  playbackRate: number,
  isPaused: boolean,
): Promise<void> {
  const api = new EmbyApi(ctx.baseUrl, ctx.accessToken, ctx.userId);
  const ticks = positionMs * 10_000;
  await api.reportProgress({
    ItemId:        ctx.itemId,
    MediaSourceId: ctx.mediaSourceId,
    PlaySessionId: ctx.playSessionId,
    LiveStreamId:  ctx.liveStreamId,
    PlayMethod:    ctx.playMethod,
    PositionTicks: ticks,
    PlaybackRate:  playbackRate,
    IsPaused:      isPaused,
  });
}

async function reportStopped(
  ctx: EmbyHooksCtx,
  positionMs: number,
): Promise<void> {
  const api = new EmbyApi(ctx.baseUrl, ctx.accessToken, ctx.userId);
  const ticks = positionMs * 10_000;
  await api.reportStopped({
    ItemId:        ctx.itemId,
    MediaSourceId: ctx.mediaSourceId,
    PlaySessionId: ctx.playSessionId,
    LiveStreamId:  ctx.liveStreamId,
    PositionTicks: ticks,
  });
}
