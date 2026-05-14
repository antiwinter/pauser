import {
  assert,
  assertType,
  assertNonEmptyString,
  assertObject,
  assertArray,
  assertUrl,
  checkUrlReachable,
  checkMediaWithFfprobe,
  parseJsonResult,
} from '../validators.js';
import { NAError } from '../reporter.js';

/**
 * Category: Playback
 * Covers getPlaybackSpec (structure, URL reachability, ffprobe validation)
 * and the playback hook lifecycle (onPlaybackReady, onProgressTick, onStop).
 *
 * @param {import('../reporter.js').Reporter} reporter
 * @param {import('../quickjs-runner.js').QuickJsProviderRunner} runner
 * @param {{ playableItem: object|null, hooks: boolean, ffprobe: boolean }} opts
 */
export async function runPlaybackChecks(reporter, runner, opts) {
  reporter.beginCategory('Playback', ['getPlaybackSpec', 'onPlaybackReady', 'onProgressTick', 'onStop']);

  const { playableItem, hooks, ffprobe } = opts;
  let spec = null;

  spec = await reporter.step('getPlaybackSpec returns a valid PlaybackSpec', async () => {
    if (!playableItem) throw new NAError('no playable item found in browsed catalog sample');
    const result = parseJsonResult(
      await runner.callMethod('getPlaybackSpec', { itemRef: playableItem.id, startMs: 0 }),
      'getPlaybackSpec',
    );
    validatePlaybackSpecShape(result);
    return result;
  });

  await reporter.step('playback URL is a non-empty string', async () => {
    if (!spec) throw new NAError('spec not loaded');
    assertNonEmptyString(spec.url, 'playback spec.url');
    return spec.url.slice(0, 80);
  });

  await reporter.step('playback URL is a valid http/https URL', async () => {
    if (!spec) throw new NAError('spec not loaded');
    assertUrl(spec.url, 'playback spec.url');
    return spec.url;
  });

  await reporter.step('playback URL is reachable (HEAD request)', async () => {
    if (!spec) throw new NAError('spec not loaded');
    await checkUrlReachable(spec.url, spec.headers ?? {});
    return 'ok';
  });

  await reporter.step('playback URL passes ffprobe media check', async () => {
    if (!spec) throw new NAError('spec not loaded');
    if (!ffprobe) throw new NAError('--no-ffprobe flag set');
    await checkMediaWithFfprobe(spec.url, spec.headers ?? {}, { expectVideo: false, expectAudio: false });
    return 'ok';
  });

  await reporter.step('playback spec optional fields have correct types', async () => {
    if (!spec) throw new NAError('spec not loaded');
    if (spec.mimeType != null) assertType(spec.mimeType, 'string', 'spec.mimeType');
    if (spec.durationMs != null) assertType(spec.durationMs, 'number', 'spec.durationMs');
    assertType(spec.title, 'string', 'spec.title');
    return `title="${spec.title}"${spec.durationMs != null ? ` duration=${spec.durationMs}ms` : ''}`;
  });

  await reporter.step('playback spec subtitleTracks are valid', async () => {
    if (!spec) throw new NAError('spec not loaded');
    assertArray(spec.subtitleTracks, 'spec.subtitleTracks');
    if (spec.subtitleTracks.length === 0) throw new NAError('subtitleTracks is empty — no tracks to validate');
    for (const [i, track] of spec.subtitleTracks.entries()) validateSubtitleTrack(track, `subtitleTracks[${i}]`);
    return `${spec.subtitleTracks.length} track(s)`;
  });

  await reporter.step('playback spec hooksState is an object', async () => {
    if (!spec) throw new NAError('spec not loaded');
    assertObject(spec.hooksState, 'spec.hooksState');
    return 'ok';
  });

  // ── Playback hooks lifecycle ───────────────────────────────────────────────

  await reporter.step('onPlaybackReady succeeds', async () => {
    if (!hooks) throw new NAError('pass --hooks to exercise the playback lifecycle callbacks');
    if (!spec) throw new NAError('spec not loaded');
    await runner.callMethod('onPlaybackReady', {
      hooksState: spec.hooksState,
      positionMs: 0,
      playbackRate: 1,
    });
    return 'ok';
  });

  await reporter.step('onProgressTick succeeds', async () => {
    if (!hooks) throw new NAError('pass --hooks to exercise the playback lifecycle callbacks');
    if (!spec) throw new NAError('spec not loaded');
    await runner.callMethod('onProgressTick', {
      hooksState: spec.hooksState,
      positionMs: 5_000,
      playbackRate: 1,
    });
    return 'ok';
  });

  await reporter.step('onStop succeeds', async () => {
    if (!hooks) throw new NAError('pass --hooks to exercise the playback lifecycle callbacks');
    if (!spec) throw new NAError('spec not loaded');
    await runner.callMethod('onStop', {
      hooksState: spec.hooksState,
      positionMs: 5_000,
    });
    return 'ok';
  });

  reporter.endCategory();
}

// ── Validators ────────────────────────────────────────────────────────────────

function validatePlaybackSpecShape(spec) {
  assertObject(spec, 'playback spec');
  assertObject(spec.headers, 'playback spec.headers');
  assertType(spec.title, 'string', 'playback spec.title');
  assertArray(spec.subtitleTracks, 'playback spec.subtitleTracks');
  assertObject(spec.hooksState, 'playback spec.hooksState');
}

function validateSubtitleTrack(track, path) {
  assertObject(track, path);
  assertNonEmptyString(track.trackId, `${path}.trackId`);
  assertNonEmptyString(track.label, `${path}.label`);
  assertType(track.isDefault, 'boolean', `${path}.isDefault`);
  assertType(track.isForced, 'boolean', `${path}.isForced`);
  if (track.language != null) assertType(track.language, 'string', `${path}.language`);
  if (track.externalRef != null) assertType(track.externalRef, 'string', `${path}.externalRef`);
}
