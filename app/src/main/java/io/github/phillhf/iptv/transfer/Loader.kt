package io.github.phillhf.iptv.transfer

import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.data.Http
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import java.io.RandomAccessFile
import java.net.URL

/** Lädt Dateien und schneidet Live-Streams mit. Ohne Android-Abhängigkeit, damit es sich testen lässt. */
class Loader(private val isCancelled: () -> Boolean, private val onProgress: (bytes: Long, total: Long) -> Unit) {
    class Cancelled : IOException()

    /** Bei so wenig freiem Platz wird abgebrochen. */
    private val reserve = 300L * 1024 * 1024

    /** Lädt nach [target], setzt einen abgebrochenen Versuch fort. */
    fun download(url: String, target: File, pause: (Long) -> Unit = { Thread.sleep(it) }) {
        val part = File(target.path + ".part")
        var attempt = 0
        while (true) {
            val before = part.length()
            try {
                downloadOnce(url, part)
                break
            } catch (e: Cancelled) {
                throw e
            } catch (e: IOException) {
                if (e is Fatal) throw e
                // Solange es vorangeht, zählt ein Abbruch nicht als Fehlversuch
                attempt = if (part.length() > before) 1 else attempt + 1
                if (attempt >= 6) throw e
                pause(2_000L * attempt)
            }
        }
        if (!part.renameTo(target)) throw Fatal(t("Datei konnte nicht gespeichert werden."))
    }

    class Fatal(message: String) : IOException(message)

    private fun downloadOnce(url: String, part: File) {
        val have = part.length()
        val conn = Http.open(url, if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap())
        try {
            val type = conn.contentType.orEmpty()
            if (type.startsWith("text/html") || type.contains("json")) throw Fatal(t("Der Anbieter liefert keine Videodatei."))
            val resumed = have > 0 && conn.responseCode == 206 &&
                conn.getHeaderField("Content-Range").orEmpty().startsWith("bytes $have-")
            var bytes = if (resumed) have else 0L
            val length = conn.contentLengthLong
            val total = if (length > 0) bytes + length else 0L
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(bytes)
                out.seek(bytes)
                conn.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var sinceCheck = 0L
                    while (true) {
                        if (isCancelled()) throw Cancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        bytes += n
                        sinceCheck += n
                        if (sinceCheck > 32L * 1024 * 1024) {
                            sinceCheck = 0
                            checkSpace(part)
                        }
                        onProgress(bytes, total)
                    }
                }
            }
            if (total in 1..Long.MAX_VALUE && bytes < total) throw IOException(t("Verbindung abgebrochen."))
            if (bytes == 0L) throw IOException(t("Leere Antwort."))
        } finally {
            conn.disconnect()
        }
    }

    private fun checkSpace(file: File) {
        val free = file.absoluteFile.parentFile?.usableSpace ?: return
        if (free in 1 until reserve) throw Fatal(t("Der Speicher ist fast voll."))
    }

    /** Nimmt auf, bis [until] erreicht ist oder abgebrochen wird. Verbindet bei Aussetzern neu. */
    fun record(url: String, target: File, until: Long, clock: () -> Long = { System.currentTimeMillis() }, pause: (Long) -> Unit = { Thread.sleep(it) }): Long {
        var bytes = target.length()
        FileOutputStream(target, true).use { out ->
            val sink: (ByteArray, Int) -> Unit = { b, n ->
                out.write(b, 0, n)
                bytes += n
                onProgress(bytes, 0)
            }
            if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                recordHls(url, target, until, clock, pause, sink)
            } else {
                recordStream(url, target, until, clock, pause, sink)
            }
        }
        return bytes
    }

    private fun recordStream(url: String, target: File, until: Long, clock: () -> Long, pause: (Long) -> Unit, sink: (ByteArray, Int) -> Unit) {
        var lastData = clock()
        while (clock() < until && !isCancelled()) {
            try {
                val conn = Http.open(url)
                conn.readTimeout = 15_000
                try {
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (clock() < until && !isCancelled()) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            sink(buffer, n)
                            lastData = clock()
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                checkSpace(target)
            } catch (e: Fatal) {
                throw e
            } catch (e: IOException) {
                if (clock() - lastData > 120_000) throw e
            }
            if (clock() < until && !isCancelled()) pause(2_000)
        }
    }

    private fun recordHls(url: String, target: File, until: Long, clock: () -> Long, pause: (Long) -> Unit, sink: (ByteArray, Int) -> Unit) {
        val media = resolveVariant(url)
        var next = -1L
        var lastData = clock()
        while (clock() < until && !isCancelled()) {
            var wait = 4_000L
            try {
                val playlist = HlsPlaylist.parse(Http.get(media))
                if (playlist.unsupported) throw Fatal(t("Verschlüsselte Streams lassen sich nicht aufnehmen."))
                wait = (playlist.targetSeconds * 500L).coerceIn(1_000, 6_000)
                playlist.segments.forEachIndexed { i, segment ->
                    val number = playlist.sequence + i
                    // Beim Einstieg nur das letzte Stück nehmen, damit die Aufnahme nicht zu früh beginnt
                    if (next < 0 && i < playlist.segments.size - 1 && !playlist.ended) return@forEachIndexed
                    if (number < next || isCancelled() || clock() >= until) return@forEachIndexed
                    copy(URL(URL(media), segment).toString(), sink)
                    next = number + 1
                    lastData = clock()
                }
                checkSpace(target)
                if (playlist.ended) return
            } catch (e: Fatal) {
                throw e
            } catch (e: IOException) {
                if (clock() - lastData > 120_000) throw e
            }
            if (clock() < until && !isCancelled()) pause(wait)
        }
    }

    private fun resolveVariant(url: String): String {
        val text = Http.get(url)
        return HlsPlaylist.bestVariant(text)?.let { URL(URL(url), it).toString() } ?: url
    }

    private fun copy(url: String, sink: (ByteArray, Int) -> Unit) {
        val conn = Http.open(url)
        try {
            // Erst ganz laden: ein halbes Stück würde beim Neuversuch doppelt in der Datei landen
            val data = conn.inputStream.use { it.readBytes() }
            sink(data, data.size)
        } finally {
            conn.disconnect()
        }
    }
}

class HlsPlaylist(val sequence: Long, val targetSeconds: Int, val segments: List<String>, val ended: Boolean, val unsupported: Boolean) {
    companion object {
        fun parse(text: String): HlsPlaylist {
            var sequence = 0L
            var target = 6
            var ended = false
            var unsupported = false
            val segments = ArrayList<String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: 0
                    line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').toDoubleOrNull()?.toInt() ?: 6
                    line.startsWith("#EXT-X-ENDLIST") -> ended = true
                    line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE") -> unsupported = true
                    line.startsWith("#EXT-X-MAP") || line.startsWith("#EXT-X-BYTERANGE") -> unsupported = true
                    line.isNotEmpty() && !line.startsWith("#") -> segments += line
                }
            }
            return HlsPlaylist(sequence, target, segments, ended, unsupported)
        }

        /** Adresse der Variante mit der höchsten Bitrate, null bei einer einfachen Playlist. */
        fun bestVariant(text: String): String? {
            var best: String? = null
            var bestRate = -1L
            var rate = -1L
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    rate = Regex("[:,]BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                } else if (rate >= 0 && line.isNotEmpty() && !line.startsWith("#")) {
                    if (rate > bestRate) {
                        bestRate = rate
                        best = line
                    }
                    rate = -1
                }
            }
            return best
        }
    }
}

