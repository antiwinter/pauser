package com.opentune.emby.api

import com.opentune.provider.ServerFieldKind
import com.opentune.provider.ServerFieldSpec

/** Declarative fields for HTTP library server flows. */
object EmbyServerFields {

    fun serverAddFields(): List<ServerFieldSpec> = listOf(
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

    fun serverEditFields(): List<ServerFieldSpec> = listOf(
        ServerFieldSpec(
            id = "display_name",
            labelKey = "fld_display_name",
            kind = ServerFieldKind.SingleLineText,
            required = true,
            order = 0,
        ),
        ServerFieldSpec(
            id = "base_url",
            labelKey = "fld_http_library_url",
            kind = ServerFieldKind.SingleLineText,
            required = true,
            order = 1,
            placeholderKey = "ph_http_library_url",
        ),
        ServerFieldSpec(
            id = "username",
            labelKey = "fld_account_username",
            kind = ServerFieldKind.SingleLineText,
            required = true,
            order = 2,
        ),
        ServerFieldSpec(
            id = "password",
            labelKey = "fld_account_password",
            kind = ServerFieldKind.Password,
            required = true,
            sensitive = true,
            order = 3,
        ),
    )
}
