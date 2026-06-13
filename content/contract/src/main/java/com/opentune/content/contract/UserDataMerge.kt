package com.opentune.content.contract

import com.opentune.storage.EntryStateEntity

/**
 * Merges provider [remote] userData with app-local [local] state.
 *
 * Priority: remote → local. When the provider supplies [remote], it is used as-is.
 * Otherwise local [EntryStateEntity] fills in (SMB and other providers with no remote userData).
 */
internal object UserDataMerge {

    fun merge(remote: EntryUserData?, local: EntryStateEntity?): EntryUserData? {
        if (remote != null) return remote
        if (local == null) return null
        return EntryUserData(
            positionMs = local.positionMs,
            isFavorite = local.isFavorite,
            played = local.positionMs > 0,
        )
    }
}
