package com.opentune.genart

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

    private val FALLBACK_JPEG: ByteArray = run {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 50, out)
            out.toByteArray()
        }
    }

    /** Returns a 1×1 gray JPEG used for failed/missing covers. */
    fun fallback(): ByteArray = FALLBACK_JPEG.copyOf()

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
