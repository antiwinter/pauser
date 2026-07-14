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
import java.io.FileNotFoundException

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
     * Lists provider folders under `assets/js-providers/`. Scoping to this
     * subdirectory keeps the loader from tripping on unrelated asset folders
     * that happen to live at the top of `assets/`.
     */
    private fun assetFolders(assets: AssetManager): List<String> =
        assets.list(JS_PROVIDERS_DIR)?.toList().orEmpty()

    private fun readProviderMeta(assets: AssetManager, folderName: String): ProviderMeta? = try {
        val path = "$JS_PROVIDERS_DIR/$folderName/meta.json"
        val text = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        META_JSON.decodeFromString(ProviderMeta.serializer(), text)
    } catch (e: FileNotFoundException) {
        Timber.w("JsProviderLoader: '%s' is missing meta.json", folderName)
        null
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
     * `assets/js-providers/<provider>/<jar>` into `codeCacheDir/jars/asset_<safe>.jar`
     * — the host never publishes anything into the sandbox (rule #5).
     */
    private fun injectCoLocatedJars(assets: AssetManager, folderName: String, ctx: Context) {
        val children = assets.list("$JS_PROVIDERS_DIR/$folderName") ?: return
        for (child in children) {
            if (!child.endsWith(".jar")) continue
            val dest = File(JarStaging.stageDir(ctx), "asset_${JarStaging.safeName(child)}.jar")
            if (dest.exists()) continue
            assets.open("$JS_PROVIDERS_DIR/$folderName/$child").use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            }
            dest.setReadOnly()
            ClassPathInjector.inject(ctx, dest)
        }
    }

    private fun readIndexJs(assets: AssetManager, folderName: String): String? = try {
        assets.open("$JS_PROVIDERS_DIR/$folderName/index.js").use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: FileNotFoundException) {
        Timber.w("JsProviderLoader: '%s' is missing index.js", folderName)
        null
    } catch (e: Exception) {
        Timber.w(e, "readIndexJs(%s) failed", folderName)
        null
    }

    private companion object {
        const val JS_PROVIDERS_DIR = "js-providers"
        val META_JSON = Json { ignoreUnknownKeys = false; isLenient = false }
    }
}
