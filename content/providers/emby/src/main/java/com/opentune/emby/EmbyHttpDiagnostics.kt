package com.opentune.emby

import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import retrofit2.HttpException
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("OpenTuneEmby")

internal fun logUnsuccessfulEmbyResponse(method: String, url: HttpUrl, code: Int, responseMessage: String, bodySnippet: String) {
    logger.severe("HTTP $code $responseMessage for $method $url bodySnippet=${bodySnippet.take(8192)}")
}

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
    logger.severe("phase=$phase code=${e.code()} url=${e.response()?.raw()?.request?.url} responseMessage=${e.response()?.message()} body=$body")
}

fun logEmbySerializationFailure(phase: String, e: SerializationException) {
    logger.severe("phase=$phase JSON parse failed: ${e.message}")
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
