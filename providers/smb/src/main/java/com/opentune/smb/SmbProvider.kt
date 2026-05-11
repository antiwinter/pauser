package com.opentune.smb

import com.opentune.provider.CodecCapabilities
import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.PlatformContext
import com.opentune.provider.ServerFieldKind
import com.opentune.provider.ServerFieldSpec
import com.opentune.provider.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class SmbProvider : OpenTuneProvider {

    @Volatile private var subtitleCacheDir: File = File("")

    override val providerType: String = PROVIDER_TYPE
    override val providesCover: Boolean = false

    override fun bootstrap(context: PlatformContext) {
        subtitleCacheDir = File(context.cacheDir, "opentune_subtitles")
    }
    override fun getFieldsSpec(): List<ServerFieldSpec> = listOf(
        ServerFieldSpec(
            id = "host",
            labelKey = "fld_network_host",
            kind = ServerFieldKind.SingleLineText,
            required = true,
            order = 0,
        ),
        ServerFieldSpec(
            id = "share_name",
            labelKey = "fld_share_name",
            kind = ServerFieldKind.SingleLineText,
            required = true,
            order = 1,
        ),
        ServerFieldSpec(
            id = "username",
            labelKey = "fld_account_username",
            kind = ServerFieldKind.SingleLineText,
            required = true,
            order = 2,
        ),
        ServerFieldSpec(
            id = "password",
            labelKey = "fld_account_password",
            kind = ServerFieldKind.Password,
            required = true,
            sensitive = true,
            order = 3,
        ),
        ServerFieldSpec(
            id = "domain",
            labelKey = "fld_domain_optional",
            kind = ServerFieldKind.SingleLineText,
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
                val fields = SmbServerFieldsJson(
                    host = host,
                    shareName = shareName,
                    username = username,
                    password = password,
                    domain = domain,
                )
                val hash = sha256("$host$shareName")
                ValidationResult.Success(
                    hash = hash,
                    displayName = shareName,
                    fieldsJson = SmbServerFieldsJson.encode(fields),
                )
            } catch (e: Exception) {
                ValidationResult.Error(e.message ?: "SMB validation failed")
            }
        }

    override fun createInstance(values: Map<String, String>, capabilities: CodecCapabilities): OpenTuneProviderInstance {
        val fields = SmbServerFieldsJson(
            host = values["host"] ?: error("Missing host"),
            shareName = values["share_name"] ?: error("Missing share_name"),
            username = values["username"] ?: error("Missing username"),
            password = values["password"] ?: error("Missing password"),
            domain = values["domain"],
        )
        return SmbProviderInstance(fields = fields, subtitleCacheDir = subtitleCacheDir)
    }

    companion object {
        const val PROVIDER_TYPE = "smb"

        private fun sha256(s: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
