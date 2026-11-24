package io.github.phillhf.iptv

import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.Backend
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.platform.AppContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.swing.SwingUtilities

/** Fehlt das Cover in der Liste, sucht die App der Reihe nach Ersatz. */
class CoverTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Wie der Demo-Anbieter, aber in der Serienliste ohne Bilder; das Cover steht nur in den Details. */
    private class NoListCovers(private val demo: DemoBackend = DemoBackend()) : Backend by demo {
        override fun catalog(kind: Kind, liveFormat: String): Catalog {
            val c = demo.catalog(kind, liveFormat)
            if (kind != Kind.SERIES) return c
            return Catalog(c.categories, c.streams.map { it.copy(logo = null, backdrop = null) })
        }

        override fun seriesInfo(id: String) = demo.seriesInfo(id).copy(cover = "demo://poster/details-$id", backdrop = null)
    }

    private fun <T> ui(block: () -> T): T {
        var r: T? = null
        SwingUtilities.invokeAndWait { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            if (ui(condition)) return
            Thread.sleep(50)
        }
        throw AssertionError("Nicht eingetreten: $what")
    }

    @Test
    fun fallbackChain() {
        val context = AppContext(File(temp.root, "daten"))
        lateinit var vm: AppViewModel
        ui {
            AppSettings.init(context)
            AppSettings.tmdbKey = ""
            I18n.init(context)
            vm = AppViewModel(context)
            vm.backendFor = { NoListCovers() }
            runBlocking { vm.addAccount(Account("demo", "Demo", SourceType.XTREAM, "http://demo.invalid", "demo", "demo")) }
            vm.ensureLoaded(Kind.SERIES)
        }
        waitFor("Serien") { vm.readyCatalog(Kind.SERIES) != null }
        val s = ui { vm.readyCatalog(Kind.SERIES)!!.streams.first() }
        assertNull(ui { vm.cover(s) })
        // So meldet sich die Kachel, wenn sie kein Bild hat
        ui { vm.coverFailed(s, null) }
        waitFor("Cover aus den Details") { vm.cover(s) == "demo://poster/details-${s.id}" }
        // Gemerkt, auch nach einem Neustart
        assertEquals("demo://poster/details-${s.id}", Library(AppContext(context.dataDir), "demo").covers()[s.key])
        // Lädt auch das nicht und gibt es keinen TMDB-Schlüssel, bleibt der Titel als Platzhalter
        ui { vm.coverFailed(s, "demo://poster/details-${s.id}") }
        waitFor("aufgegeben") { s.key in vm.covers && vm.cover(s) == null }
    }
}
