package io.github.phillhf.iptv.server

import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.sync.SyncClient
import io.github.phillhf.iptv.sync.SyncHost
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** Ein frisch installierter Server darf beim Abgleich nicht den Stand des Handys durch einen leeren ersetzen. */
class SyncGuardTest {
    @Test
    fun emptyServerGivesNothing() {
        val server = AppContext(File(System.getProperty("klartv.data"), "leer").apply { deleteRecursively() })
        val phone = AppContext(File(System.getProperty("klartv.data"), "handy").apply { deleteRecursively() })
        val host = SyncHost(server, "Klar TV Server")
        try {
            SyncClient.pull(phone, host.link.copy(hosts = listOf("127.0.0.1")))
            throw AssertionError("Hätte abgelehnt werden müssen")
        } catch (e: IOException) {
            // Je nach Sprache des Rechners deutsch oder übersetzt
            assertTrue(e.message, e.message == t("Auf dem anderen Gerät ist noch kein Anbieter eingerichtet. Bitte von hier dorthin senden."))
        } finally {
            host.stop()
        }
    }
}
