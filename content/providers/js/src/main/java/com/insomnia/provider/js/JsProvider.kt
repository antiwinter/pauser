package com.insomnia.provider.js

import com.insomnia.content.contract.InsomniaProvider
import com.insomnia.content.contract.EndpointClient
import com.insomnia.core.form.contract.FormFieldKind
import com.insomnia.core.form.contract.FormFieldSpec
import kotlinx.serialization.json.Json
import com.insomnia.proxy.contract.WrappedProxyClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An [InsomniaProvider] backed by a JavaScript bundle running inside QuickJS.
 *
 * The JS bundle must export `globalThis.insomniaProvider` conforming to the
 * bridge protocol defined in `providers-ts/utils/types.ts`.
 *
 * Construct via [create] — the suspend factory evaluates the bundle once to
 * read [providesArt] and [getFieldsSpec] without blocking any thread.
 */
class JsProvider private constructor(
    private val assetPath: String,
    private val jsBundle: String,
    private val hostApis: HostApis,
    override val providesArt: Boolean,
    private val cachedFieldsSpec: List<FormFieldSpec>,
) : InsomniaProvider {

    override val protocol: String = assetPath.removeSuffix(".js")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── Field spec ─────────────────────────────────────────────────────────

    override fun getFieldsSpec(): List<FormFieldSpec> = cachedFieldsSpec

    // ── Client creation ──────────────────────────────────────────────────

    override fun createClient(values: Map<String, String>): EndpointClient {
        val deviceInfo = com.insomnia.player.PlatformInfo.detect(ContextHolder.get())
        return JsClient(
            protocol = protocol,
            jsBundle = jsBundle,
            hostApis = hostApis,
            values = values,
            deviceInfo = deviceInfo,
        )
    }

    companion object {
        /**
         * Evaluates the bundle in a temporary engine to read [providesArt] and
         * [getFieldsSpec], then constructs and returns a ready [JsProvider].
         * Runs on whatever dispatcher the caller is on — call from [Dispatchers.IO].
         */
        suspend fun create(assetPath: String, jsBundle: String, hostApis: HostApis): JsProvider {
            var cover = false
            var fields: List<FormFieldSpec> = emptyList()
            val engine = QuickJsEngine(hostApis, WrappedProxyClient(null).getHttpClient())
            try {
                engine.init()
                engine.evalSnippet(HOST_BOOTSTRAP_JS)
                engine.evalBundle(jsBundle)
                cover = engine.evalExpression("globalThis.insomniaProvider.providesArt") == "true"
                val result = engine.callMethod("getFieldsSpec", "{}") ?: ""
                fields = parseFieldsSpec(result)
            } finally {
                engine.close()
            }
            return JsProvider(assetPath, jsBundle, hostApis, cover, fields)
        }

        private fun parseFieldsSpec(json: String): List<FormFieldSpec> {
            val serializer = Json { ignoreUnknownKeys = true; isLenient = true }
            return try {
                val arr = serializer.parseToJsonElement(json).jsonArray
                arr.mapNotNull { el ->
                    val obj = el.jsonObject
                    val id  = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val lbl = obj["labelKey"]?.jsonPrimitive?.content ?: id
                    val kind = when (obj["kind"]?.jsonPrimitive?.content) {
                        "password"      -> FormFieldKind.Password
                        "singleLine"    -> FormFieldKind.SingleLineText
                        "proxySelector" -> FormFieldKind.ProxySelector
                        "qrCode"        -> FormFieldKind.QrCode
                        else            -> FormFieldKind.Text
                    }
                    FormFieldSpec(
                        id             = id,
                        labelKey       = lbl,
                        kind           = kind,
                        required       = obj["required"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                        sensitive      = obj["sensitive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                        order          = obj["order"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        placeholderKey = obj["placeholderKey"]?.jsonPrimitive?.content,
                        identity       = obj["identity"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

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
    http:   ns('http'),
    crypto: ns('crypto'),
    jar:    ns('jar'),
    fs:     ns('fs'),
    log:    ns('log'),
    timer:  ns('timer'),
    dns:    ns('dns'),
    relay:  ns('relay'),
  };
  globalThis.console = {
    log: function() {
      host.log.d({ msg: Array.prototype.join.call(arguments, ' ') });
    },
    warn: function() {
      host.log.w({ msg: Array.prototype.join.call(arguments, ' ') });
    },
    error: function() {
      host.log.e({ msg: Array.prototype.join.call(arguments, ' ') });
    },
  };
  Object.defineProperty(globalThis.host, 'proxyConfig', {
    get: function() { return globalThis.__proxyConfig || null; },
    enumerable: true,
  });
})();
"""
    }
}
