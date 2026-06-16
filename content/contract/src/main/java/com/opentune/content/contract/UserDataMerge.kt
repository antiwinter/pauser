package com.opentune.content.contract

import com.opentune.storage.EntryStateEntity

/**
 * Merges provider [remote] userData with app-local [local] state.
 *
 * Remote wins per field when present. [EntryUserData.positionMs] may be null when a
 * provider has no meaningful value (series focus index); local fills that gap.
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
        val positionMs = remote.positionMs ?: local.positionMs
        return remote.copy(positionMs = positionMs)
    }
}
