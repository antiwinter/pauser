/**
 * Shared container → MIME hints for ExoPlayer (mirrors Kotlin `PlaybackMimeTypes` in contracts).
 */

export function playbackMimeTypeFromContainers(
  transcodingContainer: string | null | undefined,
  container: string | null | undefined,
): string | null {
  const c = (transcodingContainer && transcodingContainer.trim())
    || (container && container.trim())
    || '';
  if (!c) return null;
  return playbackMimeTypeFromRawFormat(c);
}

export function playbackMimeTypeFromRawFormat(raw: string): string | null {
  switch (raw.toLowerCase()) {
    case 'm3u8':
    case 'hls':
      return 'application/vnd.apple.mpegurl';
    case 'ts':
      return 'video/mp2t';
    case 'mkv':
    case 'matroska':
      return 'video/x-matroska';
    case 'mp4':
    case 'm4v':
      return 'video/mp4';
    case 'webm':
      return 'video/webm';
    default:
      return null;
  }
}
