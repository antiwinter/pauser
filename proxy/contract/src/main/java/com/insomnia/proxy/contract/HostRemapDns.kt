package com.insomnia.proxy.contract

import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Per-endpoint DNS remap: when looking up `from`, resolve `to` instead and return its IPs.
 *
 * Mirrors fongmi's `OkDns.addAll`/`get` (`catvod/.../net/OkDns.java`): entries are `"from=to"`
 * strings; lookup checks exact match, then substring, then regex match against the hostname.
 *
 * Mutable — remaps registered at runtime via [remap] take effect on the live OkHttpClient.
 * Installed per-endpoint by [WrappedProxyClient] (one Dns instance per endpoint, via
 * `newBuilder()` on the delegate's client) so a remap on one endpoint never leaks to another.
 * NOTE: only reaches OkHttp clients built by the app (ExoPlayer datasource, `host.http.*`,
 * JAR download, Coil); the spider JAR bundles its own okhttp and is unaffected.
 */
class HostRemapDns(private val system: Dns = Dns.SYSTEM) : Dns {

    private val map = ConcurrentHashMap<String, String>()

    fun remap(from: String, to: String) {
        val f = from.trim()
        val t = to.trim()
        if (f.isNotEmpty() && t.isNotEmpty()) map[f] = t
    }

    fun remapAll(entries: List<String>) {
        for (entry in entries) {
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2) remap(parts[0], parts[1])
        }
    }

    fun clear() = map.clear()

    private fun targetFor(host: String): String? {
        map[host]?.let { return it }
        for ((key, value) in map) {
            if (host.contains(key)) return value
            try {
                if (Pattern.compile(key).matcher(host).find()) return value
            } catch (_: Throwable) { /* not a valid regex — skip */ }
        }
        return null
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val target = targetFor(hostname) ?: return system.lookup(hostname)
        return system.lookup(target)
    }
}
