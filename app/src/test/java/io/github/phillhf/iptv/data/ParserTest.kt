package io.github.phillhf.iptv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ParserTest {
    @Test
    fun serverAddressIsNormalized() {
        assertEquals("http://example.com:8080", normalizeServer(" example.com:8080/ "))
        assertEquals("https://tv.example.com", normalizeServer("https://tv.example.com/player_api.php?username=a&password=b"))
        assertEquals("http://1.2.3.4:80", normalizeServer("http://1.2.3.4:80/get.php?username=a&password=b&type=m3u_plus"))
    }

    @Test
    fun m3uEntriesAreRead() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="das.erste" tvg-logo="http://logo/ard.png" group-title="DE | Vollprogramm",Das Erste, HD
            http://example.com/live/u/p/1.ts
            #EXTINF:-1 group-title="Filme",Ein Film (2020)
            http://example.com/movie/u/p/77.mkv
            #EXTINF:-1,Ohne Gruppe
            #EXTGRP:Sport
            http://example.com/live/u/p/2.ts
        """.trimIndent()
        val list = M3uParser.parse(text)
        assertEquals(3, list.size)
        assertEquals("Das Erste, HD", list[0].name)
        assertEquals("http://logo/ard.png", list[0].logo)
        assertEquals("DE | Vollprogramm", list[0].categoryId)
        assertEquals(Kind.LIVE, list[0].kind)
        assertEquals(Kind.MOVIE, list[1].kind)
        assertEquals("Sport", list[2].categoryId)
    }

    @Test
    fun nameWithCommaInsideAttributes() {
        assertEquals("Sender", M3uParser.nameOf("#EXTINF:-1 tvg-name=\"a,b\" group-title=\"x\",Sender"))
    }

    @Test
    fun base64TitlesAreDecoded() {
        assertEquals("Tagesschau", decodeMaybeBase64("VGFnZXNzY2hhdQ=="))
        assertEquals("Klartext Titel", decodeMaybeBase64("Klartext Titel"))
    }

    @Test
    fun catalogGroupsUnknownCategories() {
        val streams = listOf(
            Stream(Kind.LIVE, "1", "A", null, "10", "u1"),
            Stream(Kind.LIVE, "2", "B", null, "99", "u2"),
        )
        val c = Catalog.build(listOf(Category("10", "News"), Category("11", "Leer")), streams)
        assertEquals(listOf("Alle", "News", "Sonstige"), c.categories.map { it.name })
        assertEquals(1, c.inCategory("10").size)
        assertEquals(2, c.inCategory(Catalog.ALL).size)
    }
}
