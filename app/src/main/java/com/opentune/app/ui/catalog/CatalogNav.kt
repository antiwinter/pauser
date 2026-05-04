package com.opentune.app.ui.catalog

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Single place for opaque browse/search location tokens and encoding rules used in [Routes].
 * HTTP-library catalog root uses a named sentinel (not raw API ids).
 */
object CatalogNav {
    const val UrlCharset = "UTF-8"

    /** Encoded [location] segment meaning “show top-level library views” grid for HTTP-library providers. */
    const val LIBRARIES_ROOT_SEGMENT: String = "__opentune_library_root__"

    fun encodeSegment(raw: String): String = URLEncoder.encode(raw, UrlCharset)

    fun decodeSegment(encoded: String): String =
        URLDecoder.decode(encoded, UrlCharset)
}
