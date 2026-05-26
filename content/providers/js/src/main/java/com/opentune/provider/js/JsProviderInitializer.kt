package com.opentune.provider.js

import android.content.Context
import androidx.startup.Initializer

object ContextHolder {
    @Volatile private var _context: Context? = null
    fun set(context: Context) { _context = context }
    fun get(): Context = _context ?: error("Context not initialized")
}

class JsProviderInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        ContextHolder.set(context.applicationContext)
    }
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
