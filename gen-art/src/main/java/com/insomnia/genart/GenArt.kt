package com.insomnia.genart

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.net.URL

object GenArt {

    /** Bump when the extraction algorithm changes to invalidate all cached URLs. */
    const val VERSION = "v1"

    private const val COVER_W = 300
    private const val COVER_H = 250
    private const val COVER_SHORT_EDGE = 300

    /** Minimal 1×1 transparent PNG (67 bytes). */
    private val TRANSPARENT_PNG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44,
        0x41, 0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00,
        0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    /** Returns a 1×1 transparent PNG used for failed/missing covers. */
    fun transparentPlaceholder(): ByteArray = TRANSPARENT_PNG.copyOf()

    fun generateCover(videoUrl: String, headers: Map<String, String>): ByteArray? {
        return try {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(videoUrl, headers)
                mmr.embeddedPicture
                    ?: run {
                        val durationMs = mmr.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION,
                        )?.toLongOrNull() ?: 0L
                        val bitmap = mmr.getFrameAtTime(
                            (durationMs * 1000L) / 3L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        )
                        bitmap?.resizeAndCropToCover()?.toJpegBytes()
                    }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Downloads an image from [url], resizes/crops it, and returns JPEG bytes. */
    fun downloadCover(url: String): ByteArray? {
        return try {
            val raw = URL(url).readBytes()
            BitmapFactory.decodeByteArray(raw, 0, raw.size, null)
                ?.resizeAndCropToCover()
                ?.toJpegBytes()
        } catch (_: Exception) {
            null
        }
    }

    private fun Bitmap.resizeAndCropToCover(): Bitmap {
        val srcW = width
        val srcH = height
        val scale = if (srcW <= srcH) COVER_SHORT_EDGE.toFloat() / srcW
        else COVER_SHORT_EDGE.toFloat() / srcH
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(this, scaledW, scaledH, true)

        val cropW = COVER_W.coerceAtMost(scaledW)
        val cropH = COVER_H.coerceAtMost(scaledH)
        val cropX = (scaledW - cropW) / 2
        val cropY = (scaledH - cropH) / 2
        val cropped = if (cropW == scaledW && cropH == scaledH) scaled
        else Bitmap.createBitmap(scaled, cropX, cropY, cropW, cropH)

        if (cropped !== scaled) scaled.recycle()
        return cropped
    }

    private fun Bitmap.toJpegBytes(): ByteArray {
        return ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, 85, out)
            recycle()
            out.toByteArray()
        }
    }
}
