package io.github.phillhf.iptv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AccountStore
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.EngineChoice
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.data.XtreamBackend
import io.github.phillhf.iptv.transfer.TransferState
import io.github.phillhf.iptv.transfer.TransferType
import io.github.phillhf.iptv.transfer.Transfers
import io.github.phillhf.iptv.ui.Tab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/** Läuft im Emulator gegen tools/demo_server.py auf dem Rechner (10.0.2.2) und spielt wirklich ab. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val app: Context = ins.targetContext.applicationContext
    private val server = "http://10.0.2.2:8080"
    private val account = Account("test", "Testanbieter", SourceType.XTREAM, server, "demo", "demo")
    private val shots = File(app.getExternalFilesDir(null), "shots").apply { mkdirs() }

    @Before
    fun prepare() {
        if (Build.VERSION.SDK_INT >= 33) {
            ins.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        ins.runOnMainSync {
            AppSettings.engine = EngineChoice.AUTO
            AppSettings.subtitlesOn = false
            AppSettings.language = "de"
            AppSettings.clearPin()
            I18n.init(app)
            Transfers.list.toList().forEach { Transfers.remove(it) }
        }
        AccountStore(app).apply {
            save(listOf(account))
            activeId = account.id
        }
    }

    @After
    fun closeAll() {
        val open = activities()
        ins.runOnMainSync { open.forEach { it.finish() } }
        Thread.sleep(1500)
    }

    // ---------- Hilfen ----------

    private fun activities(): List<Activity> {
        val out = ArrayList<Activity>()
        ins.runOnMainSync {
            Stage.entries.filter { it != Stage.DESTROYED }.forEach { s ->
                out += ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(s)
            }
        }
        return out.distinct()
    }

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        ins.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(what: String, seconds: Int = 40, describe: () -> String = { "" }, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            if (main(condition)) return
            Thread.sleep(250)
        }
        throw AssertionError("Nicht eingetreten: $what ${main(describe)}")
    }

    private fun shot(name: String) {
        Thread.sleep(800)
        val bmp = ins.uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    /** Alle Texte, die gerade wirklich auf dem Bildschirm stehen (Text und Beschreibung von Knöpfen). */
    private fun onScreen(): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (n.isVisibleToUser) {
                n.text?.let { out += it.toString() }
                n.contentDescription?.let { out += it.toString() }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(ins.uiAutomation.rootInActiveWindow)
        return out
    }

    private fun expectOnScreen(vararg texts: String) {
        val end = System.currentTimeMillis() + 12_000
        var seen = emptyList<String>()
        while (System.currentTimeMillis() < end) {
            seen = onScreen()
            if (texts.all { t -> seen.any { it.contains(t) } }) return
            Thread.sleep(300)
        }
        throw AssertionError("Nicht sichtbar: ${texts.filter { t -> seen.none { it.contains(t) } }}, sichtbar: $seen")
    }

    private fun play(vararg items: PlayItem, index: Int = 0): PlayerActivity {
        PlaybackQueue.items = items.toList()
        PlaybackQueue.startIndex = index
        PlaybackQueue.library = Library(app, "test")
        PlaybackQueue.epg = null
        PlaybackQueue.onPlay = null
        PlaybackQueue.catchup = null
        app.startActivity(Intent(app, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("Player offen") { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is PlayerActivity } }
        return activities().filterIsInstance<PlayerActivity>().first()
    }

    private fun state(p: PlayerActivity) = p.ui.run { "engine=$engineName playing=$playing buffering=$buffering pos=$position dur=$duration msg=$message" }

    private fun expectPlaying(p: PlayerActivity, engine: String, from: Long = 2_500, seconds: Int = 60) =
        waitFor("Wiedergabe mit $engine", seconds, { state(p) }) { p.ui.engineName == engine && p.ui.playing && p.ui.position > from }

    private val liveTs = PlayItem("LIVE:100", "Alpha HD", "$server/live/demo/demo/100.ts", live = true, streamId = "100")
    private val liveHls = PlayItem("LIVE:101", "Nord Eins", "$server/live/demo/demo/101.m3u8", live = true, streamId = "101")
    private val movie = PlayItem("MOVIE:200", "Sternenstaub", "$server/movie/demo/demo/200.mp4", live = false)
    private val dolby = PlayItem("MOVIE:201", "Dolby Nacht", "$server/movie/demo/demo/201.mkv", live = false)

    // ---------- Wiedergabe ----------

    @Test
    fun a1_liveTs() {
        val p = play(liveTs, liveHls)
        expectPlaying(p, "ExoPlayer")
        main { p.showControls(true) }
        expectOnScreen("Alpha HD", "Aufnehmen")
        shot("player_live")
        main { p.step(1) }
        waitFor("Umschalten") { p.ui.index == 1 }
        waitFor("zweiter Sender läuft", 40, { state(p) }) { p.ui.playing && p.ui.position > 0 }
    }

    @Test
    fun a2_movieSeekAndResume() {
        val p = play(movie)
        expectPlaying(p, "ExoPlayer")
        assertTrue("Dauer " + main { p.ui.duration }, main { p.ui.duration } in 115_000..125_000)
        main { p.seekTo(60_000) }
        waitFor("gesprungen", 20, { state(p) }) { p.ui.playing && p.ui.position in 60_000..75_000 }
        main { p.showControls(true) }
        expectOnScreen("Sternenstaub", "Untertitel", "Bild im Bild")
        shot("player_film")
        main { p.openPanel(PlayerPanel.SPEED) }
        expectOnScreen("1.5×")
        main { p.openPanel(null) }
        main { p.finish() }
        waitFor("Position gemerkt", 10) { Library(app, "test").position("MOVIE:200") > 55_000 }
        assertTrue(Library(app, "test").continueList().any { it.key == "MOVIE:200" })
        Thread.sleep(1500)
        val again = play(movie)
        waitFor("macht weiter", 40, { state(again) }) { again.ui.playing && again.ui.position > 58_000 }
        Library(app, "test").clearPosition("MOVIE:200")
    }

    @Test
    fun a3_dolbySwitchesToVlc() {
        val p = play(dolby)
        expectPlaying(p, "VLC")
        main { p.openPanel(PlayerPanel.SUBTITLES) }
        waitFor("Untertitelspur", 10) { p.ui.text.isNotEmpty() }
        main { p.pickText(p.ui.text.first().id) }
        Thread.sleep(1500)
        shot("player_vlc_untertitel")
        main { p.seekTo(30_000) }
        waitFor("VLC springt", 20, { state(p) }) { p.ui.position > 29_000 && p.ui.playing }
    }

    @Test
    fun a4_vlcLiveAndBack() {
        ins.runOnMainSync { AppSettings.engine = EngineChoice.VLC }
        val p = play(liveTs)
        expectPlaying(p, "VLC", from = 1_500)
        main { p.engine(EngineChoice.EXO) }
        expectPlaying(p, "ExoPlayer", from = 1_500)
    }

    @Test
    fun a5_externalSubtitles() {
        val srt = File(app.cacheDir, "test.srt").apply { writeText("1\n00:00:00,500 --> 00:01:30,000\nExterner Untertitel\n") }
        val p = play(movie)
        expectPlaying(p, "ExoPlayer")
        main { p.addSubtitleForTest(Uri.fromFile(srt), "test.srt") }
        waitFor("Untertitel geladen", 25, { state(p) }) {
            p.openPanel(PlayerPanel.SUBTITLES)
            p.ui.text.any { it.selected }
        }
        main { p.openPanel(null) }
        shot("player_untertitel_extern")
    }

    @Test
    fun a6_catchup() {
        val b = XtreamBackend(account)
        b.login()
        val s = b.catalog(Kind.LIVE, "ts").streams.first { it.archiveDays > 0 }
        val past = b.fullEpg(s.id).last { it.end < System.currentTimeMillis() }
        val url = b.catchupUrl(s, past.start, past.end)
        assertNotNull(url)
        val p = play(PlayItem("CU:1", past.title, url!!, live = false, catchup = true))
        expectPlaying(p, "ExoPlayer", from = 1_500)
    }

    @Test
    fun a7_pictureInPicture() {
        if (!app.packageManager.hasSystemFeature("android.software.picture_in_picture")) return
        val p = play(liveTs)
        expectPlaying(p, "ExoPlayer", from = 1_500)
        main { p.pip() }
        waitFor("Bild-in-Bild", 15) { p.ui.inPip }
        val before = main { p.ui.position }
        Thread.sleep(3000)
        assertTrue("läuft weiter", main { p.ui.position } > before)
        shot("bild_in_bild")
    }

    // ---------- Downloads und Aufnahmen ----------

    @Test
    fun b1_download() {
        main { Transfers.download(movie) }
        waitFor("Download fertig", 60, { Transfers.list.toString() }) { Transfers.find("MOVIE:200", TransferType.DOWNLOAD)?.state == TransferState.DONE }
        val x = main { Transfers.find("MOVIE:200", TransferType.DOWNLOAD)!! }
        val expected = java.net.URL(movie.url).openConnection().contentLengthLong
        assertEquals(expected, File(x.file).length())
        val p = play(PlayItem("FILE:1", x.title, Uri.fromFile(File(x.file)).toString(), live = false, local = true))
        expectPlaying(p, "ExoPlayer")
    }

    @Test
    fun b2_recordTs() = record(liveTs)

    @Test
    fun b3_recordHls() = record(liveHls)

    private fun record(item: PlayItem) {
        main { Transfers.record(item.key, "Aufnahme", null, null, item.url, 0, System.currentTimeMillis() + 20_000) }
        waitFor("nimmt auf", 25, { Transfers.list.toString() }) { (Transfers.find(item.key, TransferType.RECORD)?.bytes ?: 0) > 50_000 }
        waitFor("Aufnahme fertig", 60, { Transfers.list.toString() }) { Transfers.find(item.key, TransferType.RECORD)?.state == TransferState.DONE }
        val x = main { Transfers.find(item.key, TransferType.RECORD)!! }
        val p = play(PlayItem("FILE:2", x.title, Uri.fromFile(File(x.file)).toString(), live = false, local = true))
        expectPlaying(p, "ExoPlayer", from = 2_000)
    }

    @Test
    fun b4_scheduledRecording() {
        if (!Transfers.canSchedule()) return
        val now = System.currentTimeMillis()
        // Der Wecker geht 30 Sekunden vor dem Start
        main { Transfers.record("LIVE:102", "Geplant", null, null, "$server/live/demo/demo/102.ts", now + 45_000, now + 60_000) }
        assertEquals(TransferState.SCHEDULED, main { Transfers.find("LIVE:102", TransferType.RECORD)!!.state })
        waitFor("geplante Aufnahme fertig", 120, { Transfers.list.toString() }) { Transfers.find("LIVE:102", TransferType.RECORD)?.state == TransferState.DONE }
    }

    // ---------- Oberfläche ----------

    private fun openApp(): AppViewModel {
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("App offen") { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity } }
        val a = activities().filterIsInstance<MainActivity>().first()
        val vm = main { ViewModelProvider(a)[AppViewModel::class.java] }
        main { Kind.entries.forEach { vm.ensureLoaded(it) } }
        waitFor("Inhalte geladen") { Kind.entries.all { vm.readyCatalog(it) != null } }
        return vm
    }

    @Test
    fun c1_guideAndScreens() {
        val vm = openApp()
        waitFor("Programmführer", 60, { vm.epgState.toString() }) { (vm.epgState as? EpgState.Ready)?.count == 4 * 96 }
        shot("start")
        main { vm.tab = Tab.LIVE }
        Thread.sleep(3000)
        shot("live")
        main { vm.tab = Tab.GUIDE }
        Thread.sleep(3000)
        expectOnScreen("Programm", "Heute", "Alpha HD")
        shot("programm")
        main { vm.tab = Tab.MEDIA }
        shot("mediathek")
        main { vm.tab = Tab.SETTINGS }
        shot("einstellungen")
        main { vm.open(Screen.Series(vm.readyCatalog(Kind.SERIES)!!.streams.first())) }
        Thread.sleep(2000)
        shot("serie")
    }

    @Test
    fun c2_multiView() {
        val vm = openApp()
        main { vm.open(Screen.MultiView) }
        Thread.sleep(1500)
        shot("mehrere_sender")
    }

    @Test
    fun c3_parentalLock() {
        ins.runOnMainSync { AppSettings.setPin("1234") }
        val vm = openApp()
        // „FSK 18“ wird ohne weiteres Zutun gesperrt
        assertTrue(main { vm.readyCatalog(Kind.LIVE)!!.streams.none { it.name == "Doku Welt" } })
        assertTrue(main { vm.hidden("LIVE:103") })
        assertEquals(false, main { AppSettings.checkPin("0000") })
        main { vm.chooseCategory(Kind.LIVE, "2") }
        expectOnScreen("PIN eingeben")
        shot("pin")
        val action = main { vm.pinRequest }
        assertNotNull(action)
        main {
            vm.pinRequest = null
            action!!()
        }
        assertTrue(main { vm.readyCatalog(Kind.LIVE)!!.streams.any { it.name == "Doku Welt" } })
    }
}
