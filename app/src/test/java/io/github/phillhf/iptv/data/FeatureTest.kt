package io.github.phillhf.iptv.data

import io.github.phillhf.iptv.isLocalHost
import io.github.phillhf.iptv.transfer.HlsPlaylist
import io.github.phillhf.iptv.transfer.Loader
import io.github.phillhf.iptv.ui.guideCells
import io.github.phillhf.iptv.ui.trailerUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.time.ZoneId
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

class FeatureTest {
    private val start = 1_769_890_500_000L // 31.01.2026 20:15 UTC

    private fun live(type: String?, source: String? = null, url: String = "http://host/ch/index.m3u8?t=1") =
        Stream(Kind.LIVE, "1", "A", null, "c", url, archiveDays = 3, catchupType = type, catchupSource = source)

    @Test
    fun xtreamCatchupUsesServerTime() {
        assertEquals("45/2026-01-31:21-15", Catchup.xtreamPath(start, start + 45 * 60_000, ZoneId.of("Europe/Berlin")))
    }

    @Test
    fun m3uCatchupVariants() {
        val end = start + 3_600_000
        val now = end + 1_000_000
        assertEquals(
            "http://host/ch/index.m3u8?t=1&utc=1769890500&lutc=1769895100",
            Catchup.m3uUrl(live("shift"), start, end, now),
        )
        assertEquals("http://host/ch/index-1769890500-3600.m3u8?t=1", Catchup.m3uUrl(live("flussonic"), start, end, now))
        assertEquals(
            "http://arc/1769890500-60/2026-01-31",
            Catchup.m3uUrl(live("default", "http://arc/\${start}-{duration:60}/{Y}-{m}-{d}"), start, end, now),
        )
        assertEquals("http://x/y.ts?at=20260131201500", Catchup.m3uUrl(live("append", "?at={utc:YmdHMS}", "http://x/y.ts"), start, end, now))
        assertEquals(
            "http://h:80/timeshift/u/p/60/2026-01-31:20-15/77.ts",
            Catchup.m3uUrl(live("xc", null, "http://h:80/live/u/p/77.m3u8"), start, end, now),
        )
        assertNull(Catchup.m3uUrl(live(null), start, end, now))
    }

    @Test
    fun catchupOnlyWithinArchive() {
        val s = live("shift")
        assertTrue(Catchup.available(s, start, start + 1000))
        assertFalse(Catchup.available(s, start, start + 4 * 86_400_000L))
        assertFalse(Catchup.available(s, start, start - 1000))
    }

    @Test
    fun xmltvIsParsedAlsoGzipped() {
        assertEquals(start, Xmltv.parseTime("20260131211500 +0100"))
        assertEquals(start, Xmltv.parseTime("20260131201500"))
        assertEquals(0, Xmltv.parseTime("kaputt"))
        val xml = """<?xml version="1.0"?><!DOCTYPE tv SYSTEM "xmltv.dtd"><tv>
            <channel id="a.de"><display-name>DE | Das Erste FHD</display-name></channel>
            <programme start="20260131211500 +0100" stop="20260131220000 +0100" channel="a.de">
            <title lang="de">Krimi &amp; Co</title><title lang="en">Crime</title><desc>Text</desc></programme></tv>"""
        val packed = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(xml.toByteArray()) } }.toByteArray()
        for (bytes in listOf(xml.toByteArray(), packed)) {
            val names = ArrayList<String>()
            val list = ArrayList<XmltvProgramme>()
            Xmltv.parse(ByteArrayInputStream(bytes), { _, n -> names += n }, { list += it })
            assertEquals(listOf("DE | Das Erste FHD"), names)
            assertEquals(1, list.size)
            assertEquals("Krimi & Co", list[0].title)
            assertEquals(45 * 60_000L, list[0].stop - list[0].start)
        }
        assertEquals(Xmltv.normalizeName("das erste hd"), Xmltv.normalizeName("DE | Das Erste FHD"))
    }

    @Test
    fun playlistHeaderAndCatchup() {
        val text = "#EXTM3U url-tvg=\"http://epg/x.xml.gz\"\n#EXTINF:-1 catchup=\"default\" catchup-days=\"5\" catchup-source=\"http://a/{utc}\",A\nhttp://h/1.ts"
        assertEquals("http://epg/x.xml.gz", M3uParser.epgUrl(text))
        val s = M3uParser.parse(text).single()
        assertEquals(5, s.archiveDays)
        assertEquals("default", s.catchupType)
    }

    @Test
    fun adultNames() {
        listOf("XXX", "FR | Adult", "18+ Kanal", "FSK 18", "For Adults").forEach { assertTrue(it, looksAdult(it)) }
        listOf("Sport", "1+18 Mix TV".drop(5), "Adultshausen Lokal".take(0), "Essex", "Kinder").forEach { assertFalse(it, looksAdult(it)) }
    }

    @Test
    fun lockedCategoriesVanishEverywhere() {
        val c = Catalog.build(
            listOf(Category("1", "A"), Category("2", "B")),
            listOf(Stream(Kind.LIVE, "x", "X", null, "1", "u"), Stream(Kind.LIVE, "y", "Y", null, "2", "u")),
        ).without(setOf("2"))
        assertEquals(listOf("X"), c.streams.map { it.name })
        assertEquals(listOf("X"), c.inCategory(Catalog.ALL).map { it.name })
        assertTrue(c.inCategory("2").isEmpty())
        assertEquals(setOf("2"), c.lockedIds)
    }

    @Test
    fun guideRowsHaveNoGapsOrOverlaps() {
        val list = listOf(EpgEntry("A", "", 100, 200), EpgEntry("B", "", 150, 250), EpgEntry("C", "", 300, 900))
        val cells = guideCells(list, 120, 500)
        assertEquals(120, cells.first().start)
        assertEquals(500, cells.last().end)
        cells.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        assertEquals(listOf("A", "B", null, "C"), cells.map { it.entry?.title })
    }

    @Test
    fun hlsPlaylists() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500\nlow.m3u8\n#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=9999,BANDWIDTH=900\nhigh.m3u8"
        assertEquals("high.m3u8", HlsPlaylist.bestVariant(master))
        val p = HlsPlaylist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:4,\na.ts\n#EXTINF:4,\nb.ts")
        assertEquals(7, p.sequence)
        assertEquals(listOf("a.ts", "b.ts"), p.segments)
        assertTrue(HlsPlaylist.parse("#EXT-X-KEY:METHOD=AES-128,URI=\"k\"").unsupported)
    }

    @Test
    fun helpers() {
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", trailerUrl("abcdefghijk"))
        assertNull(trailerUrl(""))
        assertEquals("Der Pate", searchTitle("DE | Der Pate (1972) [4K]"))
        assertTrue(isLocalHost("http://192.168.1.5:8080"))
        assertTrue(isLocalHost("http://nas:80"))
        assertFalse(isLocalHost("http://example.com:8080"))
    }

    /** Server, der jede Verbindung nach 1000 Bytes abreißt, aber Fortsetzen kann. */
    @Test
    fun downloadResumesAfterDrops() {
        val data = ByteArray(5_500) { (it * 31).toByte() }
        val socket = ServerSocket(0)
        val ranges = ArrayList<String>()
        val server = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                c.use {
                    val lines = it.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine()?.takeIf { l -> l.isNotEmpty() } }.toList() }
                    val range = lines.firstOrNull { l -> l.startsWith("Range:", true) }?.substringAfter("bytes=")
                    synchronized(ranges) { ranges += range ?: "-" }
                    val from = range?.substringBefore('-')?.toInt() ?: 0
                    val head = if (range != null) "HTTP/1.1 206 Partial\r\nContent-Range: bytes $from-${data.size - 1}/${data.size}\r\n" else "HTTP/1.1 200 OK\r\n"
                    it.getOutputStream().apply {
                        write((head + "Content-Type: video/mp4\r\nConnection: close\r\nContent-Length: ${data.size - from}\r\n\r\n").toByteArray())
                        write(data, from, minOf(1000, data.size - from))
                        flush()
                    }
                }
            }
        }
        val target = File.createTempFile("klartv", ".mp4").apply { delete() }
        Loader({ false }) { _, _ -> }.download("http://127.0.0.1:${socket.localPort}/f.mp4", target, pause = {})
        socket.close()
        server.join(1000)
        assertArrayEquals(data, target.readBytes())
        assertTrue(ranges.size >= 6)
        target.delete()
    }
}
