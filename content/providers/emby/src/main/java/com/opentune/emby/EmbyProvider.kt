package com.opentune.emby

import com.opentune.emby.dto.CodecProfile
import com.opentune.emby.dto.DeviceIdentification
import com.opentune.emby.dto.DeviceProfile
import com.opentune.emby.dto.DirectPlayProfile
import com.opentune.emby.dto.ProfileCondition
import com.opentune.emby.dto.ResponseProfile
import com.opentune.emby.dto.SubtitleProfile
import com.opentune.emby.dto.TranscodingProfile
import com.opentune.content.contract.PlatformCapabilities
import com.opentune.content.contract.OpenTuneProvider
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.PlatformInfoHolder
import com.opentune.core.form.contract.FormFieldKind
import com.opentune.core.form.contract.FormFieldSpec
import java.security.MessageDigest
import kotlin.math.sqrt

class EmbyProvider : OpenTuneProvider {

    override val protocol: String = "emby-kt"
    override val providesArt: Boolean = true

    override fun getFieldsSpec(): List<FormFieldSpec> = listOf(
        FormFieldSpec(
            id = "base_url",
            labelKey = "fld_http_library_url",
            kind = FormFieldKind.SingleLineText,
            required = true,
            identity = true,
            order = 0,
            placeholderKey = "ph_http_library_url",
        ),
        FormFieldSpec(
            id = "username",
            labelKey = "fld_account_username",
            kind = FormFieldKind.SingleLineText,
            required = true,
            identity = true,
            order = 1,
        ),
        FormFieldSpec(
            id = "password",
            labelKey = "fld_account_password",
            kind = FormFieldKind.Password,
            required = true,
            sensitive = true,
            order = 2,
        ),
        FormFieldSpec(id = "proxy", labelKey = "", kind = FormFieldKind.ProxySelector, order = Int.MAX_VALUE),
        FormFieldSpec(id = "name", labelKey = "fld_endpoint_name", kind = FormFieldKind.SingleLineText, required = false, order = 100),
    )

    override fun createClient(values: Map<String, String>, capabilities: PlatformCapabilities): EndpointClient {
        val fields = EmbyServerFieldsJson(
            baseUrl = values["base_url"] ?: error("Missing base_url"),
            userId = values["user_id"] ?: error("Missing user_id"),
            accessToken = values["access_token"] ?: error("Missing access_token"),
            serverId = values["server_id"]?.ifEmpty { null },
        )
        val info = PlatformInfoHolder.get()
        EmbyClientIdentificationStore.install(
            EmbyClientIdentification(
                clientName = "OpenTune",
                deviceName = info.deviceName,
                deviceId = info.deviceId,
                clientVersion = info.clientVersion,
            ),
        )
        return EmbyProviderInstance(fields = fields, deviceProfile = buildDeviceProfile(capabilities), capabilities = capabilities)
    }

    private fun buildDeviceProfile(caps: PlatformCapabilities): DeviceProfile {
        val videoCodecCsv = caps.videoMime
            .mapNotNull { mimeToEmbyVideoCodec(it) }.distinct().joinToString(",")
        val audioCodecCsv = caps.audioMime
            .mapNotNull { mimeToEmbyAudioCodec(it) }.distinct().joinToString(",")

        val maxPixels = caps.maxPixels.coerceAtLeast(1920 * 1080)
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
        val model = PlatformInfoHolder.get().deviceName

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
            subtitleProfiles = caps.subtitleFormats.map { SubtitleProfile(format = it) },
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
