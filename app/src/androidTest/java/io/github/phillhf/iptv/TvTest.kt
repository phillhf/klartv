package io.github.phillhf.iptv

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.ui.Tab
import org.junit.Assert.assertEquals
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Bedienung nur mit Fernbedienungstasten; das Skript stellt den Emulator dafür auf 1920×1080 (960×540 dp). */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TvTest : DeviceBase() {
    private fun key(code: Int, times: Int = 1) = repeat(times) {
        dismissSystemDialogs()
        ins.sendKeyDownUpSync(code)
        Thread.sleep(350)
    }

    private fun openApp(): AppViewModel {
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("App offen") { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity } }
        val a = activities().filterIsInstance<MainActivity>().first()
        val vm = main { ViewModelProvider(a)[AppViewModel::class.java] }
        main { Kind.entries.forEach { vm.ensureLoaded(it) } }
        waitFor("Inhalte geladen") { Kind.entries.all { vm.readyCatalog(it) != null } }
        Thread.sleep(1500)
        return vm
    }

    private fun player(): PlayerActivity? = activities().filterIsInstance<PlayerActivity>().firstOrNull()

    @Test
    fun d1_liveWithRemote() {
        val vm = openApp()
        main { vm.tab = Tab.LIVE }
        Thread.sleep(1500)
        // Erst „Mehrere Sender“, dann Alpha HD, dann Nord Eins (der Weg dorthin steht in FocusTest)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        Thread.sleep(6000)
        shot("tv_live_vorschau")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Vollbild") { player0() != null }
        val p = player()!!
        assertEquals("Nord Eins", main { p.ui.item?.title })
        expectPlaying(p, "ExoPlayer", from = 1_000)

        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("Hoch schaltet weiter") { p.ui.item?.title == "Sport Arena" }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("Runter schaltet zurück") { p.ui.item?.title == "Nord Eins" }
        key(KeyEvent.KEYCODE_4)
        waitFor("Ziffer springt", 10) { p.ui.item?.title == "Doku Welt" }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("Senderliste offen") { p.ui.panel == PlayerPanel.CHANNELS }
        Thread.sleep(800)
        shot("tv_senderliste")
        key(KeyEvent.KEYCODE_DPAD_UP, 3)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("aus der Liste gewählt", 10) { p.ui.item?.title == "Alpha HD" && p.ui.panel == null }
        expectPlaying(p, "ExoPlayer", from = 1_000)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Leiste offen") { p.ui.controls }
        Thread.sleep(600)
        shot("tv_player_leiste")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("pausiert") { !p.ui.playing }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("läuft wieder") { p.ui.playing }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("Leiste zu oder Player zu") { !p.ui.controls || p.isFinishing }
        if (!main { p.isFinishing }) key(KeyEvent.KEYCODE_BACK)
        waitFor("zurück in der App") { p.isFinishing || p.isDestroyed }
    }

    private fun player0() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is PlayerActivity }

    @Test
    fun d2_movieSeekWithRemote() {
        val p = play(movie)
        expectPlaying(p, "ExoPlayer")
        val before = main { p.ui.position }
        key(KeyEvent.KEYCODE_DPAD_RIGHT, 3)
        waitFor("dreimal vorgespult", 3, { state(p) }) { p.ui.position > before + 28_000 && p.ui.controls }
        shot("tv_zeitbalken")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("zurückgespult", 3, { state(p) }) { p.ui.position < before + 28_000 }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("OK hält an", 5, { state(p) }) { !p.ui.playing }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("OK spielt weiter", 5, { state(p) }) { p.ui.playing }
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        waitFor("Pause-Taste") { !p.ui.playing }
    }

    @Test
    fun d3_startAndGuide() {
        val vm = openApp()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        shot("tv_start")
        main { vm.tab = Tab.GUIDE }
        Thread.sleep(2500)
        // „Alle“, dann Alpha HD, dann die laufende Sendung
        key(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        shot("tv_programm")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        expectOnScreen("Beschreibung zu Sendung")
        // Kurz nach einem Wechsel steht links noch der Rest der vorigen Sendung
        if (onScreen().none { it.contains("Aufnehmen") }) {
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        expectOnScreen("Beschreibung zu Sendung", "Aufnehmen", "Sender ansehen")
        shot("tv_sendung")
    }

    /** OK gedrückt halten, wie eine echte Fernbedienung: Wiederholungen, dann loslassen. */
    private fun holdOk() {
        dismissSystemDialogs()
        val down = SystemClock.uptimeMillis()
        repeat(16) { n ->
            val flags = if (n == 1) KeyEvent.FLAG_LONG_PRESS else 0
            ins.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, n, 0, 0, 0, flags))
            Thread.sleep(if (n == 0) 500 else 50)
        }
        ins.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        Thread.sleep(800)
    }

    @Test
    fun d4_removeContinue() {
        val lib = Library(app, "test")
        lib.continueList().forEach { lib.removeContinue(it.key) }
        listOf("MOVIE:200" to "Sternenstaub", "MOVIE:201" to "Dolby Nacht").forEach { (key, title) ->
            lib.updateContinue(ContinueEntry(key, key, title, null, null, "$server/movie/demo/demo/200.mp4", 30_000, 120_000))
        }
        val vm = openApp()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        holdOk()
        expectOnScreen("Aus „Weiterschauen“ entfernen", "Fortsetzen")
        shot("tv_weiterschauen_menue")
        assertEquals("Langes Drücken darf nichts abspielen", null, player())
        // Fortsetzen, Details, Entfernen, Abbrechen
        key(KeyEvent.KEYCODE_DPAD_DOWN, 5)
        key(KeyEvent.KEYCODE_DPAD_UP)
        shot("tv_weiterschauen_menue_fokus")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("entfernt", 10, { vm.continueList.toString() }) { vm.continueList.map { it.key } == listOf("MOVIE:200") }
        Thread.sleep(1000)
        shot("tv_weiterschauen_entfernt")
        // Der Fokus steht auf dem übrigen Film: OK spielt ihn ab
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("OK spielt den nächsten") { player0() != null }
        val p = player()!!
        assertEquals("Sternenstaub", main { p.ui.item?.title })
        lib.removeContinue("MOVIE:200")
    }

    @Test
    fun d5_hideChannelAndLastChannel() {
        val vm = openApp()
        main { vm.resetCustom() }
        main { vm.tab = Tab.LIVE }
        Thread.sleep(1500)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        holdOk()
        // Steht der Fokus noch auf einer Gruppe, kommt erst deren Menü; dann einen Schritt nach rechts zum Sender
        if (onScreen().any { it.contains("Gruppe ausblenden") }) {
            shot("tv_gruppe_menue")
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            holdOk()
        }
        expectOnScreen("Ausblenden", "Umbenennen")
        shot("tv_sender_menue")
        clickOn("Ausblenden")
        waitFor("ausgeblendet", 10) { vm.custom.hiddenStreams.size == 1 && vm.readyCatalog(Kind.LIVE)?.byKey?.containsKey(vm.custom.hiddenStreams.first()) == false }
        main { vm.resetCustom() }
        waitFor("wieder da", 10) { vm.custom.isEmpty && vm.readyCatalog(Kind.LIVE)?.streams?.size == 4 }
        // Letzter Sender: hin und zurück
        val p = play(liveTs, liveHls, PlayItem("LIVE:102", "Süd Drei", "$server/live/demo/demo/102.ts", live = true, streamId = "102"))
        waitFor("läuft", 40) { p.ui.playing }
        main { p.jumpTo(2) }
        waitFor("Sender 3") { p.ui.index == 2 }
        key(KeyEvent.KEYCODE_LAST_CHANNEL)
        waitFor("zurück zu Sender 1", 10) { p.ui.index == 0 }
        key(KeyEvent.KEYCODE_LAST_CHANNEL)
        waitFor("wieder Sender 3", 10) { p.ui.index == 2 }
    }

    /** QR-Code zeigen und einen Abgleich über das Netz auf demselben Gerät durchspielen. */
    @Test
    fun d6_sync() {
        val vm = openApp()
        main { vm.openSync() }
        expectOnScreen("Geräte abgleichen", "Gültig noch")
        shot("tv_abgleich")
        main { vm.closeSync() }
        main { vm.toggleFavorite(vm.readyCatalog(Kind.LIVE)!!.streams.first()) }
        val favorites = main { vm.favorites }
        val host = io.github.phillhf.iptv.sync.SyncHost(app, "Fernseher")
        val link = host.link.copy(hosts = listOf("127.0.0.1"))
        val answer = Thread {
            while (host.offer == null) Thread.sleep(20)
            val o = host.offer!!
            io.github.phillhf.iptv.sync.SyncData.import(app, o.data)
            o.answer.complete(true)
        }.apply { start() }
        org.junit.Assert.assertTrue(io.github.phillhf.iptv.sync.SyncClient.push(app, link, "Handy"))
        answer.join()
        host.stop()
        main { vm.reloadStored() }
        waitFor("wieder geladen") { vm.active?.id == "test" && vm.favorites == favorites }
        main { vm.toggleFavorite(vm.readyCatalog(Kind.LIVE)!!.streams.first()) }
    }
}
