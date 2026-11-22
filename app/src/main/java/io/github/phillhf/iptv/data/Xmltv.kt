package io.github.phillhf.iptv.data

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

data class XmltvProgramme(val channel: String, val start: Long, val stop: Long, val title: String, val description: String)

/** Liest XMLTV-Programmdaten als Strom, damit auch sehr große Dateien in den Speicher passen. */
object Xmltv {
    fun parse(input: InputStream, onChannel: (id: String, name: String) -> Unit, onProgramme: (XmltvProgramme) -> Unit) {
        val stream = unzip(input)
        SAXParserFactory.newInstance().newSAXParser().parse(stream, Handler(onChannel, onProgramme))
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

    private class Handler(
        private val onChannel: (String, String) -> Unit,
        private val onProgramme: (XmltvProgramme) -> Unit,
    ) : DefaultHandler() {
        private val text = StringBuilder()
        private var collecting = false
        private var channelId: String? = null
        private var inProgramme = false
        private var channel = ""
        private var start = 0L
        private var stop = 0L
        private var title = ""
        private var description = ""

        // Keine fremden DTDs oder Dateien nachladen
        override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))

        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
            when (val name = qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()) {
                "channel" -> channelId = a.getValue("id")
                "programme" -> {
                    inProgramme = true
                    channel = a.getValue("channel").orEmpty()
                    start = parseTime(a.getValue("start"))
                    stop = parseTime(a.getValue("stop"))
                    title = ""
                    description = ""
                }
                "display-name", "title", "desc" -> {
                    // Bei mehreren Sprachen zählt die erste
                    collecting = when (name) {
                        "display-name" -> channelId != null
                        "title" -> inProgramme && title.isEmpty()
                        else -> inProgramme && description.isEmpty()
                    }
                    text.setLength(0)
                }
            }
        }

        override fun characters(ch: CharArray, offset: Int, length: Int) {
            if (collecting && text.length < 4000) text.append(ch, offset, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()) {
                "display-name" -> if (collecting) channelId?.let { onChannel(it, text.toString().trim()) }
                "title" -> if (collecting) title = text.toString().trim()
                "desc" -> if (collecting) description = text.toString().trim()
                "channel" -> channelId = null
                "programme" -> {
                    inProgramme = false
                    if (channel.isNotEmpty() && start > 0 && stop > start) {
                        onProgramme(XmltvProgramme(channel, start, stop, title, description))
                    }
                }
                else -> return
            }
            collecting = false
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
