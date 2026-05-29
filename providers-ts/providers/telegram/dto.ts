/**
 * dto.ts — TDLib JSON response types and shim contract types.
 */

// ── TDLib Chat types ────────────────────────────────────────────────────────

export interface TdChatType {
  '@type': string;
}

export interface TdChatPhoto {
  small?: { remote?: { id: string } };
  big?: { remote?: { id: string } };
}

export interface TdChat {
  id: string;
  title: string;
  type: TdChatType;
  photo?: TdChatPhoto;
  username?: string;
  is_marked_as_unread?: boolean;
  last_message?: TdMessage;
  positions?: TdChatPosition[];
}

export interface TdChatPosition {
  list: { '@type': string; id: string };
  order: string;
}

// ── TDLib Message types ─────────────────────────────────────────────────────

export interface TdFormattedText {
  text: string;
  entities: TdTextEntity[];
}

export interface TdTextEntity {
  type: { '@type': string; url?: string };
  offset: number;
  length: number;
}

export interface TdPhotoSize {
  width: number;
  height: number;
  file_size?: number;
}

export interface TdPhoto {
  sizes: TdPhotoSize[];
}

export interface TdVideo {
  duration: number;
  width: number;
  height: number;
  file_name?: string;
  mime_type?: string;
  thumbnail?: TdPhotoSize;
}

export interface TdMessageContent {
  '@type': string;
  text?: TdFormattedText;
  photo?: TdPhoto;
  video?: TdVideo;
  caption?: TdFormattedText;
}

export interface TdMessage {
  id: number;
  chat_id: string;
  content: TdMessageContent;
  date: number;
  sender_id?: { '@type': string; user_id?: string };
}

// ── TDLib Chat list ─────────────────────────────────────────────────────────

export interface TdChats {
  chat_ids: string[];
  total_count?: number;
}

// ── TDLib File (download info) ──────────────────────────────────────────────

export interface TdFile {
  id: number;
  size?: number;
  remote?: { id: string; is_upload_completed: boolean };
  local?: { path: string; is_downloading_active: boolean };
}

// ── Shim contract types ─────────────────────────────────────────────────────

export interface ShimEntryItem {
  id: string;
  title: string;
  type: string;
  cover?: string | null;
  userData?: {
    positionMs: number;
    isFavorite: boolean;
    played: boolean;
  };
  originalTitle?: string;
  genres?: string[];
  communityRating?: number;
  studios?: string[];
  childCount?: number;
}

export interface ShimEntryList {
  items: ShimEntryItem[];
  totalCount: number;
}

export interface ShimEntryDetail {
  title: string;
  overview: string | null;
  logo: string | null;
  backdrop: string[];
  isMedia: boolean;
  rating: number | null;
  bitrate: number | null;
  externalUrls: { name: string; url: string }[];
  year: number | null;
  providerIds: Record<string, string>;
  streams: {
    index: number;
    type: string;
    codec: string | null;
    title: string | null;
    language: string | null;
    isDefault: boolean;
    isForced: boolean;
  }[];
  etag: string | null;
}

export interface ShimPlaybackSpec {
  url: string;
  headers?: Record<string, string>;
  mimeType?: string | null;
  title?: string;
  durationMs?: number | null;
  subtitleTracks?: {
    trackId: string;
    label: string;
    language: string | null;
    isDefault: boolean;
    isForced: boolean;
    externalRef: string | null;
  }[];
  hooksState?: Record<string, unknown>;
}
