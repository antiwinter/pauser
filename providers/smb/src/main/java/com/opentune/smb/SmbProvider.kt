package com.opentune.smb

import com.opentune.provider.PlatformCapabilities
import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.EndpointClient
import com.opentune.provider.ProviderFieldKind
import com.opentune.provider.ProviderFieldSpec
import com.opentune.provider.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.security.MessageDigest

class SmbProvider : OpenTuneProvider {

    override val protocol: String = "smb"
    override val providesArt: Boolean = false
    override fun getFieldsSpec(): List<ProviderFieldSpec> = listOf(
        ProviderFieldSpec(
            id = "host",
            labelKey = "fld_network_host",
            kind = ProviderFieldKind.SingleLineText,
            required = true,
            order = 0,
        ),
        ProviderFieldSpec(
            id = "share_name",
            labelKey = "fld_share_name",
            kind = ProviderFieldKind.SingleLineText,
            required = true,
            order = 1,
        ),
        ProviderFieldSpec(
            id = "username",
            labelKey = "fld_account_username",
            kind = ProviderFieldKind.SingleLineText,
            required = true,
            order = 2,
        ),
        ProviderFieldSpec(
            id = "password",
            labelKey = "fld_account_password",
            kind = ProviderFieldKind.Password,
            required = true,
            sensitive = true,
            order = 3,
        ),
        ProviderFieldSpec(
            id = "domain",
            labelKey = "fld_domain_optional",
            kind = ProviderFieldKind.SingleLineText,
            required = false,
            order = 4,
        ),
    )

    override suspend fun validateFields(values: Map<String, String>, httpClient: okhttp3.OkHttpClient): ValidationResult =
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
                ValidationResult.Success(
                    hash = hash,
                    name = shareName,
                    fields = fields,
                )
            } catch (e: Exception) {
                ValidationResult.Error(e.message ?: "SMB validation failed")
            }
        }

    override fun createClient(values: Map<String, String>, capabilities: PlatformCapabilities, httpClient: OkHttpClient): EndpointClient {
        val fields = SmbServerFieldsJson(
            host = values["host"] ?: error("Missing host"),
            shareName = values["share_name"] ?: error("Missing share_name"),
            username = values["username"] ?: error("Missing username"),
            password = values["password"] ?: error("Missing password"),
            domain = values["domain"],
        )
        return SmbProviderInstance(fields = fields, httpClient = httpClient)
    }

    companion object {
        private fun sha256(s: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
