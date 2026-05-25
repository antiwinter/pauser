package com.opentune.smb

import com.opentune.content.contract.PlatformCapabilities
import com.opentune.content.contract.OpenTuneProvider
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.FormFieldKind
import com.opentune.content.contract.FormFieldSpec
import com.opentune.content.contract.EndpointValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class SmbProvider : OpenTuneProvider {

    override val protocol: String = "smb"
    override val providesArt: Boolean = false
    override fun getFieldsSpec(): List<FormFieldSpec> = listOf(
        FormFieldSpec(
            id = "host",
            labelKey = "fld_network_host",
            kind = FormFieldKind.SingleLineText,
            required = true,
            order = 0,
        ),
        FormFieldSpec(
            id = "share_name",
            labelKey = "fld_share_name",
            kind = FormFieldKind.SingleLineText,
            required = true,
            order = 1,
        ),
        FormFieldSpec(
            id = "username",
            labelKey = "fld_account_username",
            kind = FormFieldKind.SingleLineText,
            required = true,
            order = 2,
        ),
        FormFieldSpec(
            id = "password",
            labelKey = "fld_account_password",
            kind = FormFieldKind.Password,
            required = true,
            sensitive = true,
            order = 3,
        ),
        FormFieldSpec(
            id = "domain",
            labelKey = "fld_domain_optional",
            kind = FormFieldKind.SingleLineText,
            required = false,
            order = 4,
        ),
        FormFieldSpec(id = "proxy", labelKey = "", kind = FormFieldKind.ProxySelector, order = Int.MAX_VALUE),
    )

    override suspend fun validateFields(values: Map<String, String>): EndpointValidationResult =
        withContext(Dispatchers.IO) {
            try {
                val host = values["host"]?.trim().orEmpty()
                val shareName = values["share_name"]?.trim().orEmpty()
                val username = values["username"].orEmpty()
                val password = values["password"].orEmpty()
                val domain = values["domain"]?.trim()?.ifBlank { null }
                val session = SmbSession.open(
                    SmbCredentials(
                        host = host,
                        shareName = shareName,
                        username = username,
                        password = password,
                        domain = domain,
                    ),
                )
                session.close()
                val hash = sha256("$host$shareName")
                val fields = buildMap {
                    put("host", host)
                    put("share_name", shareName)
                    put("username", username)
                    put("password", password)
                    domain?.let { put("domain", it) }
                }
                EndpointValidationResult.Success(
                    hash = hash,
                    name = shareName,
                    fields = fields,
                )
            } catch (e: Exception) {
                EndpointValidationResult.Error(e.message ?: "SMB validation failed")
            }
        }

    override fun createClient(values: Map<String, String>, capabilities: PlatformCapabilities): EndpointClient {
        val fields = SmbServerFieldsJson(
            host = values["host"] ?: error("Missing host"),
            shareName = values["share_name"] ?: error("Missing share_name"),
            username = values["username"] ?: error("Missing username"),
            password = values["password"] ?: error("Missing password"),
            domain = values["domain"],
        )
        return SmbProviderInstance(fields = fields)
    }

    companion object {
        private fun sha256(s: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
