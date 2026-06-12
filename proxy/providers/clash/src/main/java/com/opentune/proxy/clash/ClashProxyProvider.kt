package com.opentune.proxy.clash

import com.opentune.core.form.contract.FormFieldKind
import com.opentune.core.form.contract.FormFieldSpec
import com.opentune.proxy.contract.ProxyClient
import com.opentune.proxy.contract.ProxyProvider

class ClashProxyProvider : ProxyProvider {

    override val proxyType: String = "clash"

    override fun getFieldsSpec(): List<FormFieldSpec> = listOf(
        FormFieldSpec(
            id = "url",
            labelKey = "fld_clash_url",
            kind = FormFieldKind.SingleLineText,
            required = true,
            order = 0,
            placeholderKey = "ph_clash_url",
            identity = true,
        ),
        FormFieldSpec(
            id = "secret",
            labelKey = "fld_clash_secret",
            kind = FormFieldKind.Password,
            required = false,
            sensitive = true,
            order = 1,
        ),
        FormFieldSpec(
            id = "name",
            labelKey = "fld_clash_name",
            kind = FormFieldKind.SingleLineText,
            required = false,
            order = 2,
        ),
    )

    override fun createClient(values: Map<String, String>): ProxyClient = ClashProxyClient(values)
}
