package com.opentune.smb

import com.opentune.provider.ServerFieldKind
import com.opentune.provider.ServerFieldSpec

/** Declarative fields for file-share add/edit flows. */
object SmbServerFields {

    fun serverFields(): List<ServerFieldSpec> = listOf(
        ServerFieldSpec(
            id = "display_name",
            labelKey = "fld_display_name",
            kind = ServerFieldKind.SingleLineText,
            required = false,
            order = 0,
        ),
        ServerFieldSpec(
            id = "host",
            labelKey = "fld_network_host",
            kind = ServerFieldKind.SingleLineText,
            required = true,
            order = 1,
        ),
        ServerFieldSpec(
            id = "share_name",
            labelKey = "fld_share_name",
            kind = ServerFieldKind.SingleLineText,
            required = true,
            order = 2,
        ),
        ServerFieldSpec(
            id = "username",
            labelKey = "fld_account_username",
            kind = ServerFieldKind.SingleLineText,
            required = true,
            order = 3,
        ),
        ServerFieldSpec(
            id = "password",
            labelKey = "fld_account_password",
            kind = ServerFieldKind.Password,
            required = true,
            sensitive = true,
            order = 4,
        ),
        ServerFieldSpec(
            id = "domain",
            labelKey = "fld_domain_optional",
            kind = ServerFieldKind.SingleLineText,
            required = false,
            order = 5,
        ),
    )
}
