import {
  assert,
  assertType,
  assertNonEmptyString,
  assertObject,
  assertArray,
  assertUrl,
  checkMediaWithFfprobe,
  parseJsonResult,
} from '../validators.js';
import { NAError } from '../reporter.js';

/**
 * Category: Detail
 * Covers getDetail and verifies the full EntryDetail shape, including media URLs.
 *
 * @param {import('../reporter.js').Reporter} reporter
 * @param {import('../quickjs-runner.js').QuickJsProviderRunner} runner
 * @param {{ firstItem: object|null, ffprobe: boolean }} opts
 * @returns {Promise<{ detail: object|null }>}
 */
export async function runDetailChecks(reporter, runner, opts) {
  reporter.beginCategory('Detail', ['getDetail']);

  const { firstItem, ffprobe } = opts;
  let detail = null;

  detail = await reporter.step('getDetail returns a valid EntryDetail shape', async () => {
    if (!firstItem) throw new NAError('no item available from catalog — cannot test getDetail');
    const result = parseJsonResult(
      await runner.callMethod('getDetail', { itemRef: firstItem.id }),
      'getDetail',
    );
    validateEntryDetailShape(result);
    return result;
  });

  await reporter.step('detail required string fields are present', async () => {
    if (!detail) throw new NAError('detail not loaded');
    assertNonEmptyString(detail.title, 'detail.title');
    assertType(detail.isMedia, 'boolean', 'detail.isMedia');
    return `title="${detail.title}" isMedia=${detail.isMedia}`;
  });

  await reporter.step('detail optional scalar fields have correct types when present', async () => {
    if (!detail) throw new NAError('detail not loaded');
    if (detail.overview != null) assertType(detail.overview, 'string', 'detail.overview');
    if (detail.rating != null) assertType(detail.rating, 'number', 'detail.rating');
    if (detail.bitrate != null) assertType(detail.bitrate, 'number', 'detail.bitrate');
    if (detail.year != null) assertType(detail.year, 'number', 'detail.year');
    if (detail.etag != null) assertType(detail.etag, 'string', 'detail.etag');
    return 'ok';
  });

  await reporter.step('detail.providerIds is a string→string map', async () => {
    if (!detail) throw new NAError('detail not loaded');
    assertObject(detail.providerIds, 'detail.providerIds');
    for (const [k, v] of Object.entries(detail.providerIds)) {
      assertType(k, 'string', 'providerIds key');
      assertType(v, 'string', `providerIds["${k}"]`);
    }
    return `${Object.keys(detail.providerIds).length} provider id(s)`;
  });

  await reporter.step('detail.streams are valid StreamInfo objects', async () => {
    if (!detail) throw new NAError('detail not loaded');
    assertArray(detail.streams, 'detail.streams');
    if (detail.streams.length === 0) throw new NAError('detail.streams is empty — no stream info to validate');
    for (const [i, s] of detail.streams.entries()) validateStreamInfo(s, `detail.streams[${i}]`);
    return `${detail.streams.length} stream(s)`;
  });

  await reporter.step('detail.externalUrls are valid ExternalUrl objects', async () => {
    if (!detail) throw new NAError('detail not loaded');
    assertArray(detail.externalUrls, 'detail.externalUrls');
    if (detail.externalUrls.length === 0) throw new NAError('detail.externalUrls is empty');
    for (const [i, eu] of detail.externalUrls.entries()) {
      assertNonEmptyString(eu.name, `detail.externalUrls[${i}].name`);
      assertUrl(eu.url, `detail.externalUrls[${i}].url`);
    }
    return `${detail.externalUrls.length} external URL(s)`;
  });

  // ── Logo ──────────────────────────────────────────────────────────────────

  await reporter.step('detail.logo is a valid URL', async () => {
    if (!detail) throw new NAError('detail not loaded');
    if (detail.logo == null) throw new NAError('detail.logo is null — provider did not supply a logo');
    assertUrl(detail.logo, 'detail.logo');
    return detail.logo;
  });

  await reporter.step('detail.logo passes ffprobe image check', async () => {
    if (!detail) throw new NAError('detail not loaded');
    if (detail.logo == null) throw new NAError('detail.logo is null');
    if (!ffprobe) throw new NAError('--no-ffprobe flag set');
    await checkMediaWithFfprobe(detail.logo, {}, { expectImage: true });
    return 'ok';
  });

  // ── Backdrop ──────────────────────────────────────────────────────────────

  await reporter.step('detail.backdrop contains valid URLs', async () => {
    if (!detail) throw new NAError('detail not loaded');
    if (detail.backdrop.length === 0) throw new NAError('detail.backdrop is empty — provider did not supply backdrops');
    for (const [i, url] of detail.backdrop.entries()) {
      assertUrl(url, `detail.backdrop[${i}]`);
    }
    return `${detail.backdrop.length} backdrop URL(s)`;
  });

  await reporter.step('detail.backdrop URLs pass ffprobe image check', async () => {
    if (!detail) throw new NAError('detail not loaded');
    if (detail.backdrop.length === 0) throw new NAError('detail.backdrop is empty');
    if (!ffprobe) throw new NAError('--no-ffprobe flag set');
    const sample = detail.backdrop.slice(0, 3);
    for (const [i, url] of sample.entries()) {
      await checkMediaWithFfprobe(url, {}, { expectImage: true });
    }
    return `checked ${sample.length} backdrop(s)`;
  });

  reporter.endCategory();
  return { detail };
}

// ── Validators ────────────────────────────────────────────────────────────────

function validateEntryDetailShape(detail) {
  assertObject(detail, 'detail');
  assertType(detail.title, 'string', 'detail.title');
  assertType(detail.isMedia, 'boolean', 'detail.isMedia');
  assertArray(detail.backdrop, 'detail.backdrop');
  assertArray(detail.externalUrls, 'detail.externalUrls');
  assertObject(detail.providerIds, 'detail.providerIds');
  assertArray(detail.streams, 'detail.streams');
  if (detail.logo != null) assertType(detail.logo, 'string', 'detail.logo');
  if (detail.overview != null) assertType(detail.overview, 'string', 'detail.overview');
  if (detail.rating != null) assertType(detail.rating, 'number', 'detail.rating');
  if (detail.bitrate != null) assertType(detail.bitrate, 'number', 'detail.bitrate');
  if (detail.year != null) assertType(detail.year, 'number', 'detail.year');
  if (detail.etag != null) assertType(detail.etag, 'string', 'detail.etag');
}

function validateStreamInfo(s, path) {
  assertObject(s, path);
  assertType(s.index, 'number', `${path}.index`);
  assertNonEmptyString(s.type, `${path}.type`);
  assertType(s.isDefault, 'boolean', `${path}.isDefault`);
  assertType(s.isForced, 'boolean', `${path}.isForced`);
  if (s.codec != null) assertType(s.codec, 'string', `${path}.codec`);
  if (s.title != null) assertType(s.title, 'string', `${path}.title`);
  if (s.language != null) assertType(s.language, 'string', `${path}.language`);
}
