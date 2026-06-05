/**
 * Central filename type detection.
 * Mirrors FilenameDetector.kt — used by routing to classify "Unknown" entries.
 */

const VIDEO_EXTS = new Set([
  '.mkv', '.mp4', '.avi', '.webm', '.m4v', '.mov', '.wmv', '.flv', '.ts', '.m2ts',
]);
const AUDIO_EXTS = new Set([
  '.mp3', '.flac', '.aac', '.ogg', '.wav', '.wma', '.m4a', '.opus', '.alac',
]);
const IMAGE_EXTS = new Set([
  '.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp', '.tiff',
]);

export function detectType(filename: string): string {
  const lower = filename.toLowerCase();
  if (endsWithAny(lower, VIDEO_EXTS)) return 'Video';
  if (endsWithAny(lower, AUDIO_EXTS)) return 'Audio';
  if (endsWithAny(lower, IMAGE_EXTS)) return 'Image';
  return 'Unknown';
}

function endsWithAny(filename: string, exts: Set<string>): boolean {
  for (const ext of exts) {
    if (filename.endsWith(ext)) return true;
  }
  return false;
}
