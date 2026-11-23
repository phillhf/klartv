package io.github.phillhf.iptv.transfer

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Kleiner Webserver nur für dieses Gerät: Er reicht Aufnahmen vom Netzlaufwerk an den Player weiter, auch mit Spulen.
 * Erreichbar nur über 127.0.0.1 und mit einem zufälligen Schlüssel in der Adresse.
 */
object LocalServer {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val files = ConcurrentHashMap<String, String>()
    private val pool = Executors.newCachedThreadPool { Thread(it, "klartv-server").apply { isDaemon = true } }
    private var socket: ServerSocket? = null

    @Synchronized
    private fun port(): Int {
        socket?.let { return it.localPort }
        val s = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        socket = s
        pool.execute {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                pool.execute { runCatching { serve(c) } }
            }
        }
        return s.localPort
    }

    fun url(location: String): String {
        val id = Integer.toHexString(location.hashCode()) + "-" + files.size
        files[id] = location
        val name = URLEncoder.encode(location.substringAfterLast('/'), "UTF-8").replace("+", "%20")
        return "http://127.0.0.1:${port()}/$token/$id/$name"
    }

    private fun type(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "ts" -> "video/mp2t"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }

    private fun serve(c: Socket) = c.use {
        c.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
        val request = reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val parts = request.split(' ')
        val out = c.getOutputStream()
        fun answer(status: String, headers: List<String> = emptyList()) {
            out.write(("HTTP/1.1 $status\r\n" + (headers + "Connection: close").joinToString("") { "$it\r\n" } + "\r\n").toByteArray())
        }
        val segments = parts.getOrNull(1)?.split('/').orEmpty()
        val location = if (segments.getOrNull(1) == token) files[segments.getOrNull(2)] else null
        if (location == null || (parts[0] != "GET" && parts[0] != "HEAD")) {
            answer("404 Not Found", listOf("Content-Length: 0"))
            return
        }
        val (store, name) = Stores.resolve(location)
        val size = store.size(name)
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        var from = 0L
        var to = size - 1
        if (m != null) {
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if (a.isEmpty()) {
                from = (size - (b.toLongOrNull() ?: 0)).coerceAtLeast(0)
            } else {
                from = a.toLong()
                if (b.isNotEmpty()) to = minOf(b.toLong(), size - 1)
            }
            if (from >= size) {
                answer("416 Range Not Satisfiable", listOf("Content-Range: bytes */$size", "Content-Length: 0"))
                return
            }
        }
        val length = to - from + 1
        val headers = listOf("Content-Type: ${type(name)}", "Accept-Ranges: bytes", "Content-Length: $length") +
            if (m != null) listOf("Content-Range: bytes $from-$to/$size") else emptyList()
        answer(if (m != null) "206 Partial Content" else "200 OK", headers)
        if (parts[0] == "HEAD") return
        store.input(name, from).use { input ->
            val buffer = ByteArray(256 * 1024)
            var left = length
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (n < 0) break
                out.write(buffer, 0, n)
                left -= n
            }
        }
        out.flush()
    }
}
