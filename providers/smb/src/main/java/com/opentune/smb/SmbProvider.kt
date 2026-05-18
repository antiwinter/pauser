package com.opentune.smb

import com.opentune.provider.PlatformCapabilities
import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.SourceFieldKind
import com.opentune.provider.SourceFieldSpec
import com.opentune.provider.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class SmbProvider : OpenTuneProvider {

    override val protocol: String = "smb"
    override val providesCover: Boolean = false
    override fun getFieldsSpec(): List<SourceFieldSpec> = listOf(
        SourceFieldSpec(
            id = "host",
            labelKey = "fld_network_host",
            kind = SourceFieldKind.SingleLineText,
            required = true,
            order = 0,
        ),
        SourceFieldSpec(
            id = "share_name",
            labelKey = "fld_share_name",
            kind = SourceFieldKind.SingleLineText,
            required = true,
            order = 1,
        ),
        SourceFieldSpec(
            id = "username",
            labelKey = "fld_account_username",
            kind = SourceFieldKind.SingleLineText,
            required = true,
            order = 2,
        ),
        SourceFieldSpec(
            id = "password",
            labelKey = "fld_account_password",
            kind = SourceFieldKind.Password,
            required = true,
            sensitive = true,
            order = 3,
        ),
        SourceFieldSpec(
            id = "domain",
            labelKey = "fld_domain_optional",
            kind = SourceFieldKind.SingleLineText,
            required = false,
            order = 4,
        ),
    )

    override suspend fun validateFields(values: Map<String, String>): ValidationResult =
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

    override fun createInstance(values: Map<String, String>, capabilities: PlatformCapabilities): OpenTuneProviderInstance {
        val fields = SmbSourceFieldsJson(
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
