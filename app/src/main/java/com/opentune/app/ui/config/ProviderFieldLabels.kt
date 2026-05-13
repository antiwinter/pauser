package com.opentune.app.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opentune.app.R

@Composable
fun providerFieldLabel(labelKey: String): String = when (labelKey) {
    "fld_http_library_url" -> stringResource(R.string.fld_http_library_url)
    "fld_account_username" -> stringResource(R.string.fld_account_username)
    "fld_account_password" -> stringResource(R.string.fld_account_password)
    "fld_display_name" -> stringResource(R.string.fld_display_name)
    "fld_network_host" -> stringResource(R.string.fld_network_host)
    "fld_share_name" -> stringResource(R.string.fld_share_name)
    "fld_domain_optional" -> stringResource(R.string.fld_domain_optional)
    else -> labelKey
}

@Composable
fun providerFieldPlaceholder(placeholderKey: String?): String? =
    when (placeholderKey) {
        "ph_http_library_url" -> stringResource(R.string.ph_http_library_url)
        null -> null
        else -> placeholderKey
    }
