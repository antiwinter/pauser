package com.opentune.player

import android.content.Context
import java.security.MessageDigest

/**
 * Local **per-title** playback state for **resumption** (keyed by [resumeProgressKey]), e.g.
 * `emby_{serverId}_{itemId}` or `smb_{sourceId}_{path}`: **position** and **playback speed**.
 *
 * **Speed:** per title; if none stored, defaults to **1.0**. **Position:** [readResumePosition] / [writeResumePosition].
 *
 * Emby resume position for the detail screen still comes from the app database; this store mirrors
 * position/speed locally for the player shell. SMB uses these prefs for resume.
 */
object OpenTunePlaybackResumeStore {
    private const val PREFS_NAME = "opentune_player_playback"
    private const val KEY_POS_PREFIX = "resume_pos_"
    private const val KEY_SPEED_PREFIX = "resume_speed_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun positionPrefKey(resumeProgressKey: String): String =
        KEY_POS_PREFIX + sha256Hex(resumeProgressKey)

    private fun speedPrefKey(resumeProgressKey: String): String =
        KEY_SPEED_PREFIX + sha256Hex(resumeProgressKey)

    private fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    /** Per-title speed, or **1f** if nothing stored for this title. */
    fun readSpeed(context: Context, resumeProgressKey: String): Float {
        val v = prefs(context).getFloat(speedPrefKey(resumeProgressKey), 1f)
        return v.coerceIn(0.25f, 4f)
    }

    fun writeSpeed(context: Context, resumeProgressKey: String, speed: Float) {
        prefs(context).edit().putFloat(speedPrefKey(resumeProgressKey), speed.coerceIn(0.25f, 4f)).apply()
    }

    /** Last known position for this title, or `-1` if none stored. */
    fun readResumePosition(context: Context, resumeProgressKey: String): Long {
        val v = prefs(context).getLong(positionPrefKey(resumeProgressKey), -1L)
        return if (v >= 0L) v else -1L
    }

    fun writeResumePosition(context: Context, resumeProgressKey: String, positionMs: Long) {
        prefs(context).edit().putLong(positionPrefKey(resumeProgressKey), positionMs.coerceAtLeast(0L)).apply()
    }

    fun clearResumePosition(context: Context, resumeProgressKey: String) {
        prefs(context).edit().remove(positionPrefKey(resumeProgressKey)).apply()
    }
}
