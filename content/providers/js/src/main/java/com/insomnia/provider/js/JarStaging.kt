package com.insomnia.provider.js

import android.content.Context
import java.io.File

/**
 * Shared staging directory and filename sanitization for JAR files.
 * Used by [JarLoader] (downloaded/buffered JARs) and [JsProviderLoader]
 * (co-located asset JARs) — both stage to the same `codeCacheDir/jars/`
 * with identical permission cleanup.
 */
internal object JarStaging {

    /** `codeCacheDir/jars/`. Android 13+ DexFile refuses jars under group/world-writable
     *  parents — strip those bits on the staging dir and its parent on first use per inode. */
    fun stageDir(ctx: Context): File {
        val dir = File(ctx.codeCacheDir, "jars").also { it.mkdirs() }
        DexFilePermissions.chmodForDex(dir)
        DexFilePermissions.chmodForDex(ctx.codeCacheDir)
        return dir
    }

    /** DexClassLoader splits its jar path on `:` (path-list separator). Handles like
     *  `path:abc123…` become two nonsense files. Translate the in-memory key to a colon-free
     *  filename for files on disk. */
    fun safeName(key: String): String =
        key.replace(':', '_').replace('/', '_')
}
