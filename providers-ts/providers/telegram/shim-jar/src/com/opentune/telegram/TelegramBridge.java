package com.opentune.telegram;

import android.content.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram bridge exposed to the TypeScript provider via jar.reflect().
 * All TDLib/MTProto logic lives here — loaded from telegram-shim.jar asset.
 */
public abstract class TelegramBridge {

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /** Initialize TDLib with the Android context. */
    public abstract void init(Context ctx) throws Exception;

    /** Initialize with optional config blob (e.g. proxy settings, API credentials). */
    public abstract void init(Context ctx, String extend) throws Exception;

    // ── QR Auth ────────────────────────────────────────────────────────────

    /**
     * Start QR auth flow. Returns JSON:
     *   {"token": "...", "qrData": "tg://login?token=..."}
     */
    public abstract String getQr() throws Exception;

    /**
     * Poll QR auth status. Returns JSON:
     *   {"status": "NEW"|"SCANNED"|"CONFIRMED"|"EXPIRED"|"CANCELED", "fields": {...}}
     */
    public abstract String pollQr(String token) throws Exception;

    // ── Content (returns JSON strings matching OpenTune contract types) ─────

    /** Top-level: returns list of joined channels/supergroups. */
    public abstract String homeContent(boolean filter) throws Exception;

    /** Sub-folder: returns messages in a chat/type filter. */
    public abstract String categoryContent(String chatId, String pg, boolean filter, HashMap<String, String> extend) throws Exception;

    /** Detail view for a message or chat. */
    public abstract String detailContent(List<String> ids) throws Exception;

    /** Full-text search across all accessible messages. */
    public abstract String searchContent(String key, boolean quick, String pg) throws Exception;

    /** Playback URL for a video message. */
    public abstract String playerContent(String flag, String id, List<String> vipFlags) throws Exception;

    /** Thumbnail sprite for a video message (returns data:image/jpeg;base64,...). */
    public abstract String getSprite(String itemRef, long ts) throws Exception;

    /** Cleanup TDLib resources. */
    public abstract void destroy();
}
