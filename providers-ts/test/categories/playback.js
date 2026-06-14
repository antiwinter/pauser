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
 * and updateEntryState lifecycle.
 *
 * @param {import('../reporter.js').Reporter} reporter
 * @param {import('../quickjs-runner.js').QuickJsProviderRunner} runner
 * @param {{ playableItem: object|null, hooks: boolean, ffprobe: boolean }} opts
 */
export async function runPlaybackChecks(reporter, runner, opts) {
  reporter.beginCategory('Playback', ['getPlaybackSpec', 'updateEntryState']);

  const { playableItem, hooks, ffprobe } = opts;
  let spec = null;

  spec = await reporter.step('getPlaybackSpec returns a valid PlaybackSpec', async () => {
    if (!playableItem) throw new NAError('no playable item found in browsed catalog sample');
    let result;
    try {
      result = parseJsonResult(
        await runner.callMethod('getPlaybackSpec', { itemRef: playableItem.ref, startMs: 0 }),
        'getPlaybackSpec',
      );
    } catch (e) {
      throw new NAError(`getPlaybackSpec threw: ${e.message}`);
    }
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
    if (spec.title != null) assertType(spec.title, 'string', 'spec.title');
    assertType(spec.progressIntervalMs, 'number', 'spec.progressIntervalMs');
    return `progressIntervalMs=${spec.progressIntervalMs}`;
  });

  await reporter.step('playback spec subtitleTracks are valid', async () => {
    if (!spec) throw new NAError('spec not loaded');
    assertArray(spec.subtitleTracks, 'spec.subtitleTracks');
    if (spec.subtitleTracks.length === 0) throw new NAError('subtitleTracks is empty — no tracks to validate');
    for (const [i, track] of spec.subtitleTracks.entries()) validateSubtitleTrack(track, `subtitleTracks[${i}]`);
    return `${spec.subtitleTracks.length} track(s)`;
  });

  await reporter.step('playback spec state is an object', async () => {
    if (!spec) throw new NAError('spec not loaded');
    assertObject(spec.state, 'spec.state');
    return 'ok';
  });

  await reporter.step('updateEntryState PLAYING succeeds', async () => {
    if (!hooks) throw new NAError('pass --hooks to exercise updateEntryState lifecycle');
    if (!spec) throw new NAError('spec not loaded');
    await runner.callMethod('updateEntryState', {
      itemRef: playableItem.ref,
      key: 'positionMs',
      value: '0',
      state: spec.state,
    });
    await runner.callMethod('updateEntryState', {
      itemRef: playableItem.ref,
      key: 'playingState',
      value: 'PLAYING',
      state: spec.state,
    });
    return 'ok';
  });

  await reporter.step('updateEntryState PAUSED succeeds', async () => {
    if (!hooks) throw new NAError('pass --hooks to exercise updateEntryState lifecycle');
    if (!spec) throw new NAError('spec not loaded');
    await runner.callMethod('updateEntryState', {
      itemRef: playableItem.ref,
      key: 'positionMs',
      value: '5000',
      state: spec.state,
    });
    await runner.callMethod('updateEntryState', {
      itemRef: playableItem.ref,
      key: 'playingState',
      value: 'PAUSED',
      state: spec.state,
    });
    return 'ok';
  });

  await reporter.step('updateEntryState STOPPED succeeds', async () => {
    if (!hooks) throw new NAError('pass --hooks to exercise updateEntryState lifecycle');
    if (!spec) throw new NAError('spec not loaded');
    await runner.callMethod('updateEntryState', {
      itemRef: playableItem.ref,
      key: 'playingState',
      value: 'STOPPED',
      state: spec.state,
    });
    return 'ok';
  });

  reporter.endCategory();
}

function validatePlaybackSpecShape(spec) {
  assertObject(spec, 'playback spec');
  assertObject(spec.headers, 'playback spec.headers');
  assertArray(spec.subtitleTracks, 'playback spec.subtitleTracks');
  assertObject(spec.state, 'playback spec.state');
  assertType(spec.progressIntervalMs, 'number', 'playback spec.progressIntervalMs');
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
