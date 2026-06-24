package com.opentune.player

/** Bits-per-second → localized-ish "1.5 Mbps" / "750 Kbps" / "999 bps", auto-scaled (decimal base). */
fun formatBitrate(bps: Float): String {
    if (bps < 0f || bps.isNaN()) return ""
    val (v, sym) = when {
        bps < 1_000f -> bps to "bps"
        bps < 1_000_000f -> bps / 1_000f to "Kbps"
        bps < 1_000_000_000f -> bps / 1_000_000f to "Mbps"
        else -> bps / 1_000_000_000f to "Gbps"
    }
    return "%.1f %s".format(v, sym)
}
