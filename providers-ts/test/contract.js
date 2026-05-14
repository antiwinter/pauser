import { SkipError } from './reporter.js';

const ENTRY_TYPES = new Set(['Folder', 'Series', 'Season', 'Episode', 'Playable', 'Other']);
const CONTAINER_TYPES = new Set(['Folder', 'Series', 'Season']);

export async function checkBridge(reporter, runner) {
  await reporter.step('provider bridge is present', async () => {
    const providesCover = await runner.getProvidesCover();
    assertType(providesCover, 'boolean', 'providesCover');
    for (const method of [
      'getFieldsSpec',
      'validateFields',
      'init',
      'listEntry',
      'search',
      'getDetail',
      'getPlaybackSpec',
      'onPlaybackReady',
      'onProgressTick',
      'onStop',
    ]) {
      assert(await runner.hasMethod(method), `missing method ${method}`);
    }
    return `providesCover=${providesCover}`;
  });
}

export async function loadFieldsSpec(reporter, runner) {
  return reporter.step('getFieldsSpec returns valid fields', async () => {
    const fields = parseJsonResult(await runner.callMethod('getFieldsSpec', {}), 'getFieldsSpec');
    assert(Array.isArray(fields), 'getFieldsSpec must return an array');
    for (const [index, field] of fields.entries()) validateFieldSpec(field, `fields[${index}]`);
    return fields;
  });
}

export async function validateProvider(reporter, runner, values) {
  return reporter.step('validateFields accepts env credentials', async () => {
    const result = parseJsonResult(await runner.callMethod('validateFields', { values }), 'validateFields');
    assertObject(result, 'validation result');
    assertType(result.success, 'boolean', 'validation result.success');
    if (!result.success) throw new Error(`validation failed: ${result.error ?? 'unknown provider error'}`);
    assertNonEmptyString(result.hash, 'validation result.hash');
    assertNonEmptyString(result.name, 'validation result.name');
    assertObject(result.fields, 'validation result.fields');
    return { name: result.name, hash: result.hash, fields: result.fields };
  });
}

export async function runInstanceSmoke(reporter, runner, credentials, options) {
  await reporter.step('init accepts validated credentials', async () => {
    await runner.callMethod('init', {
      credentials,
      capabilities: defaultCapabilities(),
    });
  });

  const rootList = await reporter.step('listEntry returns a root list', async () => {
    const list = parseJsonResult(
      await runner.callMethod('listEntry', { location: null, startIndex: 0, limit: 10 }),
      'listEntry',
    );
    validateEntryList(list, 'root list');
    return list;
  });

  await reporter.step('search returns an array shape', async () => {
    const result = parseJsonResult(
      await runner.callMethod('search', { scopeLocation: '', query: '' }),
      'search',
    );
    assert(Array.isArray(result), 'search must return an array');
    result.forEach((item, index) => validateEntryInfo(item, `search[${index}]`));
    return `${result.length} item(s)`;
  });

  const detailCandidate = rootList.items[0] ?? null;
  if (detailCandidate) {
    await reporter.step('getDetail returns a detail shape', async () => {
      const detail = parseJsonResult(
        await runner.callMethod('getDetail', { itemRef: detailCandidate.id }),
        'getDetail',
      );
      validateEntryDetail(detail);
      return detail.title;
    });
  } else {
    reporter.record({
      name: 'getDetail returns a detail shape',
      status: 'skip',
      durationMs: 0,
      error: new SkipError('root list is empty'),
    });
  }

  const playable = await findPlayableItem(runner, rootList);
  if (!playable) {
    reporter.record({
      name: 'getPlaybackSpec returns a playable spec',
      status: 'skip',
      durationMs: 0,
      error: new SkipError('no playable item found in browsed catalog sample'),
    });
    return;
  }

  let playback = null;
  await reporter.step('getPlaybackSpec returns a playable spec', async () => {
    const spec = parseJsonResult(
      await runner.callMethod('getPlaybackSpec', { itemRef: playable.id, startMs: 0 }),
      'getPlaybackSpec',
    );
    validatePlaybackSpec(spec);
    playback = spec;
    return spec.title || playable.title;
  });

  if (!options.hooks) {
    reporter.record({
      name: 'playback hooks can be called',
      status: 'skip',
      durationMs: 0,
      error: new SkipError('pass --hooks to send playback hook calls to the provider service'),
    });
    return;
  }

  await reporter.step('playback hooks can be called', async () => {
    const hooksState = playback?.hooksState ?? {};
    await runner.callMethod('onPlaybackReady', { hooksState, positionMs: 0, playbackRate: 1 });
    await runner.callMethod('onProgressTick', { hooksState, positionMs: 1_000, playbackRate: 1 });
    await runner.callMethod('onStop', { hooksState, positionMs: 1_000 });
  });
}

async function findPlayableItem(runner, rootList) {
  const queue = [...rootList.items];
  const seen = new Set();
  let scanned = 0;

  while (queue.length > 0 && scanned < 40) {
    const item = queue.shift();
    if (!item || seen.has(item.id)) continue;
    seen.add(item.id);
    scanned += 1;

    if (item.type === 'Playable' || item.type === 'Episode') return item;
    if (!CONTAINER_TYPES.has(item.type)) continue;

    try {
      const childList = parseJsonResult(
        await runner.callMethod('listEntry', { location: item.id, startIndex: 0, limit: 20 }),
        'listEntry',
      );
      validateEntryList(childList, `children of ${item.id}`);
      queue.push(...childList.items);
    } catch {
      // Some providers may expose non-browsable container-like items. Continue sampling.
    }
  }

  return null;
}

function validateFieldSpec(field, path) {
  assertObject(field, path);
  assertNonEmptyString(field.id, `${path}.id`);
  assertNonEmptyString(field.labelKey, `${path}.labelKey`);
  assert(['text', 'singleLine', 'password'].includes(field.kind), `${path}.kind is invalid`);
  if (field.required != null) assertType(field.required, 'boolean', `${path}.required`);
  if (field.sensitive != null) assertType(field.sensitive, 'boolean', `${path}.sensitive`);
  if (field.order != null) assertType(field.order, 'number', `${path}.order`);
  if (field.placeholderKey != null) assertType(field.placeholderKey, 'string', `${path}.placeholderKey`);
}

function validateEntryList(list, path) {
  assertObject(list, path);
  assert(Array.isArray(list.items), `${path}.items must be an array`);
  assertType(list.totalCount, 'number', `${path}.totalCount`);
  list.items.forEach((item, index) => validateEntryInfo(item, `${path}.items[${index}]`));
}

function validateEntryInfo(item, path) {
  assertObject(item, path);
  assertNonEmptyString(item.id, `${path}.id`);
  assertNonEmptyString(item.title, `${path}.title`);
  assert(ENTRY_TYPES.has(item.type), `${path}.type is invalid: ${item.type}`);
  if (item.cover != null) assertType(item.cover, 'string', `${path}.cover`);
  if (item.userData != null) {
    assertObject(item.userData, `${path}.userData`);
    assertType(item.userData.positionMs, 'number', `${path}.userData.positionMs`);
    assertType(item.userData.isFavorite, 'boolean', `${path}.userData.isFavorite`);
    assertType(item.userData.played, 'boolean', `${path}.userData.played`);
  }
}

function validateEntryDetail(detail) {
  assertObject(detail, 'detail');
  assertType(detail.title, 'string', 'detail.title');
  if (detail.overview != null) assertType(detail.overview, 'string', 'detail.overview');
  if (detail.logo != null) assertType(detail.logo, 'string', 'detail.logo');
  assert(Array.isArray(detail.backdrop), 'detail.backdrop must be an array');
  assertType(detail.isMedia, 'boolean', 'detail.isMedia');
  if (detail.rating != null) assertType(detail.rating, 'number', 'detail.rating');
  if (detail.bitrate != null) assertType(detail.bitrate, 'number', 'detail.bitrate');
  assert(Array.isArray(detail.externalUrls), 'detail.externalUrls must be an array');
  assertObject(detail.providerIds, 'detail.providerIds');
  assert(Array.isArray(detail.streams), 'detail.streams must be an array');
}

function validatePlaybackSpec(spec) {
  assertObject(spec, 'playback spec');
  assertNonEmptyString(spec.url, 'playback spec.url');
  assertObject(spec.headers, 'playback spec.headers');
  if (spec.mimeType != null) assertType(spec.mimeType, 'string', 'playback spec.mimeType');
  assertType(spec.title, 'string', 'playback spec.title');
  if (spec.durationMs != null) assertType(spec.durationMs, 'number', 'playback spec.durationMs');
  assert(Array.isArray(spec.subtitleTracks), 'playback spec.subtitleTracks must be an array');
  assertObject(spec.hooksState, 'playback spec.hooksState');
}

function parseJsonResult(value, method) {
  if (value == null) throw new Error(`${method} returned null`);
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch (error) {
    throw new Error(`${method} returned invalid JSON: ${error.message}`);
  }
}

function defaultCapabilities() {
  return {
    videoMime: ['video/mp4', 'video/webm', 'video/x-matroska'],
    audioMime: ['audio/mp4', 'audio/mpeg', 'audio/opus'],
    subtitleFormats: ['srt', 'vtt', 'ass'],
    maxPixels: 3840 * 2160,
  };
}

function assertObject(value, path) {
  assert(value != null && typeof value === 'object' && !Array.isArray(value), `${path} must be an object`);
}

function assertType(value, type, path) {
  assert(typeof value === type, `${path} must be ${type}`);
}

function assertNonEmptyString(value, path) {
  assert(typeof value === 'string' && value.length > 0, `${path} must be a non-empty string`);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}
