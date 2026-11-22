package io.github.phillhf.iptv

import com.sun.net.httpserver.HttpServer
import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AccountStore
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.player.PlayerHost
import io.github.phillhf.iptv.player.PlayerSession
import io.github.phillhf.iptv.ui.Tab
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.KeyEvent
import java.io.File
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/** Startet das echte Fenster und spielt darin ab. Braucht einen Bildschirm, läuft deshalb nur auf Wunsch. */
class WindowRunTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val out = File(System.getProperty("klartv.out")).apply { mkdirs() }

    private fun <T> ui(block: () -> T): T {
        var r: T? = null
        SwingUtilities.invokeAndWait { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun waitFor(what: String, seconds: Int = 40, describe: () -> String = { "" }, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            if (ui(condition)) return
            Thread.sleep(150)
        }
        throw AssertionError("Nicht eingetreten: $what ${ui(describe)}")
    }

    private fun window() = Frame.getFrames().first { it.isShowing }

    private fun shot(name: String) {
        Thread.sleep(1200)
        val b: Rectangle = ui { window().bounds }
        ImageIO.write(Robot().createScreenCapture(b), "png", File(out, "$name.png"))
    }

    private fun press(code: Int) {
        Robot().apply {
            keyPress(code)
            delay(60)
            keyRelease(code)
        }
        Thread.sleep(700)
    }

    @Test
    fun realWindow() {
        val film = System.getProperty("klartv.testVideo")?.takeIf { it.isNotBlank() }
        assumeTrue("nur mit -Dklartv.window=true", System.getProperty("klartv.window") == "true" && film != null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val list = "#EXTM3U\n" + (1..30).joinToString("\n") { "#EXTINF:-1 tvg-id=\"s$it\" group-title=\"Gruppe ${it % 3}\",Sender $it\nhttp://127.0.0.1:${server.address.port}/s/$it" }
        server.createContext("/s/") { x ->
            x.responseHeaders.add("Location", film!!)
            x.sendResponseHeaders(302, -1)
            x.close()
        }
        server.createContext("/liste.m3u") { x ->
            val b = list.toByteArray()
            x.sendResponseHeaders(200, b.size.toLong())
            x.responseBody.use { it.write(b) }
        }
        server.start()
        System.setProperty("klartv.data", File(temp.root, "daten").path)
        System.setProperty("klartv.videos", File(temp.root, "videos").path)
        val context = AppContext.default
        AccountStore(context).apply {
            save(listOf(Account("t", "Testliste", SourceType.M3U, m3uUrl = "http://127.0.0.1:${server.address.port}/liste.m3u")))
            activeId = "t"
        }
        thread(isDaemon = true, name = "app") { runApp() }
        waitForWindow()
        val vm = mainViewModel!!
        waitFor("Senderliste", 40, { vm.catalog(Kind.LIVE).toString() }) { vm.readyCatalog(Kind.LIVE) != null }
        shot("fenster_start")
        ui { vm.tab = Tab.LIVE }
        Thread.sleep(6000)
        shot("fenster_live")
        val streams = vm.readyCatalog(Kind.LIVE)!!.streams
        assertTrue(streams.size == 30)
        ui { vm.play(context, streams.map { it.toPlayItem() }, 2) }
        val session = PlayerHost.session!!
        val state = { session.ui.run { "playing=$playing buffering=$buffering pos=$position frames=${session.player.sink.count} msg=$message" } }
        waitFor("Wiedergabe", 60, state) { session.player.sink.count > 60 && session.ui.playing }
        ui { session.mouseMoved() }
        shot("fenster_player")
        // Tastatur über das echte Fenster
        ui { window().toFront(); window().requestFocus() }
        press(KeyEvent.VK_F)
        waitFor("Vollbild", 10, state) { session.ui.fullscreen }
        shot("fenster_vollbild")
        press(KeyEvent.VK_ESCAPE)
        waitFor("kein Vollbild", 10, state) { !session.ui.fullscreen && PlayerHost.session != null }
        press(KeyEvent.VK_PAGE_UP)
        waitFor("nächster Sender", 10, state) { session.ui.index == 3 }
        waitFor("läuft", 40, state) { session.ui.playing && session.player.positionMs > 1500 }
        press(KeyEvent.VK_M)
        waitFor("stumm", 5, state) { session.ui.volume == 0f }
        press(KeyEvent.VK_SPACE)
        waitFor("Pause", 10, state) { !session.ui.playing }
        press(KeyEvent.VK_SPACE)
        waitFor("weiter", 10, state) { session.ui.playing }
        press(KeyEvent.VK_L)
        shot("fenster_senderliste")
        press(KeyEvent.VK_ESCAPE)
        ui { session.command(PlayerSession.Command.PIP) }
        waitFor("kleines Fenster", 5, state) { session.ui.inPip }
        shot("fenster_klein")
        assertTrue(ui { window().width } < 700)
        ui { session.back() }
        Thread.sleep(800)
        press(KeyEvent.VK_ESCAPE)
        waitFor("Player zu", 10, state) { PlayerHost.session == null }
        shot("fenster_zurueck")
        server.stop(0)
    }

    private fun waitForWindow() {
        val end = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < end) {
            if (Frame.getFrames().any { it.isShowing } && mainViewModel != null) return
            Thread.sleep(200)
        }
        throw AssertionError("Kein Fenster")
    }
}
