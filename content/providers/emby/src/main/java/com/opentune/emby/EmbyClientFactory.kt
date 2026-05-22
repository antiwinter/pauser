package com.opentune.emby

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object EmbyClientFactory {

    private val jsonContent = "application/json".toMediaType()

    fun create(
        baseUrl: String,
        accessToken: String?,
        json: Json = embyJson(),
        connectTimeoutSec: Long = 30,
        readTimeoutSec: Long = 120,
        enableLogging: Boolean = false,
    ): EmbyApi {
        val normalized = normalizeBaseUrl(baseUrl)
        val client = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor(accessToken))
            .addInterceptor(loggingResponseInterceptor())
            .apply {
                if (enableLogging) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()
        return buildRetrofit(normalized, client, json)
    }

    fun create(
        client: OkHttpClient,
        baseUrl: String,
        accessToken: String?,
        json: Json = embyJson(),
    ): EmbyApi {
        val normalized = normalizeBaseUrl(baseUrl)
        val wrappedClient = client.newBuilder()
            .addInterceptor(authInterceptor(accessToken))
            .addInterceptor(loggingResponseInterceptor())
            .build()
        return buildRetrofit(normalized, wrappedClient, json)
    }

    private fun authInterceptor(accessToken: String?) = okhttp3.Interceptor { chain ->
        val ident = EmbyClientIdentificationStore.current()
        val mediaBrowser = ident.mediaBrowserAuthorizationHeader()
        val req = chain.request().newBuilder()
        req.header("Accept", "application/json")
        req.header("Authorization", mediaBrowser)
        req.header("X-Emby-Authorization", mediaBrowser)
        if (!accessToken.isNullOrBlank()) {
            req.header("X-Emby-Token", accessToken)
        }
        chain.proceed(req.build())
    }

    private fun loggingResponseInterceptor() = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful) {
            val snippet = try {
                response.peekBody(8192).string()
            } catch (e: Exception) {
                "(peekBody failed: ${e.message})"
            }
            logUnsuccessfulEmbyResponse(
                request.method,
                request.url,
                response.code,
                response.message,
                snippet,
            )
        }
        response
    }

    private fun buildRetrofit(baseUrl: String, client: OkHttpClient, json: Json): EmbyApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(jsonContent))
            .build()
            .create(EmbyApi::class.java)

    fun normalizeBaseUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        val withScheme = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "http://$trimmed"
        }
        val url = withScheme.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid base URL: $input")
        val base = url.newBuilder().build().toString()
        // Retrofit requires baseUrl to end with `/` so @POST("Users/...") resolves under the host path.
        return if (base.endsWith('/')) base else "$base/"
    }
}
