package com.opentune.content.contract

import com.opentune.storage.EntryStateEntity

/**
 * Merges provider [remote] userData with app-local [local] state.
 *
 * Local position wins when non-zero: the heartbeat writes it during playback and is always
 * more current than any server value cached before or during a session. Remote position is
 * used as fallback (e.g. progress synced from another device, or null for series focus index).
 */
internal object UserDataMerge {

    fun merge(remote: EntryUserData?, local: EntryStateEntity?): EntryUserData? {
        if (remote == null) {
            if (local == null) return null
            return EntryUserData(
                positionMs = local.positionMs,
                isFavorite = local.isFavorite,
                played = local.positionMs > 0,
            )
        }
        if (local == null) return remote
        // Local wins when non-zero; remote.positionMs may be 0 (not null) even with no history.
        val positionMs = if (local.positionMs > 0L) local.positionMs else remote.positionMs
        return remote.copy(positionMs = positionMs)
    }
}
