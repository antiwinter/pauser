package com.opentune.smb

import com.opentune.content.contract.OpenTuneProvider
import com.opentune.content.contract.EndpointClient
import com.opentune.core.form.contract.FormFieldKind
import com.opentune.core.form.contract.FormFieldSpec

class SmbProvider : OpenTuneProvider {

    override val protocol: String = "smb"
    override val providesArt: Boolean = false
    override fun getFieldsSpec(): List<FormFieldSpec> = listOf(
        FormFieldSpec(
            id = "host",
            labelKey = "fld_network_host",
            kind = FormFieldKind.SingleLineText,
            required = true,
            identity = true,
            order = 0,
        ),
        FormFieldSpec(
            id = "share_name",
            labelKey = "fld_share_name",
            kind = FormFieldKind.SingleLineText,
            required = true,
            identity = true,
            order = 1,
        ),
        FormFieldSpec(
            id = "username",
            labelKey = "fld_account_username",
            kind = FormFieldKind.SingleLineText,
            required = true,
            identity = true,
            order = 2,
        ),
        FormFieldSpec(
            id = "password",
            labelKey = "fld_account_password",
            kind = FormFieldKind.Password,
            required = true,
            sensitive = true,
            order = 3,
        ),
        FormFieldSpec(
            id = "domain",
            labelKey = "fld_domain_optional",
            kind = FormFieldKind.SingleLineText,
            required = false,
            order = 4,
        ),
        FormFieldSpec(id = "proxy", labelKey = "", kind = FormFieldKind.ProxySelector, order = Int.MAX_VALUE),
        FormFieldSpec(id = "name", labelKey = "fld_endpoint_name", kind = FormFieldKind.SingleLineText, required = false, order = 100),
    )

    override fun createClient(values: Map<String, String>): EndpointClient {
        val fields = SmbServerFieldsJson(
            host = values["host"] ?: error("Missing host"),
            shareName = values["share_name"] ?: error("Missing share_name"),
            username = values["username"] ?: error("Missing username"),
            password = values["password"] ?: error("Missing password"),
            domain = values["domain"],
        )
        return SmbClient(fields = fields)
    }
}
