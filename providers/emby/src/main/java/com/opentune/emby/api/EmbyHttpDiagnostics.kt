package com.opentune.emby.api

import android.util.Log
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import retrofit2.HttpException

private const val TAG = "OpenTuneEmby"

internal fun logUnsuccessfulEmbyResponse(method: String, url: HttpUrl, code: Int, responseMessage: String, bodySnippet: String) {
    // Use Log.e so lines show when Logcat log level is set to "Error" (common on TV debugging).
    Log.e(
        TAG,
        "HTTP $code $responseMessage for $method $url bodySnippet=${bodySnippet.take(8192)}",
    )
}

/** Short message for UI; includes server error body when present (e.g. Jellyfin plain-text errors). */
fun formatHttpExceptionForDisplay(e: HttpException): String {
    val line1 = e.message?.trim()?.takeIf { it.isNotEmpty() } ?: "HTTP ${e.code()}"
    val body = try {
        e.response()?.errorBody()?.string()?.trim()?.take(600)
    } catch (_: Throwable) {
        null
    }
    return if (body.isNullOrEmpty()) line1 else "$line1 — $body"
}

fun logEmbyHttpException(phase: String, e: HttpException) {
    val body = try {
        e.response()?.errorBody()?.string()?.take(8192)
    } catch (t: Throwable) {
        "(errorBody failed: ${t.message})"
    }
    Log.e(
        TAG,
        "phase=$phase code=${e.code()} url=${e.response()?.raw()?.request?.url} responseMessage=${e.response()?.message()} body=$body",
        e,
    )
}

fun logEmbySerializationFailure(phase: String, e: SerializationException) {
    Log.e(TAG, "phase=$phase JSON parse failed: ${e.message}", e)
}

suspend fun <T> runEmbyHttpPhase(phase: String, block: suspend () -> T): T =
    try {
        block()
    } catch (e: HttpException) {
        logEmbyHttpException(phase, e)
        throw e
    } catch (e: SerializationException) {
        logEmbySerializationFailure(phase, e)
        throw e
    }
