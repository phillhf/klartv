package io.github.phillhf.iptv.server

import io.github.phillhf.iptv.data.Http
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest

/**
 * Holt Senderlogos und Cover für den Browser. Die Seite läuft über HTTPS, viele Anbieter liefern Bilder nur über
 * HTTP – das würde der Browser sperren. Bilder bleiben eine Woche auf der Platte, insgesamt höchstens 300 MB.
 */
class Images(private val dir: File, private val allowLocal: Boolean = false) {
    init {
        dir.mkdirs()
    }

    class Image(val bytes: ByteArray, val type: String)

    @Volatile private var lastCleanup = 0L

    fun get(address: String): Image {
        val uri = runCatching { URI(address) }.getOrNull() ?: throw IOException("Ungültige Adresse")
        if (uri.scheme != "http" && uri.scheme != "https") throw IOException("Ungültige Adresse")
        // Nicht als Umweg zu Diensten auf diesem Rechner
        val host = uri.host ?: throw IOException("Ungültige Adresse")
        val loopback = host.equals("localhost", true) || runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(true)
        if (loopback && !allowLocal) {
            throw IOException("Ungültige Adresse")
        }
        val name = MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, name)
        val meta = File(dir, "$name.type")
        if (file.exists() && meta.exists() && System.currentTimeMillis() - file.lastModified() < 7 * 86_400_000L) {
            return Image(file.readBytes(), meta.readText())
        }
        val conn = Http.open(address)
        val (bytes, type) = try {
            val type = conn.contentType?.substringBefore(';')?.trim().orEmpty()
            if (!type.startsWith("image/")) throw IOException("Kein Bild")
            val len = conn.contentLengthLong
            if (len > 8_000_000) throw IOException("Bild zu groß")
            val b = conn.inputStream.use { it.readNBytes(8_000_001) }
            if (b.size > 8_000_000) throw IOException("Bild zu groß")
            b to type
        } finally {
            conn.disconnect()
        }
        runCatching {
            file.writeBytes(bytes)
            meta.writeText(type)
        }
        cleanup()
        return Image(bytes, type)
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        if (now - lastCleanup < 3_600_000L) return
        lastCleanup = now
        Thread {
            val files = dir.listFiles()?.filter { !it.name.endsWith(".type") }?.sortedByDescending { it.lastModified() } ?: return@Thread
            var total = 0L
            files.forEach { f ->
                total += f.length()
                if (total > 300_000_000L || now - f.lastModified() > 14 * 86_400_000L) {
                    f.delete()
                    File(f.path + ".type").delete()
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
