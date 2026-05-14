import { spawnSync } from 'node:child_process';
import { NAError } from './reporter.js';

// ── Primitive assertions ──────────────────────────────────────────────────────

export function assert(condition, message) {
  if (!condition) throw new Error(message);
}

export function assertType(value, type, path) {
  assert(typeof value === type, `${path} must be ${type}, got ${typeof value}`);
}

export function assertNonEmptyString(value, path) {
  assert(typeof value === 'string' && value.length > 0, `${path} must be a non-empty string`);
}

export function assertObject(value, path) {
  assert(
    value != null && typeof value === 'object' && !Array.isArray(value),
    `${path} must be a plain object`,
  );
}

export function assertArray(value, path) {
  assert(Array.isArray(value), `${path} must be an array`);
}

export function assertUrl(value, path) {
  assertNonEmptyString(value, path);
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`${path} is not a valid URL: ${value}`);
  }
  assert(
    parsed.protocol === 'http:' || parsed.protocol === 'https:',
    `${path} must use http or https protocol, got ${parsed.protocol}`,
  );
}

// ── Network checks ────────────────────────────────────────────────────────────

/**
 * HEAD-request a URL (with optional auth headers).
 * Throws on network errors or 4xx/5xx; accepts 2xx, 206, and 3xx.
 */
export async function checkUrlReachable(url, headers = {}) {
  let response;
  try {
    response = await fetch(url, { method: 'HEAD', headers });
  } catch (error) {
    throw new Error(`Network error reaching ${url}: ${error.message}`);
  }
  assert(
    response.status < 400,
    `URL returned HTTP ${response.status}: ${url}`,
  );
}

// ── ffprobe helpers ───────────────────────────────────────────────────────────

let _ffprobeAvailable;

/** Returns the ffprobe binary path, or null if not on $PATH. Cached after first call. */
export function ffprobeAvailable() {
  if (_ffprobeAvailable !== undefined) return _ffprobeAvailable;
  const result = spawnSync('ffprobe', ['-version'], { encoding: 'utf8', stdio: 'pipe' });
  _ffprobeAvailable = result.status === 0 ? 'ffprobe' : null;
  return _ffprobeAvailable;
}

/**
 * Run ffprobe on a URL and return parsed stream info.
 * Returns null (N/A) if ffprobe is not available.
 * Throws if ffprobe exits non-zero or returns no streams.
 *
 * @param {string} url
 * @param {Record<string,string>} headers  passed via -headers "Key: Value\r\n..."
 * @param {{ expectVideo?: boolean, expectImage?: boolean, expectAudio?: boolean }} opts
 */
export async function checkMediaWithFfprobe(url, headers = {}, opts = {}) {
  const binary = ffprobeAvailable();
  if (!binary) {
    throw new NAError('ffprobe not found on $PATH — media stream check skipped');
  }

  const headerArg = Object.entries(headers)
    .map(([k, v]) => `${k}: ${v}\r\n`)
    .join('');

  const args = [
    '-v', 'error',
    '-show_streams',
    '-print_format', 'json',
    ...(headerArg ? ['-headers', headerArg] : []),
    url,
  ];

  const result = spawnSync(binary, args, { encoding: 'utf8', stdio: 'pipe', timeout: 30_000 });

  if (result.status !== 0) {
    const errMsg = (result.stderr || result.stdout || '').trim().split('\n')[0] ?? 'unknown error';
    throw new Error(`ffprobe failed for ${url}: ${errMsg}`);
  }

  let parsed;
  try {
    parsed = JSON.parse(result.stdout);
  } catch {
    throw new Error(`ffprobe returned invalid JSON for ${url}`);
  }

  const streams = parsed?.streams ?? [];
  assert(streams.length > 0, `ffprobe found no streams in ${url}`);

  if (opts.expectVideo) {
    const hasVideo = streams.some((s) => s.codec_type === 'video');
    assert(hasVideo, `ffprobe found no video stream in ${url}`);
  }
  if (opts.expectAudio) {
    const hasAudio = streams.some((s) => s.codec_type === 'audio');
    assert(hasAudio, `ffprobe found no audio stream in ${url}`);
  }
  if (opts.expectImage) {
    // Images decoded by ffprobe appear as a single video stream with nb_frames=1
    const hasImage = streams.some(
      (s) => s.codec_type === 'video' && (s.nb_frames == null || Number(s.nb_frames) <= 1),
    );
    assert(hasImage, `ffprobe found no image stream in ${url}`);
  }

  return streams;
}

// ── JSON helpers ──────────────────────────────────────────────────────────────

export function parseJsonResult(value, method) {
  if (value == null) throw new Error(`${method} returned null`);
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch (error) {
    throw new Error(`${method} returned invalid JSON: ${error.message}`);
  }
}
