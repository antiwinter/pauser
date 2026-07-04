package com.insomnia.storage

object StorageBindingsHolder {
    @Volatile private var instance: InsomniaStorageBindings? = null
    fun set(bindings: InsomniaStorageBindings) { instance = bindings }
    fun get(): InsomniaStorageBindings = requireNotNull(instance) { "StorageBindings not initialized" }
}
