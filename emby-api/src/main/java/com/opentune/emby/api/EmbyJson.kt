package com.opentune.emby.api

import kotlinx.serialization.json.Json

internal fun embyJson(): Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
