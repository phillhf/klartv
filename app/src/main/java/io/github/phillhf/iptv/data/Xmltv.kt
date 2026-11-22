package io.github.phillhf.iptv.data

import io.github.phillhf.iptv.t
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

data class XmltvProgramme(val channel: String, val start: Long, val stop: Long, val title: String, val description: String)

/**
 * Liest XMLTV-Programmdaten als Strom, damit auch sehr große Dateien in den Speicher passen. Absichtlich nachsichtig:
 * Viele Anbieter liefern kaputte Dateien (mehrere Dokumente hintereinander, rohe &-Zeichen, Text nach dem Ende),
 * an denen ein strenger XML-Leser ganz scheitert.
 */
object Xmltv {
    private const val MAX_TEXT = 4000
    private const val MAX_TAG = 16 * 1024

    /** Wirft eine IOException, wenn es gar keine XMLTV-Datei ist (etwa eine Fehlerseite). */
    fun parse(input: InputStream, onChannel: (id: String, name: String) -> Unit, onProgramme: (XmltvProgramme) -> Unit) {
        val stream = unzip(input)
        val handler = Handler(onChannel, onProgramme)
        Scanner(InputStreamReader(stream, charset(stream)), handler).run()
        if (!handler.recognized) throw IOException(t("Die Datei enthält keinen Programmführer."))
    }

    /** Erkennt gzip am Dateianfang, egal wie die Adresse endet. */
    private fun unzip(input: InputStream): InputStream {
        val b = BufferedInputStream(input, 64 * 1024)
        b.mark(2)
        val first = b.read()
        val second = b.read()
        b.reset()
        return if (first == 0x1f && second == 0x8b) BufferedInputStream(GZIPInputStream(b), 64 * 1024) else b
    }

    /** Zeichensatz aus der ersten Zeile, sonst UTF-8. Überliest die Kennung am Anfang (BOM). */
    private fun charset(stream: InputStream): Charset {
        val start = ByteArray(256)
        stream.mark(start.size)
        var n = 0
        while (n < start.size) {
            val r = stream.read(start, n, start.size - n)
            if (r < 0) break
            n += r
        }
        stream.reset()
        fun at(i: Int) = if (i < n) start[i].toInt() and 0xff else -1
        fun skip(count: Int) = repeat(count) { stream.read() }
        if (at(0) == 0xef && at(1) == 0xbb && at(2) == 0xbf) {
            skip(3)
            return Charsets.UTF_8
        }
        if (at(0) == 0xff && at(1) == 0xfe) {
            skip(2)
            return Charsets.UTF_16LE
        }
        if (at(0) == 0xfe && at(1) == 0xff) {
            skip(2)
            return Charsets.UTF_16BE
        }
        val name = Regex("""<\?xml[^>]*encoding\s*=\s*["']([^"']+)["']""").find(String(start, 0, n, Charsets.ISO_8859_1))?.groupValues?.get(1)
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    /** Zerlegt den Text in Anfangs- und Endmarken und Inhalt, ohne auf gültiges XML zu bestehen. */
    private class Scanner(private val reader: Reader, private val handler: Handler) {
        private val buffer = CharArray(64 * 1024)
        private var size = 0
        private var at = 0

        private fun read(): Int {
            if (at >= size) {
                size = reader.read(buffer)
                at = 0
                if (size <= 0) {
                    size = 0
                    return -1
                }
            }
            return buffer[at++].code
        }

        fun run() {
            while (true) {
                val c = read()
                if (c < 0) break
                if (c == '<'.code) markup() else handler.text(c.toChar())
            }
        }

        private fun markup() {
            var c = read()
            // „<<tag“: Das erste Zeichen war nur Text
            while (c == '<'.code) {
                handler.text('<')
                c = read()
            }
            when {
                c < 0 -> return
                c == '?'.code -> skipUntil("?>")
                c == '!'.code -> declaration()
                c == '/'.code -> tag(end = true, first = read())
                c.toChar().isLetter() || c == '_'.code -> tag(end = false, first = c)
                else -> {
                    handler.text('<')
                    handler.text(c.toChar())
                }
            }
        }

        private fun declaration() {
            val start = StringBuilder()
            while (start.length < 7) {
                val c = read()
                if (c < 0) return
                start.append(c.toChar())
                if (start.startsWith("--")) return skipUntil("-->")
                if (c == '>'.code) return
            }
            if (start.toString() == "[CDATA[") {
                cdata()
                return
            }
            // <!DOCTYPE …>, auch mit eigenem Teil in eckigen Klammern
            var depth = start.count { it == '[' }
            while (true) {
                val c = read()
                when {
                    c < 0 -> return
                    c == '['.code -> depth++
                    c == ']'.code -> depth--
                    c == '>'.code && depth <= 0 -> return
                }
            }
        }

        private fun cdata() {
            var brackets = 0
            while (true) {
                val c = read()
                when {
                    c < 0 -> return
                    c == ']'.code -> brackets++
                    c == '>'.code && brackets >= 2 -> {
                        repeat(brackets - 2) { handler.text(']', raw = true) }
                        return
                    }
                    else -> {
                        repeat(brackets) { handler.text(']', raw = true) }
                        brackets = 0
                        handler.text(c.toChar(), raw = true)
                    }
                }
            }
        }

        private fun skipUntil(end: String) {
            var hit = 0
            while (hit < end.length) {
                val c = read()
                if (c < 0) return
                hit = when {
                    c == end[hit].code -> hit + 1
                    // „--->“ und „??>“: das vorige Zeichen kann schon zum Ende gehören
                    hit > 0 && c == end[hit - 1].code -> hit
                    c == end[0].code -> 1
                    else -> 0
                }
            }
        }

        private fun tag(end: Boolean, first: Int) {
            if (first < 0) return
            val raw = StringBuilder().append(first.toChar())
            var quote = 0.toChar()
            var closed = first == '>'.code
            while (!closed) {
                val c = read()
                if (c < 0) return
                val ch = c.toChar()
                when {
                    quote != 0.toChar() -> if (ch == quote) quote = 0.toChar()
                    ch == '>' -> {
                        closed = true
                        break
                    }
                    // Die Marke wurde nie geschlossen: Hier beginnt schon die nächste
                    ch == '<' -> {
                        at--
                        return
                    }
                    (ch == '"' || ch == '\'') && !end -> quote = ch
                }
                raw.append(ch)
                if (raw.length > MAX_TAG) return
            }
            val text = raw.toString().trimEnd('>')
            val name = text.takeWhile { !it.isWhitespace() && it != '/' }
            if (end) {
                handler.end(name)
            } else {
                handler.start(name, text)
                if (text.endsWith('/')) handler.end(name)
            }
        }
    }

    private class Handler(
        private val onChannel: (String, String) -> Unit,
        private val onProgramme: (XmltvProgramme) -> Unit,
    ) {
        var recognized = false
            private set
        private val text = StringBuilder()
        private val entity = StringBuilder()
        private var inEntity = false
        private var collecting = false
        private var channelId: String? = null
        private var inProgramme = false
        private var channel = ""
        private var start = 0L
        private var stop = 0L
        private var title = ""
        private var description = ""

        fun start(name: String, tag: String) {
            when (name) {
                "tv" -> recognized = true
                "channel" -> channelId = attribute(tag, "id")
                "programme" -> {
                    recognized = true
                    inProgramme = true
                    channel = attribute(tag, "channel").orEmpty()
                    start = parseTime(attribute(tag, "start"))
                    stop = parseTime(attribute(tag, "stop"))
                    title = ""
                    description = ""
                    collecting = false
                }
                "display-name", "title", "desc" -> {
                    // Bei mehreren Sprachen zählt die erste
                    collecting = when (name) {
                        "display-name" -> channelId != null
                        "title" -> inProgramme && title.isEmpty()
                        else -> inProgramme && description.isEmpty()
                    }
                    text.setLength(0)
                    inEntity = false
                }
            }
        }

        /** [raw]: aus CDATA, dort ist & ein gewöhnliches Zeichen. */
        fun text(c: Char, raw: Boolean = false) {
            if (!collecting || text.length >= MAX_TEXT) return
            if (inEntity && (raw || c == '&')) flushEntity()
            when {
                raw -> text.append(c)
                inEntity -> {
                    entity.append(c)
                    if (c == ';') {
                        text.append(decode(entity.toString()))
                        inEntity = false
                    } else if (entity.length > 12 || !(c.isLetterOrDigit() || c == '#')) {
                        flushEntity()
                    }
                }
                c == '&' -> {
                    inEntity = true
                    entity.setLength(0)
                    entity.append(c)
                }
                else -> text.append(c)
            }
        }

        private fun flushEntity() {
            text.append(entity)
            inEntity = false
        }

        fun end(name: String) {
            if (inEntity) flushEntity()
            when (name) {
                "display-name" -> if (collecting) channelId?.let { onChannel(it, text.toString().trim()) }
                "title" -> if (collecting) title = text.toString().trim()
                "desc" -> if (collecting) description = text.toString().trim()
                "channel" -> channelId = null
                "programme" -> {
                    if (inProgramme && channel.isNotEmpty() && start > 0 && stop > start) {
                        onProgramme(XmltvProgramme(channel, start, stop, title, description))
                    }
                    inProgramme = false
                }
                else -> return
            }
            collecting = false
        }
    }

    /** Wert eines Attributs; die Reihenfolge und die Art der Anführungszeichen sind egal. */
    private fun attribute(tag: String, name: String): String? {
        var i = tag.indexOfFirst { it.isWhitespace() }
        while (i in 0 until tag.length) {
            while (i < tag.length && (tag[i].isWhitespace() || tag[i] == '/')) i++
            val from = i
            while (i < tag.length && tag[i] != '=' && !tag[i].isWhitespace()) i++
            val key = tag.substring(from, i)
            while (i < tag.length && tag[i].isWhitespace()) i++
            if (i >= tag.length || tag[i] != '=') {
                if (i == from) return null
                continue
            }
            i++
            while (i < tag.length && tag[i].isWhitespace()) i++
            if (i >= tag.length) return null
            val quote = tag[i]
            val value = if (quote == '"' || quote == '\'') {
                val end = tag.indexOf(quote, i + 1).let { if (it < 0) tag.length else it }
                tag.substring(i + 1, end).also { i = end + 1 }
            } else {
                val end = (i until tag.length).firstOrNull { tag[it].isWhitespace() } ?: tag.length
                tag.substring(i, end).also { i = end }
            }
            if (key == name) return ENTITY.replace(value) { decode(it.value) }
        }
        return null
    }

    private val ENTITY = Regex("&#?[A-Za-z0-9]+;")

    private fun decode(entity: String): String = when (entity) {
        "&amp;" -> "&"
        "&lt;" -> "<"
        "&gt;" -> ">"
        "&quot;" -> "\""
        "&apos;" -> "'"
        "&nbsp;" -> " "
        else -> {
            val code = when {
                entity.startsWith("&#x", ignoreCase = true) -> entity.substring(3, entity.length - 1).toIntOrNull(16)
                entity.startsWith("&#") -> entity.substring(2, entity.length - 1).toIntOrNull()
                else -> null
            }
            if (code != null && code >= 32 && Character.isValidCodePoint(code)) String(Character.toChars(code)) else entity
        }
    }

    /** „20260131201500 +0100“ in Millisekunden; 0, wenn nicht lesbar. Ohne Zone gilt UTC. */
    fun parseTime(value: String?): Long {
        val s = value?.trim().orEmpty()
        if (s.length < 12) return 0
        return try {
            val year = s.substring(0, 4).toInt()
            val month = s.substring(4, 6).toInt()
            val day = s.substring(6, 8).toInt()
            val hour = s.substring(8, 10).toInt()
            val minute = s.substring(10, 12).toInt()
            val second = if (s.length >= 14 && s[12].isDigit()) s.substring(12, 14).toInt() else 0
            val zone = s.indexOfFirst { it == '+' || it == '-' }
            var offset = 0
            if (zone > 0 && s.length >= zone + 5) {
                val digits = s.substring(zone + 1).replace(":", "")
                offset = (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()) * if (s[zone] == '-') -1 else 1
            }
            (epochDay(year, month, day) * 86_400L + hour * 3600 + minute * 60 + second - offset * 60) * 1000
        } catch (e: RuntimeException) {
            0
        }
    }

    private fun epochDay(y: Int, m: Int, d: Int): Long = java.time.LocalDate.of(y, m, d).toEpochDay()

    /** Vergleichbarer Sendername: „DE | Das Erste FHD“ und „das erste hd“ passen zusammen. */
    fun normalizeName(name: String): String {
        val noPrefix = name.lowercase().replace(Regex("^[a-z]{2,3}\\s*[|:\\-]\\s*"), "")
        return noPrefix.replace(Regex("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|raw|1080p|720p)\\b"), "").filter { it.isLetterOrDigit() }
    }
}
