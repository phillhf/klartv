package io.github.phillhf.iptv

import com.sun.net.httpserver.HttpServer
import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AccountStore
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.EpgDb
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.Prefs
import io.github.phillhf.iptv.platform.openPrefs
import io.github.phillhf.iptv.player.Vlc
import io.github.phillhf.iptv.player.VlcPlayer
import io.github.phillhf.iptv.transfer.TransferState
import io.github.phillhf.iptv.transfer.TransferType
import io.github.phillhf.iptv.transfer.Transfers
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import javax.swing.SwingUtilities

/** Was es nur unter Windows gibt: Speicher, Programmführer, Downloads und die VLC-Anbindung. */
class WindowsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: AppContext

    @Before
    fun prepare() {
        System.setProperty("klartv.videos", File(temp.root, "videos").path)
        context = AppContext(File(temp.root, "daten"))
        AppSettings.init(context)
        AppSettings.language = "de"
        I18n.init(context)
    }

    private fun waitFor(what: String, seconds: Int = 30, describe: () -> String = { "" }, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            var ok = false
            SwingUtilities.invokeAndWait { ok = condition() }
            if (ok) return
            Thread.sleep(100)
        }
        throw AssertionError("Nicht eingetreten: $what ${describe()}")
    }

    @Test
    fun prefsKeepTypesAndAreEncrypted() {
        val file = File(temp.root, "p.dat")
        Prefs(file).edit().putString("s", "geheim ä€").putInt("i", 5).putLong("l", 5L).putBoolean("b", true).putFloat("f", 1.5f).apply()
        val p = Prefs(file)
        assertEquals("geheim ä€", p.getString("s", null))
        assertEquals(5, p.all["i"])
        assertEquals(5L, p.all["l"])
        assertEquals(1.5f, p.all["f"])
        assertTrue(p.getBoolean("b", false))
        assertNull(p.getString("fehlt", null))
        assertFalse("Klartext in der Datei", String(file.readBytes(), Charsets.ISO_8859_1).contains("geheim"))
        p.edit().remove("s").apply()
        assertNull(Prefs(file).getString("s", null))
        // Beschädigte Datei: leer anfangen, altes aufheben
        file.writeText("kaputt")
        assertTrue(Prefs(file).all.isEmpty())
        assertTrue(File(file.path + ".alt").exists())
    }

    // Unter Android geht das nur mit Robolectric
    @Test
    fun customJsonRoundTrip() {
        val c = io.github.phillhf.iptv.data.Custom(setOf("LIVE:1"), mapOf("LIVE:2" to "Ä \"x\""), setOf("MOVIE:9"), mapOf(io.github.phillhf.iptv.data.Kind.SERIES to listOf("3", "1")), listOf("LIVE:2"))
        assertEquals(c, io.github.phillhf.iptv.data.Custom.fromJson(c.toJson()))
        assertEquals(io.github.phillhf.iptv.data.Custom(), io.github.phillhf.iptv.data.Custom.fromJson("kaputt"))
        assertEquals(io.github.phillhf.iptv.data.Custom(), io.github.phillhf.iptv.data.Custom.fromJson(null))
    }

    @Test
    fun syncBetweenDevices() {
        val phone = AppContext(File(temp.root, "handy"))
        val tv = AppContext(File(temp.root, "fernseher"))
        AccountStore(phone).apply {
            save(listOf(Account("a", "Anbieter", SourceType.XTREAM, "http://a.example", "u", "geheim")))
            activeId = "a"
        }
        Library(phone, "a").apply {
            toggleFavorite("LIVE:7")
            savePosition("MOVIE:1", 123_456L)
            saveCustom(io.github.phillhf.iptv.data.Custom(hiddenStreams = setOf("LIVE:9")))
        }
        phone.openPrefs("settings").edit().putString("theme", "VIOLET").putBoolean("matchFrameRate", true).putInt("subtitleSize", 130).apply()
        // Der Fernseher hat schon eigene Daten und eigene Geräteeinstellungen
        AccountStore(tv).save(listOf(Account("alt", "Alt", SourceType.M3U, m3uUrl = "http://alt")))
        Library(tv, "alt").toggleFavorite("LIVE:1")
        tv.openPrefs("settings").edit().putBoolean("matchFrameRate", false).putString("storeTarget", "file:D:/TV").apply()

        // Handy schickt, Fernseher bestätigt
        val host = io.github.phillhf.iptv.sync.SyncHost(tv, "Fernseher")
        val link = io.github.phillhf.iptv.sync.SyncLink.parse(host.link.toUri())!!
        assertEquals(host.link, link)
        val answer = Thread {
            while (host.offer == null) Thread.sleep(20)
            val o = host.offer!!
            assertEquals("Handy", o.device)
            io.github.phillhf.iptv.sync.SyncData.import(tv, o.data)
            o.answer.complete(true)
        }.apply { start() }
        assertTrue(io.github.phillhf.iptv.sync.SyncClient.push(phone, link.copy(hosts = listOf("127.0.0.1")), "Handy"))
        answer.join()
        host.stop()
        val tv2 = AppContext(tv.dataDir)
        assertEquals(listOf("a"), AccountStore(tv2).all().map { it.id })
        assertEquals("geheim", AccountStore(tv2).all().first().password)
        assertEquals(listOf("LIVE:7"), Library(tv2, "a").favorites())
        assertEquals(123_456L, Library(tv2, "a").position("MOVIE:1"))
        assertEquals(setOf("LIVE:9"), Library(tv2, "a").custom().hiddenStreams)
        assertTrue(Library(tv2, "alt").favorites().isEmpty())
        val settings = tv2.openPrefs("settings")
        assertEquals("VIOLET", settings.getString("theme", null))
        assertEquals(130, settings.getInt("subtitleSize", 0))
        assertEquals(false, settings.getBoolean("matchFrameRate", true))
        assertEquals("file:D:/TV", settings.getString("storeTarget", null))

        // Zweiter Versuch mit demselben Code geht nicht
        val again = runCatching { io.github.phillhf.iptv.sync.SyncClient.push(phone, link.copy(hosts = listOf("127.0.0.1")), "Handy") }
        assertTrue(again.isFailure)

        // Andersherum: Handy holt vom Fernseher
        AccountStore(tv2).save(listOf(Account("b", "Neu", SourceType.M3U, m3uUrl = "http://b")))
        val host2 = io.github.phillhf.iptv.sync.SyncHost(tv2, "Fernseher")
        val name = io.github.phillhf.iptv.sync.SyncClient.pull(phone, host2.link.copy(hosts = listOf("127.0.0.1")))
        host2.stop()
        assertEquals("Fernseher", name)
        assertEquals(listOf("b"), AccountStore(AppContext(phone.dataDir)).all().map { it.id })
        // Falscher Schlüssel
        val host3 = io.github.phillhf.iptv.sync.SyncHost(tv2, "Fernseher")
        val wrong = host3.link.copy(hosts = listOf("127.0.0.1"), key = ByteArray(32))
        assertTrue(runCatching { io.github.phillhf.iptv.sync.SyncClient.pull(phone, wrong) }.isFailure)
        host3.stop()
    }

    /** Echte Anfrage an TMDB und OpenSubtitles: Ein falscher Schlüssel muss als ungültig erkannt werden. */
    @Test
    fun keyCheckRejectsWrongKeys() {
        AppSettings.tmdbKey = "0123456789abcdef0123456789abcdef"
        AppSettings.openSubtitlesKey = "falsch"
        val tmdb = io.github.phillhf.iptv.data.Tmdb.check()
        val subs = runCatching { io.github.phillhf.iptv.data.OpenSubtitles.check() }.getOrElse { it.toString() }
        println("TMDB: $tmdb, OpenSubtitles: $subs")
        assertEquals("Schlüssel ungültig", tmdb)
        // OpenSubtitles lässt falsche Schlüssel von manchen Rechnern aus durch; nur sicherstellen, dass nichts abstürzt
        AppSettings.tmdbKey = ""
        AppSettings.openSubtitlesKey = ""
    }

    @Test
    fun accountsAndLibrary() {
        val a = Account("x", "Mein Anbieter", SourceType.XTREAM, "http://a.example", "u", "pw")
        AccountStore(context).apply {
            save(listOf(a))
            activeId = "x"
        }
        val again = AppContext(context.dataDir)
        assertEquals(listOf(a), AccountStore(again).all())
        assertEquals("x", AccountStore(again).activeId)
        Library(again, "x").apply {
            toggleFavorite("LIVE:1")
            savePosition("MOVIE:1", 5_000_000_000L)
        }
        assertEquals(listOf("LIVE:1"), Library(AppContext(context.dataDir), "x").favorites())
        assertEquals(5_000_000_000L, Library(AppContext(context.dataDir), "x").position("MOVIE:1"))
    }

    @Test
    fun epgDatabase() {
        val db = EpgDb(context)
        val now = System.currentTimeMillis()
        fun time(ms: Long) = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(ms))
        fun show(i: Int) = "<programme channel=\"a\" start=\"${time(now + i * 1_800_000L)}\" stop=\"${time(now + (i + 1) * 1_800_000L)}\"><title>Nr. $i</title></programme>"
        val xml = "<tv><channel id=\"a\"><display-name>A</display-name></channel>${(0 until 10).joinToString("") { show(it) }}</tv>rest < &"
        val n = db.import("x", xml.byteInputStream(), now - 1, now + 86_400_000, { id, _ -> listOf(id) }, {}, { false })
        assertEquals(10, n)
        assertEquals(10, db.info("x")!!.count)
        assertEquals("Nr. 0", db.programmes("x", "a", now, now + 86_400_000).first().title)
        // Abgebrochen: das alte Programm bleibt
        runCatching { db.import("x", "<html>".byteInputStream(), 0, Long.MAX_VALUE, { id, _ -> listOf(id) }, {}, { false }) }
        assertEquals(10, db.programmes("x", "a", now - 1, now + 86_400_000).size)
        db.remove("x")
        assertNull(db.info("x"))
    }

    @Test
    fun downloadAndRecording() {
        val data = ByteArray(3_000_000) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/film.mp4") { x ->
            x.responseHeaders.add("Content-Type", "video/mp4")
            x.sendResponseHeaders(200, data.size.toLong())
            x.responseBody.use { it.write(data) }
        }
        server.createContext("/live.ts") { x ->
            x.responseHeaders.add("Content-Type", "video/mp2t")
            x.sendResponseHeaders(200, 0)
            runCatching {
                x.responseBody.use { out ->
                    repeat(200) {
                        out.write(ByteArray(188 * 50) { i -> if (i % 188 == 0) 0x47 else 0 })
                        out.flush()
                        Thread.sleep(50)
                    }
                }
            }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            SwingUtilities.invokeAndWait {
                Transfers.init(context)
                Transfers.download(PlayItem("MOVIE:1", "Film: \"Test\"?", "$base/film.mp4", live = false))
                Transfers.record("LIVE:1", "Sendung", "Sender", null, "$base/live.ts", 0, System.currentTimeMillis() + 3_000)
            }
            val state = { Transfers.list.joinToString { "${it.type} ${it.state} ${it.bytes} ${it.error}" } }
            waitFor("Download fertig", 20, state) { Transfers.find("MOVIE:1", TransferType.DOWNLOAD)?.state == TransferState.DONE }
            waitFor("Aufnahme fertig", 20, state) { Transfers.find("LIVE:1", TransferType.RECORD)?.state == TransferState.DONE }
            val film = File(Transfers.find("MOVIE:1", TransferType.DOWNLOAD)!!.file)
            assertTrue(film.readBytes().contentEquals(data))
            assertTrue(File(Transfers.find("LIVE:1", TransferType.RECORD)!!.file).length() > 100_000)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun vlcPlaysAndSeeks() {
        println("libVLC ${Vlc.lib.libvlc_get_version()}")
        val film = System.getProperty("klartv.testVideo")?.takeIf { it.isNotBlank() }
        assumeTrue("ohne Testvideo", film != null)
        val errors = ArrayList<String>()
        lateinit var p: VlcPlayer
        SwingUtilities.invokeAndWait {
            p = VlcPlayer()
            p.listener = object : VlcPlayer.Listener {
                override fun onError() {
                    errors += "Fehler"
                }
            }
            p.setVolume(0f)
            p.load(PlayItem("x", "Test", film!!, live = false), 0)
        }
        val state = { "frames=${p.sink.count} pos=${p.positionMs} dur=${p.durationMs} $errors" }
        waitFor("Bilder", 40, state) { p.sink.count > 30 && p.positionMs > 500 }
        waitFor("Länge", 10, state) { p.durationMs > 5_000 }
        println(state() + " aspect=${p.aspect} audio=${p.audioTracks()} text=${p.textTracks()}")
        val out = File(System.getProperty("klartv.out")).apply { mkdirs() }
        SwingUtilities.invokeAndWait {
            val image = p.sink.current()!!
            println("Bild ${image.width}×${image.height}")
            File(out, "vlc.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        val target = p.durationMs / 2
        SwingUtilities.invokeAndWait { p.seekTo(target) }
        waitFor("gesprungen", 20, state) { p.positionMs > target + 300 }
        SwingUtilities.invokeAndWait { p.pause() }
        waitFor("angehalten", 10, state) { !p.isPlaying }
        Thread.sleep(300)
        val at = p.positionMs
        Thread.sleep(700)
        assertEquals(at, p.positionMs)
        val before = p.sink.count
        SwingUtilities.invokeAndWait { p.play() }
        waitFor("läuft weiter", 10, state) { p.isPlaying && p.sink.count > before + 10 }
        val start = p.sink.count
        Thread.sleep(2000)
        println("Bilder je Sekunde: ${(p.sink.count - start) / 2}")
        // Werte für Selbstheilung und Diagnose: kommen im Hintergrund, beim zweiten Abruf sind sie da
        p.stats()
        waitFor("Statistik", 10, { p.stats().toString() }) { p.stats().shown > 0 }
        println("Statistik: ${p.stats()}")
        SwingUtilities.invokeAndWait { p.release() }
        Thread.sleep(1500)
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun manyPlayersOpenAndClose() {
        val film = System.getProperty("klartv.testVideo")?.takeIf { it.isNotBlank() }
        assumeTrue("ohne Testvideo", film != null)
        repeat(3) {
            val players = ArrayList<VlcPlayer>()
            SwingUtilities.invokeAndWait {
                repeat(4) { players += VlcPlayer(960, 540).apply { setVolume(0f); load(PlayItem("x", "T", film!!, live = false), 0) } }
            }
            waitFor("alle vier laufen", 60, { players.joinToString { it.sink.count.toString() } }) { players.all { it.sink.count > 5 } }
            SwingUtilities.invokeAndWait { players.forEach { it.release() } }
            Thread.sleep(500)
        }
    }
}
