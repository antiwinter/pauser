/**
 * device-profile.ts — Builds the Emby DeviceProfile from codec capabilities.
 * Mirrors EmbyProvider.buildDeviceProfile() in Kotlin.
 */
import type { PlatformInfo } from '../../utils/types.js';
import type {
  DeviceProfile,
  DirectPlayProfile,
  TranscodingProfile,
  CodecProfile,
  ProfileCondition,
  SubtitleProfile,
  ResponseProfile,
} from './dto.js';

export function buildDeviceProfile(info: PlatformInfo, deviceName: string): DeviceProfile {
  const videoCodecs = info.videoCodecs.map((vc) => vc.codec);
  const audioCodecs = info.audioCodecs.map((ac) => ac.codec);

  const v = videoCodecs.length > 0 ? videoCodecs.join(',') : 'h264';
  const a = audioCodecs.length > 0 ? audioCodecs.join(',') : 'aac';

  const codecProfiles: CodecProfile[] = [];

  for (const vc of info.videoCodecs) {
    const conditions: ProfileCondition[] = [
      { Condition: 'LessThanEqual', Property: 'Width', Value: String(vc.maxWidth), IsRequired: false },
      { Condition: 'LessThanEqual', Property: 'Height', Value: String(vc.maxHeight), IsRequired: false },
    ];

    if (vc.profileLevels.length > 0) {
      const maxLevel = Math.max(...vc.profileLevels.map((pl) => pl.level));
      conditions.push({ Condition: 'LessThanEqual', Property: 'VideoLevel', Value: String(maxLevel), IsRequired: false });
    }

    codecProfiles.push({
      Type: 'Video',
      Codec: vc.codec,
      Conditions: conditions,
    });
  }

  const subtitleProfiles: SubtitleProfile[] = info.subtitleFormats.map((fmt) => ({
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
