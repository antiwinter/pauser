/**
 * hooks.ts — Emby playback progress reporting via updateEntryState.
 */
import { EmbyApi, setGlobalAuth } from './api.js';
import type { DeviceProfile } from './dto.js';

export { setGlobalAuth as setDeviceAuth };

export interface EmbyMediaSourceCtx {
  mediaSourceId: string | null;
  liveStreamId: string | null;
  playMethod: string;
  runTimeTicks: number | null;
}

export interface EmbyHooksCtx {
  itemId: string;
  playSessionId: string | null;
  /** One entry per PlaybackSource, same order as the returned sources array. */
  mediaSources: EmbyMediaSourceCtx[];
  baseUrl: string;
  userId: string;
  accessToken: string;
  deviceProfile: DeviceProfile;
}

type SessionMeta = {
  hasReportedPlaying: boolean;
  positionMs: number;
  playbackRate: number;
  sourceIndex: number;
};

const sessionMeta = new Map<string, SessionMeta>();

function metaKey(ctx: EmbyHooksCtx): string {
  return ctx.playSessionId ?? ctx.itemId;
}

function getMeta(ctx: EmbyHooksCtx): SessionMeta {
  const key = metaKey(ctx);
  let meta = sessionMeta.get(key);
  if (!meta) {
    meta = { hasReportedPlaying: false, positionMs: 0, playbackRate: 1, sourceIndex: 0 };
    sessionMeta.set(key, meta);
  }
  return meta;
}

function mediaSourceFor(ctx: EmbyHooksCtx, meta: SessionMeta): EmbyMediaSourceCtx {
  return ctx.mediaSources[meta.sourceIndex] ?? { mediaSourceId: null, liveStreamId: null, playMethod: 'DirectPlay', runTimeTicks: null };
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
    case 'sourceIndex':
      meta.sourceIndex = Number(value ?? 0);
      return;
    case 'playingState':
      if (value === 'PLAYING') {
        if (!meta.hasReportedPlaying) {
          await reportPlaying(ctx, meta);
          meta.hasReportedPlaying = true;
        } else {
          await reportProgress(ctx, meta, false);
        }
      } else if (value === 'PAUSED') {
        await reportProgress(ctx, meta, true);
      } else if (value === 'STOPPED') {
        await reportStopped(ctx, meta);
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

async function reportPlaying(ctx: EmbyHooksCtx, meta: SessionMeta): Promise<void> {
  const api = new EmbyApi(ctx.baseUrl, ctx.accessToken, ctx.userId);
  const ms = mediaSourceFor(ctx, meta);
  const ticks = meta.positionMs * 10_000;
  await api.reportPlaying({
    ItemId:        ctx.itemId,
    MediaSourceId: ms.mediaSourceId,
    PlaySessionId: ctx.playSessionId,
    LiveStreamId:  ms.liveStreamId,
    PlayMethod:    ms.playMethod,
    PositionTicks: ticks,
    PlaybackRate:  meta.playbackRate,
    CanSeek:       true,
    IsPaused:      false,
    IsMuted:       false,
    NowPlayingQueue: [{ Id: ctx.itemId, PlaylistItemId: 'playlistItem0' }],
    PlaylistIndex: 0,
    PlaylistLength: 1,
  });
}

async function reportProgress(
  ctx: EmbyHooksCtx,
  meta: SessionMeta,
  isPaused: boolean,
): Promise<void> {
  const api = new EmbyApi(ctx.baseUrl, ctx.accessToken, ctx.userId);
  const ms = mediaSourceFor(ctx, meta);
  const ticks = meta.positionMs * 10_000;
  await api.reportProgress({
    ItemId:        ctx.itemId,
    MediaSourceId: ms.mediaSourceId,
    PlaySessionId: ctx.playSessionId,
    LiveStreamId:  ms.liveStreamId,
    PlayMethod:    ms.playMethod,
    PositionTicks: ticks,
    PlaybackRate:  meta.playbackRate,
    IsPaused:      isPaused,
    CanSeek:       true,
    IsMuted:       false,
    RunTimeTicks:  ms.runTimeTicks,
    PlaylistIndex: 0,
    PlaylistLength: 1,
    RepeatMode:    'RepeatNone',
    EventName:     'TimeUpdate',
  });
}

async function reportStopped(ctx: EmbyHooksCtx, meta: SessionMeta): Promise<void> {
  const api = new EmbyApi(ctx.baseUrl, ctx.accessToken, ctx.userId);
  const ms = mediaSourceFor(ctx, meta);
  const ticks = meta.positionMs * 10_000;
  await api.reportStopped({
    ItemId:        ctx.itemId,
    MediaSourceId: ms.mediaSourceId,
    PlaySessionId: ctx.playSessionId,
    LiveStreamId:  ms.liveStreamId,
    PlayMethod:    ms.playMethod,
    PositionTicks: ticks,
    PlaybackRate:  meta.playbackRate,
    CanSeek:       true,
    IsPaused:      false,
    IsMuted:       false,
    PlaylistIndex: 0,
    PlaylistLength: 1,
    RepeatMode:    'RepeatNone',
  });
}
