package com.opentune.emby.api

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.opentune.emby.api.dto.AuthenticateByNameRequest
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.ServerFieldKind
import com.opentune.provider.ServerFieldSpec
import com.opentune.provider.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class EmbyProvider(
    private val deviceProfile: DeviceProfile,
) : OpenTuneProvider {

    @Volatile private var appContext: Context? = null

    override val providerType: String = PROVIDER_TYPE

    override val providesCover: Boolean = true

    override fun getFieldsSpec(): List<ServerFieldSpec> = listOf(
        ServerFieldSpec(
            id = "base_url",
            labelKey = "fld_http_library_url",
            kind = ServerFieldKind.SingleLineText,
            required = true,
            order = 0,
            placeholderKey = "ph_http_library_url",
        ),
        ServerFieldSpec(
            id = "username",
            labelKey = "fld_account_username",
            kind = ServerFieldKind.SingleLineText,
            required = true,
            order = 1,
        ),
        ServerFieldSpec(
            id = "password",
            labelKey = "fld_account_password",
            kind = ServerFieldKind.Password,
            required = true,
            sensitive = true,
            order = 2,
        ),
    )

    override suspend fun validateFields(values: Map<String, String>): ValidationResult =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = EmbyClientFactory.normalizeBaseUrl(values["base_url"]?.trim().orEmpty())
                val username = values["username"]?.trim().orEmpty()
                val password = values["password"].orEmpty()
                val unauth: EmbyApi = EmbyClientFactory.create(baseUrl, accessToken = null)
                val auth = runEmbyHttpPhase("authenticateByName") {
                    unauth.authenticateByName(AuthenticateByNameRequest(username, password))
                }
                val token = auth.accessToken ?: error("No access token")
                val userId = auth.user?.id ?: error("No user id")
                val api = EmbyClientFactory.create(baseUrl, token)
                val info = runEmbyHttpPhase("getSystemInfo") { api.getSystemInfo() }
                val hash = sha256("$baseUrl$userId")
                val displayName = info.serverName ?: baseUrl
                val fieldsJson = EmbyServerFieldsJson.encode(
                    EmbyServerFieldsJson(
                        baseUrl = baseUrl,
                        userId = userId,
                        accessToken = token,
                        serverId = info.id,
                    ),
                )
                ValidationResult.Success(hash = hash, displayName = displayName, fieldsJson = fieldsJson)
            } catch (e: Exception) {
                ValidationResult.Error(e.message ?: "Emby validation failed")
            }
        }

    override fun createInstance(values: Map<String, String>): OpenTuneProviderInstance {
        val context = appContext ?: run {
            Log.e(LOG_TAG, "createInstance: appContext is null — bootstrap was not called")
            error("EmbyProvider not bootstrapped")
        }
        val fields = EmbyServerFieldsJson(
            baseUrl = values["base_url"] ?: error("Missing base_url"),
            userId = values["user_id"] ?: error("Missing user_id"),
            accessToken = values["access_token"] ?: error("Missing access_token"),
            serverId = values["server_id"]?.ifEmpty { null },
        )
        Log.d(LOG_TAG, "createInstance: serverId=${fields.serverId}, baseUrl=${fields.baseUrl.take(30)}")
        return EmbyProviderInstance(fields = fields, deviceProfile = deviceProfile, context = context)
    }

    override fun bootstrap(context: Context) {
        val appContext = context.applicationContext
        this.appContext = appContext
        Log.d(LOG_TAG, "bootstrap: context=${appContext.packageName}")
        EmbyClientIdentificationStore.install(
            EmbyClientIdentification(
                clientName = "OpenTune",
                deviceName = Build.MODEL.ifBlank { "Android" },
                deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString(),
                clientVersion = try {
                    @Suppress("DEPRECATION")
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0"
                } catch (_: Exception) {
                    "0"
                },
            ),
        )
    }

    companion object {
        const val PROVIDER_TYPE = "emby"
        private const val LOG_TAG = "OT_EmbyProvider"

        internal fun sha256(s: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
