package com.opentune.provider.js

import android.content.Context
import android.content.res.AssetManager
import androidx.startup.Initializer

object AssetManagerHolder {
    @Volatile private var _assets: AssetManager? = null
    fun set(assets: AssetManager) { _assets = assets }
    fun get(): AssetManager = _assets ?: error("AssetManager not initialized")
}

class JsProviderInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        AssetManagerHolder.set(context.assets)
    }
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
