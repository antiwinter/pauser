package com.opentune.app.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogNavTest {

    @Test
    fun librariesRootToken_roundTripsThroughEncode() {
        val raw = CatalogNav.LIBRARIES_ROOT_SEGMENT
        val enc = CatalogNav.encodeSegment(raw)
        val dec = CatalogNav.decodeSegment(enc)
        assertEquals(raw, dec)
    }

    @Test
    fun smbPath_withSlashes_roundTrips() {
        val raw = "Movies/Action/file.mkv"
        val enc = CatalogNav.encodeSegment(raw)
        val dec = CatalogNav.decodeSegment(enc)
        assertEquals(raw, dec)
    }
}
