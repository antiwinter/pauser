package com.opentune.provider.js

import android.content.Context
import dalvik.system.BaseDexClassLoader
import java.io.File

/**
 * Injects bootstrap dex elements into the app's PathClassLoader at runtime.
 *
 * After injection, app's classloader directly resolves bootstrap classes
 * without parent chain shenanigans. The loaded JAR's parent is simply
 * ctx.classLoader — everything is visible naturally.
 *
 * Uses DexPathList element injection — the same technique Tinker hotfix uses.
 */
object ClassPathInjector {

    @Volatile private var injected = false

    /**
     * Merges bootstrap JAR's dex elements into the app classloader.
     * Idempotent — safe to call multiple times.
     */
    fun inject(ctx: Context, bootstrapJar: File) {
        if (injected) return
        synchronized(this) {
            if (injected) return
            runCatching {
                val appLoader = ctx.classLoader

                // Get bootstrap's DexElements via a temporary DexClassLoader
                val tmpDir = File(ctx.codeCacheDir, "dex/_bootstrap").also { it.mkdirs() }
                val tmpSoDir = File(ctx.cacheDir, "so/_bootstrap").also { it.mkdirs() }
                val tmpLoader = dalvik.system.DexClassLoader(
                    bootstrapJar.absolutePath, tmpDir.absolutePath, tmpSoDir.absolutePath, appLoader
                )
                // pathList is on BaseDexClassLoader, not DexClassLoader
                val tmpPathListField = BaseDexClassLoader::class.java
                    .getDeclaredField("pathList")
                    .also { it.isAccessible = true }
                val tmpPathList = tmpPathListField.get(tmpLoader)

                val dexElementsField = tmpPathList.javaClass
                    .getDeclaredField("dexElements")
                    .also { it.isAccessible = true }
                val bootstrapElements = dexElementsField.get(tmpPathList) as? Array<*>
                    ?: return@synchronized

                // Merge: bootstrap elements + existing app elements
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
                    bootstrapElements.javaClass.componentType,
                    bootstrapElements.size + appElements.size
                )
                System.arraycopy(bootstrapElements, 0, merged, 0, bootstrapElements.size)
                System.arraycopy(appElements, 0, merged, bootstrapElements.size, appElements.size)
                appElementsField.set(appPathList, merged)

                injected = true
            }
        }
    }
}
