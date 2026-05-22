package com.opentune.storage

object StorageBindingsHolder {
    @Volatile private var instance: OpenTuneStorageBindings? = null
    fun set(bindings: OpenTuneStorageBindings) { instance = bindings }
    fun get(): OpenTuneStorageBindings = requireNotNull(instance) { "StorageBindings not initialized" }
}
