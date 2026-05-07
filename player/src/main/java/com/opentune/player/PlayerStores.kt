package com.opentune.player

import com.opentune.storage.DataStoreAppConfigStore
import com.opentune.storage.UserMediaStateStore

data class PlayerStores(
    val mediaStateStore: UserMediaStateStore,
    val appConfigStore: DataStoreAppConfigStore?,
)
