/**
 * dto.ts — TypeScript equivalents of the Kotlin Emby DTO classes.
 * All fields use the JSON key names (PascalCase) matching Emby/Jellyfin API responses.
 */

export interface AuthenticateByNameRequest {
  Username: string;
  Pw: string;
}

export interface UserDto {
  Id?: string | null;
  Name?: string | null;
}

export interface AuthenticationResult {
  User?: UserDto | null;
  AccessToken?: string | null;
  ServerId?: string | null;
}

export interface SystemInfoDto {
  Id?: string | null;
  ServerName?: string | null;
  Version?: string | null;
}

export interface UserItemDataDto {
  PlaybackPositionTicks?: number | null;
  PlayedPercentage?: number | null;
  IsFavorite?: boolean | null;
  Played?: boolean | null;
}

export interface StudioDto {
  Name?: string | null;
}

export interface ExternalUrlDto {
  Name?: string | null;
  Url?: string | null;
}

export interface MediaStream {
  Codec?: string | null;
  Language?: string | null;
  DisplayTitle?: string | null;
  IsInterlaced?: boolean | null;
  IsDefault?: boolean | null;
  IsForced?: boolean | null;
  IsExternal?: boolean | null;
  Path?: string | null;
  Index?: number | null;
  Type?: string | null;
  Bitrate?: number | null;
  ChannelLayout?: string | null;
  Channels?: number | null;
  SampleRate?: number | null;
  Height?: number | null;
  Width?: number | null;
  AverageFrameRate?: number | null;
  Level?: number | null;
  Profile?: string | null;
  AspectRatio?: string | null;
}

export interface MediaSourceInfo {
  Id?: string | null;
  Protocol?: string | null;
  Path?: string | null;
  Type?: string | null;
  Container?: string | null;
  Size?: number | null;
  Name?: string | null;
  IsRemote?: boolean | null;
  ETag?: string | null;
  RunTimeTicks?: number | null;
  SupportsTranscoding?: boolean | null;
  SupportsDirectStream?: boolean | null;
  SupportsDirectPlay?: boolean | null;
  IsInfiniteStream?: boolean | null;
  LiveStreamId?: string | null;
  MediaStreams?: MediaStream[];
  Bitrate?: number | null;
  TranscodingUrl?: string | null;
  TranscodingSubProtocol?: string | null;
  TranscodingContainer?: string | null;
  DirectStreamUrl?: string | null;
  AddApiKeyToDirectStreamUrl?: boolean | null;
  DefaultAudioStreamIndex?: number | null;
  DefaultSubtitleStreamIndex?: number | null;
}

export interface BaseItemDto {
  Id?: string | null;
  Name?: string | null;
  Type?: string | null;
  Overview?: string | null;
  RunTimeTicks?: number | null;
  SeriesName?: string | null;
  ImageTags?: Record<string, string> | null;
  BackdropImageTags?: string[] | null;
  UserData?: UserItemDataDto | null;
  OriginalTitle?: string | null;
  CommunityRating?: number | null;
  Genres?: string[] | null;
  Studios?: StudioDto[] | null;
  ProductionYear?: number | null;
  IndexNumber?: number | null;
  Etag?: string | null;
  ProviderIds?: Record<string, string> | null;
  ExternalUrls?: ExternalUrlDto[] | null;
  MediaSources?: MediaSourceInfo[] | null;
  MediaStreams?: MediaStream[] | null;
}

export interface QueryResultBaseItemDto {
  Items: BaseItemDto[];
  TotalRecordCount: number;
}

export interface PlaybackInfoRequest {
  Id?: string | null;
  UserId?: string | null;
  MaxStreamingBitrate?: number | null;
  StartTimeTicks?: number | null;
  AudioStreamIndex?: number | null;
  SubtitleStreamIndex?: number | null;
  DeviceProfile?: DeviceProfile | null;
  EnableDirectPlay: boolean;
  EnableDirectStream: boolean;
  EnableTranscoding: boolean;
  AutoOpenLiveStream: boolean;
  AllowVideoStreamCopy: boolean;
  AllowAudioStreamCopy: boolean;
}

export interface PlaybackInfoResponse {
  MediaSources: MediaSourceInfo[];
  PlaySessionId?: string | null;
  ErrorCode?: string | null;
}

export interface PlaybackStartInfo {
  ItemId: string;
  MediaSourceId?: string | null;
  PlaySessionId?: string | null;
  LiveStreamId?: string | null;
  PlayMethod: string;
  PositionTicks: number;
  PlaybackRate: number;
}

export interface PlaybackProgressInfo {
  ItemId: string;
  MediaSourceId?: string | null;
  PlaySessionId?: string | null;
  LiveStreamId?: string | null;
  PlayMethod: string;
  PositionTicks: number;
  PlaybackRate: number;
  IsPaused?: boolean;
}

export interface PlaybackStopInfo {
  ItemId: string;
  MediaSourceId?: string | null;
  PlaySessionId?: string | null;
  LiveStreamId?: string | null;
  PositionTicks: number;
}

// ── DeviceProfile ──────────────────────────────────────────────────────────

export interface DirectPlayProfile {
  Container: string;
  Type: string;
  VideoCodec?: string | null;
  AudioCodec?: string | null;
}

export interface TranscodingProfile {
  Container: string;
  Type: string;
  VideoCodec: string;
  AudioCodec: string;
  Protocol: string;
  Context: string;
  MaxAudioChannels: string;
  MinSegments: number;
  SegmentLength: number;
  BreakOnNonKeyFrames: boolean;
}

export interface ProfileCondition {
  Condition: string;
  Property: string;
  Value: string;
  IsRequired: boolean;
}

export interface CodecProfile {
  Type: string;
  Codec?: string | null;
  Conditions: ProfileCondition[];
}

export interface SubtitleProfile {
  Format: string;
  Method: string;
}

export interface ResponseProfile {
  Type: string;
  Container: string;
  MimeType: string;
}

export interface DeviceProfile {
  Name: string;
  FriendlyName: string;
  Manufacturer: string;
  ModelName: string;
  MaxStreamingBitrate: number;
  MaxStaticBitrate: number;
  MusicStreamingTranscodingBitrate: number;
  MusicSyncBitrate: number;
  SupportedMediaTypes: string;
  DirectPlayProfiles: DirectPlayProfile[];
  TranscodingProfiles: TranscodingProfile[];
  CodecProfiles: CodecProfile[];
  SubtitleProfiles: SubtitleProfile[];
  ResponseProfiles: ResponseProfile[];
}
