package io.github.phillhf.iptv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Eigene Ordnung der Listen und Wahl der Bildwiederholrate. */
class CustomTest {
    private fun s(id: String, cat: String) = Stream(Kind.LIVE, id, "Sender $id", null, cat, "http://x/$id")

    private val catalog = Catalog.fromGroups(listOf(s("1", "A"), s("2", "A"), s("3", "B"), s("4", "B"), s("5", "C")))

    @Test
    fun hideRenameAndReorder() {
        val c = Custom(
            hiddenStreams = setOf("LIVE:2"),
            names = mapOf("LIVE:3" to "Mein Sender"),
            hiddenGroups = setOf("LIVE:C"),
            groupOrder = mapOf(Kind.LIVE to listOf("B", "A")),
            streamOrder = listOf("LIVE:4", "LIVE:3", "LIVE:1"),
        )
        val v = catalog.customized(Kind.LIVE, c)
        assertEquals(listOf(Catalog.ALL, "B", "A"), v.categories.map { it.id })
        assertEquals(listOf("4", "3", "1"), v.streams.map { it.id })
        assertEquals("Mein Sender", v.byKey["LIVE:3"]!!.name)
        assertEquals(listOf("4", "3"), v.inCategory("B").map { it.id })
        // Andere Arten bleiben unberührt
        assertEquals(catalog.categories, catalog.customized(Kind.MOVIE, Custom(hiddenGroups = setOf("LIVE:C"))).categories)
    }

    @Test
    fun movingSwapsWithVisibleNeighbour() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "d", "c", "b"), moveInOrder(order, listOf("b", "d"), "d", up = true))
        assertEquals(order, moveInOrder(order, listOf("b", "d"), "b", up = true))
        // Neue Einträge vom Anbieter kommen ans Ende
        assertEquals(listOf("b", "a", "x"), moveInOrder(listOf("a", "b"), listOf("a", "b", "x"), "a", up = false))
    }

    @Test
    fun jsonRoundTrip() {
        val c = Custom(setOf("LIVE:1"), mapOf("LIVE:2" to "Ä \"x\""), setOf("MOVIE:9"), mapOf(Kind.SERIES to listOf("3", "1")), listOf("LIVE:2"))
        assertEquals(c, Custom.fromJson(c.toJson()))
        assertEquals(Custom(), Custom.fromJson("kaputt"))
        assertEquals(Custom(), Custom.fromJson(null))
    }

    @Test
    fun refreshRates() {
        val tv = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
        assertEquals(25f, pickRefreshRate(25f, tv))
        assertEquals(23.976f, pickRefreshRate(23.976f, tv))
        assertEquals(29.97f, pickRefreshRate(29.97003f, tv))
        assertEquals(50f, pickRefreshRate(50f, tv))
        val simple = listOf(50f, 59.94f, 60f)
        assertEquals(50f, pickRefreshRate(25f, simple))
        assertEquals(59.94f, pickRefreshRate(29.97f, simple))
        assertEquals(60f, pickRefreshRate(30f, simple))
        assertNull(pickRefreshRate(23.976f, simple))
        assertNull(pickRefreshRate(0f, simple))
    }
}
