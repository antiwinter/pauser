/**
 * device-profile.ts — Builds the Emby DeviceProfile from codec capabilities.
 * Mirrors EmbyProvider.buildDeviceProfile() in Kotlin.
 */
import type { CodecCapabilities } from '../src/types.js';
import type {
  DeviceProfile,
  DirectPlayProfile,
  TranscodingProfile,
  CodecProfile,
  ProfileCondition,
  SubtitleProfile,
  ResponseProfile,
} from './dto.js';

function mimeToVideoCodec(mime: string): string | null {
  switch (mime) {
    case 'video/avc':  return 'h264';
    case 'video/hevc': return 'hevc';
    case 'video/vp9':  return 'vp9';
    case 'video/av01': return 'av1';
    default:           return null;
  }
}

function mimeToAudioCodec(mime: string): string | null {
  switch (mime) {
    case 'audio/mp4a-latm': return 'aac';
    case 'audio/ac3':       return 'ac3';
    case 'audio/eac3':      return 'eac3';
    case 'audio/mpeg':      return 'mp3';
    case 'audio/opus':      return 'opus';
    case 'audio/flac':      return 'flac';
    default:                return null;
  }
}

export function buildDeviceProfile(caps: CodecCapabilities, deviceName: string): DeviceProfile {
  const videoCodecs = [...new Set(caps.videoMimes.map(mimeToVideoCodec).filter(Boolean) as string[])];
  const audioCodecs = [...new Set(caps.audioMimes.map(mimeToAudioCodec).filter(Boolean) as string[])];

  const v = videoCodecs.length > 0 ? videoCodecs.join(',') : 'h264';
  const a = audioCodecs.length > 0 ? audioCodecs.join(',') : 'aac';

  const codecProfiles: CodecProfile[] = [];

  const maxPx = caps.maxVideoPixels ?? (1920 * 1080);
  const w = Math.max(1, Math.floor(Math.sqrt(maxPx) / 8) * 8);
  const h = Math.max(1, Math.floor(maxPx / w / 8) * 8);

  if (videoCodecs.includes('hevc')) {
    codecProfiles.push({
      Type: 'Video',
      Codec: 'hevc',
      Conditions: [
        { Condition: 'LessThanEqual', Property: 'Width',  Value: String(w), IsRequired: false },
        { Condition: 'LessThanEqual', Property: 'Height', Value: String(h), IsRequired: false },
      ],
    });
  }
  if (videoCodecs.includes('h264')) {
    codecProfiles.push({
      Type: 'Video',
      Codec: 'h264',
      Conditions: [
        { Condition: 'LessThanEqual', Property: 'Width',   Value: String(w), IsRequired: false },
        { Condition: 'LessThanEqual', Property: 'Height',  Value: String(h), IsRequired: false },
        { Condition: 'LessThanEqual', Property: 'VideoLevel', Value: '52', IsRequired: false },
      ],
    });
  }

  const subtitleProfiles: SubtitleProfile[] = caps.subtitleFormats.map((fmt) => ({
    Format: fmt,
    Method: 'Embed',
  }));

  return {
    Name: 'OpenTune Android TV',
    FriendlyName: 'OpenTune',
    Manufacturer: 'OpenTune',
    ModelName: deviceName,
    MaxStreamingBitrate: 120_000_000,
    MaxStaticBitrate: 120_000_000,
    MusicStreamingTranscodingBitrate: 192_000,
    MusicSyncBitrate: 128_000,
    SupportedMediaTypes: 'Video,Audio',
    DirectPlayProfiles: [
      {
        Container: 'mp4,mkv,avi,m4v,mov,webm',
        Type: 'Video',
        VideoCodec: v,
        AudioCodec: a,
      },
    ],
    TranscodingProfiles: [
      {
        Container: 'ts',
        Type: 'Video',
        VideoCodec: 'h264',
        AudioCodec: 'aac',
        Protocol: 'hls',
        Context: 'Streaming',
        MaxAudioChannels: '8',
        MinSegments: 1,
        SegmentLength: 6,
        BreakOnNonKeyFrames: false,
      },
    ],
    CodecProfiles: codecProfiles,
    SubtitleProfiles: subtitleProfiles,
    ResponseProfiles: [
      { Type: 'Video', Container: 'm3u8', MimeType: 'application/vnd.apple.mpegurl' },
    ],
  };
}
