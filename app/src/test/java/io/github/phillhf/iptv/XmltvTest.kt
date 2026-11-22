package io.github.phillhf.iptv

import io.github.phillhf.iptv.data.Xmltv
import io.github.phillhf.iptv.data.XmltvProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/** Anbieter liefern oft kaputtes XMLTV; was lesbar ist, muss trotzdem ankommen. */
class XmltvTest {
    private val channels = ArrayList<Pair<String, String>>()
    private val shows = ArrayList<XmltvProgramme>()

    private fun read(bytes: ByteArray) {
        channels.clear()
        shows.clear()
        Xmltv.parse(bytes.inputStream(), { id, name -> channels += id to name }, { shows += it })
    }

    private fun read(xml: String) = read(xml.toByteArray())

    private fun show(channel: String, title: String, hour: Int = 10) =
        """<programme start="202611221${hour % 10}0000 +0100" stop="202611221${hour % 10}3000 +0100" channel="$channel"><title>$title</title></programme>"""

    private val titles get() = shows.map { it.title }

    @Test
    fun wellFormed() {
        read(
            """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE tv SYSTEM "xmltv.dtd" [<!ENTITY a "b">]><tv generator-info-name='x > y'>
            <!-- <programme> im Kommentar -- zählt nicht --->
            <channel id="a&amp;b"><display-name lang="de">Das Erste</display-name><display-name>ARD</display-name><icon src="x"/></channel>
            <programme channel='a&amp;b' stop="20261122110000 +0100" start = "20261122100000 +0100"><title lang="de">Tom &amp; J&#252;rgen &#x2013; <![CDATA[<b>A & B ]] C</b>]]></title><title lang="en">No</title><desc>Text</desc></programme>
            </tv>""",
        )
        assertEquals(listOf("a&b" to "Das Erste", "a&b" to "ARD"), channels)
        assertEquals("Tom & Jürgen – <b>A & B ]] C</b>", titles.single())
        assertEquals("Text", shows[0].description)
        assertEquals("a&b", shows[0].channel)
        assertEquals(3_600_000L, shows[0].stop - shows[0].start)
    }

    @Test
    fun severalDocumentsInOneFile() {
        val doc = "<?xml version=\"1.0\"?>\n<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n<tv><channel id=\"%s\"><display-name>%s</display-name></channel>%s</tv>\n"
        read(doc.format("a", "A", show("a", "Eins")) + doc.format("b", "B", show("b", "Zwei")))
        assertEquals(listOf("Eins", "Zwei"), titles)
        assertEquals(listOf("a" to "A", "b" to "B"), channels)
    }

    @Test
    fun junkAfterTheEnd() {
        read("<tv>" + show("a", "Eins") + "</tv>\n<br />\n<b>Warning</b>: mysql_close() in /x.php on line 3 < 4 && <")
        assertEquals(listOf("Eins"), titles)
    }

    @Test
    fun rawCharactersAndHtmlEntities() {
        read("<tv>" + show("a", "Tom & Jerry &nbsp;A&B; 3 < 4 && mehr &") + show("b", "Da\u0001nach") + "</tv>")
        assertEquals(2, shows.size)
        assertEquals("Tom & Jerry  A&B; 3 < 4 && mehr &", titles[0])
    }

    @Test
    fun brokenTagsAndCutOff() {
        read("<tv><programme start=\"2026112210" + show("a", "Eins") + "<programme start=\"20261122100000\" channel=\"b\"><title>Ohne Ende" + show("c", "Zwei") + show("d", "Drei").dropLast(20))
        assertEquals(listOf("Eins", "Zwei"), titles)
    }

    @Test
    fun otherEncodings() {
        read("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><tv>${show("a", "Füße")}</tv>".toByteArray(Charsets.ISO_8859_1))
        assertEquals(listOf("Füße"), titles)
        read(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "<tv>${show("a", "Füße")}</tv>".toByteArray())
        assertEquals(listOf("Füße"), titles)
    }

    @Test
    fun gzipAndLargeFiles() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { g ->
            g.write("<tv>".toByteArray())
            repeat(20_000) { g.write(show("ch${it % 50}", "Sendung Nr. $it mit ä").toByteArray()) }
            g.write("</tv>".toByteArray())
        }
        read(out.toByteArray())
        assertEquals(20_000, shows.size)
        assertEquals("Sendung Nr. 19999 mit ä", titles.last())
    }

    @Test
    fun errorPageIsNotAGuide() {
        assertThrows(IOException::class.java) { read("<html><body><title>Fehler</title></body></html>") }
        assertThrows(IOException::class.java) { read("") }
        read("<tv></tv>")
        assertEquals(0, shows.size)
    }
}
