package com.opentune.provider.js

import android.content.Context
import dalvik.system.BaseDexClassLoader
import java.io.File

/**
 * Injects shim dex elements into the app's PathClassLoader at runtime.
 *
 * After injection, app's classloader directly resolves shim classes (Spider,
 * SpiderDebug, gson) without parent chain shenanigans. Spider JAR's parent
 * is simply ctx.classLoader — everything is visible naturally.
 *
 * Uses DexPathList element injection — the same technique Tinker hotfix uses.
 */
object ClassPathInjector {

    @Volatile private var injected = false

    /**
     * Merges shim JAR's dex elements into the app classloader.
     * Idempotent — safe to call multiple times.
     */
    fun inject(ctx: Context, shimJar: File) {
        if (injected) return
        synchronized(this) {
            if (injected) return
            runCatching {
                val appLoader = ctx.classLoader

                // Get shim's DexElements via a temporary DexClassLoader
                val tmpDir = File(ctx.codeCacheDir, "dex/_shim").also { it.mkdirs() }
                val tmpSoDir = File(ctx.cacheDir, "so/_shim").also { it.mkdirs() }
                val shimLoader = dalvik.system.DexClassLoader(
                    shimJar.absolutePath, tmpDir.absolutePath, tmpSoDir.absolutePath, appLoader
                )
                // pathList is on BaseDexClassLoader, not DexClassLoader
                val shimPathListField = BaseDexClassLoader::class.java
                    .getDeclaredField("pathList")
                    .also { it.isAccessible = true }
                val shimPathList = shimPathListField.get(shimLoader)

                val dexElementsField = shimPathList.javaClass
                    .getDeclaredField("dexElements")
                    .also { it.isAccessible = true }
                val shimElements = dexElementsField.get(shimPathList) as? Array<*>
                    ?: return@synchronized

                // Merge: shim elements + existing app elements
                val appPathList = BaseDexClassLoader::class.java
                    .getDeclaredField("pathList")
                    .also { it.isAccessible = true }
                    .get(appLoader)
                val appElementsField = appPathList.javaClass
                    .getDeclaredField("dexElements")
                    .also { it.isAccessible = true }
                val appElements = appElementsField.get(appPathList) as? Array<*>
                    ?: return@synchronized

                val merged = java.lang.reflect.Array.newInstance(
                    shimElements.javaClass.componentType,
                    shimElements.size + appElements.size
                )
                System.arraycopy(shimElements, 0, merged, 0, shimElements.size)
                System.arraycopy(appElements, 0, merged, shimElements.size, appElements.size)
                appElementsField.set(appPathList, merged)

                injected = true
            }
        }
    }
}
