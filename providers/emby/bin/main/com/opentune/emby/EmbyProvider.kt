package com.opentune.emby

import com.opentune.emby.dto.AuthenticateByNameRequest
import com.opentune.emby.dto.CodecProfile
import com.opentune.emby.dto.DeviceIdentification
import com.opentune.emby.dto.DeviceProfile
import com.opentune.emby.dto.DirectPlayProfile
import com.opentune.emby.dto.ProfileCondition
import com.opentune.emby.dto.ResponseProfile
import com.opentune.emby.dto.SubtitleProfile
import com.opentune.emby.dto.TranscodingProfile
import com.opentune.provider.CodecCapabilities
import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.PlatformContext
import com.opentune.provider.ServerFieldKind
import com.opentune.provider.ServerFieldSpec
import com.opentune.provider.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.math.sqrt

class EmbyProvider : OpenTuneProvider {

    @Volatile private var deviceName: String = "Android TV"

    override val protocol: String = "emby-kt"
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

    override fun createInstance(values: Map<String, String>, capabilities: CodecCapabilities): OpenTuneProviderInstance {
        val fields = EmbyServerFieldsJson(
            baseUrl = values["base_url"] ?: error("Missing base_url"),
            userId = values["user_id"] ?: error("Missing user_id"),
            accessToken = values["access_token"] ?: error("Missing access_token"),
            serverId = values["server_id"]?.ifEmpty { null },
        )
        return EmbyProviderInstance(fields = fields, deviceProfile = buildDeviceProfile(capabilities), capabilities = capabilities)
    }

    override fun bootstrap(context: PlatformContext) {
        deviceName = context.deviceName
        EmbyClientIdentificationStore.install(
            EmbyClientIdentification(
                clientName = "OpenTune",
                deviceName = context.deviceName,
                deviceId = context.deviceId,
                clientVersion = context.clientVersion,
            ),
        )
    }

    private fun buildDeviceProfile(caps: CodecCapabilities): DeviceProfile {
        val videoCodecCsv = caps.supportedVideoMimeTypes
            .mapNotNull { mimeToEmbyVideoCodec(it) }.distinct().joinToString(",")
        val audioCodecCsv = caps.supportedAudioMimeTypes
            .mapNotNull { mimeToEmbyAudioCodec(it) }.distinct().joinToString(",")

        val maxPixels = caps.maxVideoPixels.coerceAtLeast(1920 * 1080)
        val maxWidth = sqrtApprox(maxPixels, 16) * 16
        val maxHeight = (maxPixels / maxWidth).coerceAtLeast(1080)

        val videoConditions = listOf(
            ProfileCondition(condition = "LessThanEqual", property = "VideoBitrate", value = "120000000", isRequired = false),
            ProfileCondition(condition = "LessThanEqual", property = "Width", value = maxWidth.toString(), isRequired = false),
            ProfileCondition(condition = "LessThanEqual", property = "Height", value = maxHeight.toString(), isRequired = false),
        )

        val codecProfiles = buildList {
            if (videoCodecCsv.isNotEmpty()) add(CodecProfile(type = "Video", codec = videoCodecCsv, conditions = videoConditions))
            if (audioCodecCsv.isNotEmpty()) add(CodecProfile(type = "Audio", codec = audioCodecCsv, conditions = emptyList()))
        }

        val v = videoCodecCsv.ifBlank { "h264" }
        val a = audioCodecCsv.ifBlank { "aac" }
        val model = deviceName

        return DeviceProfile(
            name = "OpenTune Android TV",
            identification = DeviceIdentification(
                friendlyName = "OpenTune",
                manufacturer = "OpenTune",
                modelName = model,
                deviceDescription = "OpenTune on $model",
            ),
            friendlyName = "OpenTune",
            manufacturer = "OpenTune",
            modelName = model,
            directPlayProfiles = listOf(
                DirectPlayProfile(container = "mp4,mkv,avi,m4v,mov,webm", type = "Video", videoCodec = v, audioCodec = a),
            ),
            transcodingProfiles = listOf(
                TranscodingProfile(container = "ts", type = "Video", videoCodec = "h264", audioCodec = "aac", protocol = "hls", context = "Streaming"),
            ),
            codecProfiles = codecProfiles,
            subtitleProfiles = caps.supportedSubtitleFormats.map { SubtitleProfile(format = it) },
            responseProfiles = listOf(
                ResponseProfile(type = "Video", container = "m3u8", mimeType = "application/vnd.apple.mpegurl"),
            ),
        )
    }

    companion object {

        private fun sqrtApprox(n: Int, align: Int): Int {
            var w = sqrt(n.toDouble()).toInt() / align * align
            if (w < 1) w = 1920
            return w
        }

        private fun mimeToEmbyVideoCodec(mime: String): String? = when (mime) {
            "video/avc" -> "h264"
            "video/hevc" -> "hevc"
            "video/vp9" -> "vp9"
            "video/av01" -> "av1"
            else -> null
        }

        private fun mimeToEmbyAudioCodec(mime: String): String? = when (mime) {
            "audio/mp4a-latm" -> "aac"
            "audio/ac3" -> "ac3"
            "audio/eac3" -> "eac3"
            "audio/mpeg" -> "mp3"
            "audio/opus" -> "opus"
            "audio/flac" -> "flac"
            else -> null
        }

        internal fun sha256(s: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
