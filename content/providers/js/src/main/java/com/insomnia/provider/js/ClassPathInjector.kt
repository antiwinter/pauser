package com.insomnia.provider.js

import android.content.Context
import dalvik.system.BaseDexClassLoader
import java.io.File

/**
 * Injects bootstrap dex elements into the app's PathClassLoader at runtime.
 *
 * After injection, the app's classloader directly resolves bootstrap classes
 * without parent-chain shenanigans. The loaded JAR's parent is simply
 * `ctx.classLoader` — everything is visible naturally.
 *
 * Uses DexPathList element injection — the same technique Tinker hotfix uses.
 *
 * Tracks per-jar injection so multiple distinct bootstrap JARs (one per
 * provider) can be fused into the same app classloader.
 */
object ClassPathInjector {

    /** Absolute paths we've already merged. Idempotent per jar. */
    private val injected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Merges [bootstrapJar]'s dex elements into the app classloader.
     * Safe to call for multiple distinct jars — second-and-later calls for
     * the same path no-op.
     */
    fun inject(ctx: Context, bootstrapJar: File) {
        val key = bootstrapJar.absolutePath
        if (!injected.add(key)) return
        synchronized(this) {
            runCatching {
                val appLoader = ctx.classLoader

                // Android 13+ DexFile rejects jars whose parents have group/world write.
                // Strip defensively here even though the staging layer (JsProviderLoader
                // / JarLoader) chmods the file before calling — belt-and-braces for the
                // case where a stale cache dir re-emerged with fresh perms.
                runCatching { bootstrapJar.setReadOnly() }
                bootstrapJar.parentFile?.let { d -> DexFilePermissions.chmodForDex(d) }
                DexFilePermissions.chmodForDex(ctx.codeCacheDir)

                // Get bootstrap's DexElements via a temporary DexClassLoader.
                val tmpDir = File(ctx.codeCacheDir, "dex/_bootstrap").also { it.mkdirs() }
                DexFilePermissions.chmodForDex(tmpDir)
                val tmpSoDir = File(ctx.cacheDir, "so/_bootstrap").also { it.mkdirs() }
                val tmpLoader = dalvik.system.DexClassLoader(
                    bootstrapJar.absolutePath, tmpDir.absolutePath, tmpSoDir.absolutePath, appLoader
                )
                // pathList is on BaseDexClassLoader, not DexClassLoader.
                val tmpPathListField = BaseDexClassLoader::class.java
                    .getDeclaredField("pathList")
                    .also { it.isAccessible = true }
                val tmpPathList = tmpPathListField.get(tmpLoader)

                val dexElementsField = tmpPathList.javaClass
                    .getDeclaredField("dexElements")
                    .also { it.isAccessible = true }
                val bootstrapElements = dexElementsField.get(tmpPathList) as? Array<*>
                    ?: return@synchronized

                // Merge: bootstrap elements first, then existing app elements.
                // Bootstrap wins on class-name collision because it's prepended
                // to the array.
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
                    bootstrapElements.javaClass.componentType!!,
                    bootstrapElements.size + appElements.size
                )
                System.arraycopy(bootstrapElements, 0, merged, 0, bootstrapElements.size)
                System.arraycopy(appElements, 0, merged, bootstrapElements.size, appElements.size)
                appElementsField.set(appPathList, merged)
            }
        }
    }
}