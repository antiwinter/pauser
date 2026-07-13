package com.insomnia.provider.js

import android.content.Context
import android.content.res.AssetManager
import com.insomnia.content.contract.InsomniaProvider
import com.insomnia.content.contract.InsomniaProviderLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

class JsProviderLoader : InsomniaProviderLoader {

    override suspend fun load(register: (InsomniaProvider) -> Unit) {
        val ctx = ContextHolder.get()
        val assetFolders = withContext(Dispatchers.IO) {
            assetFolders(ctx.assets)
        }
        for (folderName in assetFolders) {
            val registered = loadOne(folderName, ctx, register)
            if (!registered) {
                Timber.e("JsProviderLoader: skipping provider folder '%s' (missing or invalid meta.json / index.js)", folderName)
            }
        }
    }

    private suspend fun loadOne(
        folderName: String,
        ctx: Context,
        register: (InsomniaProvider) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val meta = readProviderMeta(ctx.assets, folderName) ?: return@withContext false
        try {
            validateMeta(meta, folderName)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "JsProviderLoader: invalid meta.json for '%s'", folderName)
            return@withContext false
        }
        injectCoLocatedJars(ctx.assets, folderName, ctx)
        val indexJs = readIndexJs(ctx.assets, folderName) ?: return@withContext false
        val sandboxRoot = File(ctx.cacheDir, "providers/$folderName").apply { mkdirs() }
        val hostApis = HostApis(folderName, sandboxRoot)
        val provider = JsProvider.create(meta, indexJs, hostApis)
        register(provider)
        true
    }

    /**
     * Lists top-level folders under `assets/`. A folder is detected by the fact that
     * `assets.list(name)` returns a non-empty list of children — `assets.list("")`
     * returns mixed names (files + folders) and there's no direct "is directory"
     * probe on an `AssetManager`, so we use the presence of children as the signal.
     */
    private fun assetFolders(assets: AssetManager): List<String> {
        val topLevel = assets.list("") ?: return emptyList()
        return topLevel.filter { name ->
            // Skip loose files (e.g. flat-layout stragglers like an old `catvod.js`).
            // A folder is anything with at least one child.
            !name.endsWith(".js") && !name.endsWith(".jar") && !name.endsWith(".json") &&
                (assets.list(name)?.isNotEmpty() == true)
        }
    }

    private fun readProviderMeta(assets: AssetManager, folderName: String): ProviderMeta? = try {
        val path = "$folderName/meta.json"
        val text = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        META_JSON.decodeFromString(ProviderMeta.serializer(), text)
    } catch (e: Exception) {
        Timber.w(e, "readProviderMeta(%s) failed", folderName)
        null
    }

    private fun validateMeta(meta: ProviderMeta, name: String) {
        require(meta.protocol.isNotBlank())   { "meta.protocol empty for $name" }
        require(meta.displayName.isNotBlank()) { "meta.displayName empty for $name" }
        require(meta.version.isNotBlank())    { "meta.version empty for $name" }
        val allowedKinds = setOf("text", "singleLine", "password", "proxySelector", "qrCode")
        meta.fieldSpec.forEach { f ->
            require(f.id.isNotBlank())  { "fieldSpec.id empty in $name" }
            require(f.kind in allowedKinds) {
                "fieldSpec.kind '${f.kind}' for field '${f.id}' in $name is not one of $allowedKinds"
            }
        }
    }

    /**
     * Auto-injects every `.jar` co-located in the provider's folder into the app
     * classloader. Convention over configuration: a provider that doesn't want a jar
     * fused doesn't put one in the folder. Source bytes are copied straight from
     * `assets/<provider>/<jar>` into `codeCacheDir/jars/asset_<safe>.jar` — the host
     * never publishes anything into the sandbox (rule #5).
     */
    private fun injectCoLocatedJars(assets: AssetManager, folderName: String, ctx: Context) {
        val children = assets.list(folderName) ?: return
        for (child in children) {
            if (!child.endsWith(".jar")) continue
            val dest = File(stagingDir(ctx), "asset_${sanitize(child)}.jar")
            if (dest.exists()) continue
            assets.open("$folderName/$child").use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            }
            dest.setReadOnly()
            ClassPathInjector.inject(ctx, dest)
        }
    }

    private fun stagingDir(ctx: Context): File {
        val dir = File(ctx.codeCacheDir, "jars").apply { mkdirs() }
        DexFilePermissions.chmodForDex(dir)
        DexFilePermissions.chmodForDex(ctx.codeCacheDir)
        return dir
    }

    private fun sanitize(name: String): String = name.replace(':', '_').replace('/', '_')

    private fun readIndexJs(assets: AssetManager, folderName: String): String? = try {
        assets.open("$folderName/index.js").use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        Timber.w(e, "readIndexJs(%s) failed", folderName)
        null
    }

    private companion object {
        val META_JSON = Json { ignoreUnknownKeys = false; isLenient = false }
    }
}
