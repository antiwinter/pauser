package com.opentune.provider.js

import com.opentune.provider.PlatformCapabilities
import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.ServerFieldSpec
import com.opentune.provider.ValidationResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * An [OpenTuneProvider] backed by a JavaScript bundle running inside QuickJS.
 *
 * The JS bundle must export `globalThis.opentuneProvider` conforming to the
 * bridge protocol defined in `providers-ts/utils/types.ts`.
 *
 * @param assetPath  The asset filename of the JS bundle (e.g. "emby.js").
 *                   The [protocol] is derived by stripping the ".js" extension.
 * @param jsBundle   The full IIFE JavaScript bundle source code.
 * @param hostApis   Host API implementations (http, crypto, config).
 */
class JsProvider(
    private val assetPath: String,
    private val jsBundle: String,
    private val hostApis: HostApis,
) : OpenTuneProvider {

    override val protocol: String = assetPath.removeSuffix(".js")

    override val providesCover: Boolean by lazy {
        runWithEngine { engine ->
            engine.evalExpression("globalThis.opentuneProvider.providesCover") == "true"
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }


    // ── Field spec ─────────────────────────────────────────────────────────

    override fun getFieldsSpec(): List<ServerFieldSpec> {
        /* Synchronously call the JS bundle in a temporary engine to get field specs. */
        return runWithEngine { engine ->
            val result = engine.callMethod("getFieldsSpec", "{}") ?: return@runWithEngine emptyList()
            parseFieldsSpec(result)
        }
    }

    // ── Validation ─────────────────────────────────────────────────────────

    override suspend fun validateFields(values: Map<String, String>): ValidationResult {
        return try {
            val argsJson = buildJsonObject {
                put("values", buildJsonObject { values.forEach { (k, v) -> put(k, v) } })
            }.toString()
            val resultJson = withEngine { engine ->
                engine.callMethod("validateFields", argsJson)
            } ?: return ValidationResult.Error("Validation returned null")

            val obj = json.parseToJsonElement(resultJson).jsonObject
            val success = obj["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            if (success) {
                val fieldsEl = obj["fields"] ?: return ValidationResult.Error("Missing fields in validation response")
                val fieldsObj = fieldsEl.jsonObject
                val fields = fieldsObj.mapValues { (_, v) -> v.jsonPrimitive.content }
                ValidationResult.Success(
                    hash = obj["hash"]?.jsonPrimitive?.content ?: "",
                    name = obj["name"]?.jsonPrimitive?.content ?: protocol,
                    fields = fields,
                )
            } else {
                ValidationResult.Error(obj["error"]?.jsonPrimitive?.content ?: "Validation failed")
            }
        } catch (e: Exception) {
            ValidationResult.Error(e.message ?: "JS validation error")
        }
    }

    // ── Instance creation ──────────────────────────────────────────────────

    override fun createInstance(values: Map<String, String>, capabilities: PlatformCapabilities): OpenTuneProviderInstance {
        return JsProviderInstance(
            protocol = protocol,
            jsBundle     = jsBundle,
            hostApis     = hostApis,
            values       = values,
            capabilities = capabilities,
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun <T> runWithEngine(block: suspend (QuickJsEngine) -> T): T {
        val engine = QuickJsEngine(hostApis)
        return try {
            kotlinx.coroutines.runBlocking {
                engine.init()
                installHostApis(engine)
                engine.evalBundle(jsBundle)
                block(engine)
            }
        } finally {
            engine.close()
        }
    }

    private suspend fun <T> withEngine(block: suspend (QuickJsEngine) -> T): T {
        val engine = QuickJsEngine(hostApis)
        return try {
            engine.init()
            installHostApis(engine)
            engine.evalBundle(jsBundle)
            block(engine)
        } finally {
            engine.close()
        }
    }

    private suspend fun installHostApis(engine: QuickJsEngine) {
        engine.evalSnippet(HOST_BOOTSTRAP_JS)
    }

    private fun parseFieldsSpec(json: String): List<ServerFieldSpec> {
        return try {
            val arr = this.json.parseToJsonElement(json).jsonArray
            arr.mapNotNull { el ->
                val obj = el.jsonObject
                val id  = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val lbl = obj["labelKey"]?.jsonPrimitive?.content ?: id
                val kind = when (obj["kind"]?.jsonPrimitive?.content) {
                    "password" -> com.opentune.provider.ServerFieldKind.Password
                    "singleLine" -> com.opentune.provider.ServerFieldKind.SingleLineText
                    else         -> com.opentune.provider.ServerFieldKind.Text
                }
                ServerFieldSpec(
                    id           = id,
                    labelKey     = lbl,
                    kind         = kind,
                    required     = obj["required"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                    sensitive    = obj["sensitive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    order        = obj["order"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    placeholderKey = obj["placeholderKey"]?.jsonPrimitive?.content,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        /**
         * JS snippet evaluated after bundle load to install `globalThis.host.*`
         * namespace objects that delegate to `globalThis.__hostDispatch`.
         */
        const val HOST_BOOTSTRAP_JS = """
(function() {
  function ns(name) {
    return new Proxy({}, {
      get: function(_, prop) {
        return function(args) {
          return globalThis.__hostDispatch(name, prop, JSON.stringify(args === undefined ? null : args));
        };
      }
    });
  }
  globalThis.host = {
    http:     ns('http'),
    crypto:   ns('crypto'),
    platform: ns('platform'),
  };
})();
"""
    }
}
