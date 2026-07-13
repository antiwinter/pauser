package com.insomnia.provider.js

import com.insomnia.content.contract.InsomniaProvider
import com.insomnia.content.contract.EndpointClient
import com.insomnia.core.form.contract.FormFieldKind
import com.insomnia.core.form.contract.FormFieldSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.insomnia.proxy.contract.WrappedProxyClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An [InsomniaProvider] backed by a JavaScript bundle running inside QuickJS.
 *
 * Static surface (protocol, providesArt, version, displayName, fieldSpec) comes from
 * a [ProviderMeta] loaded from the provider's `meta.json` — the single source of
 * truth. The JS bundle's dynamic surface (init/test/listEntry/etc.) is reached via
 * the host bridge and is no longer described in the bundle.
 */
class JsProvider private constructor(
    private val meta: ProviderMeta,
    private val jsBundle: String,
    private val hostApis: HostApis,
) : InsomniaProvider {

    override val protocol: String = meta.protocol

    override val providesArt: Boolean = meta.providesArt

    // ── Field spec ─────────────────────────────────────────────────────────

    override fun getFieldsSpec(): List<FormFieldSpec> = parseFieldsSpec(meta.fieldSpec)

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
         * Constructs a [JsProvider] from already-parsed manifest + bundle source.
         * Runs on whatever dispatcher the caller is on — call from [Dispatchers.IO].
         */
        suspend fun create(meta: ProviderMeta, jsBundle: String, hostApis: HostApis): JsProvider {
            return JsProvider(meta, jsBundle, hostApis)
        }

        /** Translate a [FieldSpecDto] array into the host's [FormFieldSpec] model. */
        private fun parseFieldsSpec(dtos: List<FieldSpecDto>): List<FormFieldSpec> =
            dtos.map { dto ->
                val kind = when (dto.kind) {
                    "password"      -> FormFieldKind.Password
                    "singleLine"    -> FormFieldKind.SingleLineText
                    "proxySelector" -> FormFieldKind.ProxySelector
                    "qrCode"        -> FormFieldKind.QrCode
                    else            -> FormFieldKind.Text
                }
                FormFieldSpec(
                    id             = dto.id,
                    labelKey       = dto.labelKey ?: dto.id,
                    kind           = kind,
                    required       = dto.required ?: true,
                    sensitive      = dto.sensitive ?: false,
                    order          = dto.order ?: 0,
                    placeholderKey = dto.placeholderKey,
                    identity       = dto.identity ?: false,
                )
            }
    }
}

/**
 * Provider static manifest loaded from `assets/<provider>/meta.json`. The single
 * source of truth for protocol, providesArt, version, displayName, and fieldSpec —
 * no fallback to `globalThis.insomniaProvider`. All fields required; the loader
 * validates and rejects on any absence or wrong type.
 */
@Serializable
data class ProviderMeta(
    val protocol: String,
    val providesArt: Boolean,
    val version: String,
    val displayName: String,
    val fieldSpec: List<FieldSpecDto>,
)

@Serializable
data class FieldSpecDto(
    val id: String,
    val kind: String,
    val labelKey: String? = null,
    val required: Boolean? = null,
    val sensitive: Boolean? = null,
    val order: Int? = null,
    val placeholderKey: String? = null,
    val identity: Boolean? = null,
)
