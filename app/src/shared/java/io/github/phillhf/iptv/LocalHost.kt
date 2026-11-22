package io.github.phillhf.iptv

import java.net.InetAddress
import java.net.URI

/** Nur nach der Schreibweise, ohne Namensauflösung. */
fun isLocalHost(address: String): Boolean {
    val host = runCatching { URI(address.trim()).host }.getOrNull()?.lowercase() ?: return false
    if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".fritz.box") || !host.contains('.')) return true
    if (!Regex("[0-9.]+").matches(host)) return false
    return runCatching { InetAddress.getByName(host).let { it.isSiteLocalAddress || it.isLinkLocalAddress } }.getOrDefault(false)
}
