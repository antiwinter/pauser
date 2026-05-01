package com.opentune.emby.api

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
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                req.header("Accept", "application/json")
                if (!accessToken.isNullOrBlank()) {
                    req.header("X-Emby-Token", accessToken)
                }
                chain.proceed(req.build())
            }
            .apply {
                if (enableLogging) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(json.asConverterFactory(jsonContent))
            .build()
            .create(EmbyApi::class.java)
    }

    fun normalizeBaseUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        val withScheme = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "http://$trimmed"
        }
        val url = withScheme.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid base URL: $input")
        return url.newBuilder().build().toString()
    }
}
