package io.github.phillhf.iptv

import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.platform.AppContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Gesehen wie bei Kodi: zu Ende geschaut oder von Hand markiert, auch ganze Staffeln. */
class WatchedTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun finishedAndManual() {
        val context = AppContext(temp.root)
        val lib = Library(context, "a")
        lib.savePosition("EP:1", 600_000)
        lib.updateContinue(ContinueEntry("EP:1", "SERIES:9", "Serie", "S1 E1", null, "u", 600_000, 2_400_000))
        lib.finished("EP:1")
        assertTrue("EP:1" in lib.watched())
        assertEquals(0L, lib.position("EP:1"))
        assertTrue(lib.continueList().isEmpty())
        // Ganze Staffel von Hand, und zurück
        lib.setWatched(listOf("EP:2", "EP:3"), true)
        assertEquals(setOf("EP:1", "EP:2", "EP:3"), lib.watched())
        lib.setWatched(listOf("EP:1"), false)
        assertFalse("EP:1" in lib.watched())
        // Bleibt nach einem Neustart erhalten
        assertEquals(setOf("EP:2", "EP:3"), Library(AppContext(temp.root), "a").watched())
    }

    @Test
    fun seriesEpisodesForPosters() {
        val lib = Library(AppContext(temp.root), "a")
        lib.saveSeriesEpisodes("SERIES:9", listOf("EP:1", "EP:2", "EP:3"))
        assertEquals(mapOf("SERIES:9" to listOf("EP:1", "EP:2", "EP:3")), Library(AppContext(temp.root), "a").seriesEpisodes())
    }
}
