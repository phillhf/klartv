package io.github.phillhf.iptv

import android.content.Intent
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.ui.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Bedienung nur mit Fernbedienungstasten; das Skript stellt den Emulator dafür auf 1920×1080 (960×540 dp). */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TvTest : DeviceBase() {
    private fun key(code: Int, times: Int = 1) = repeat(times) {
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

    /** Was gerade den Fokus hat. */
    private fun focusedTexts(): String {
        val n = ins.uiAutomation.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) ?: return ""
        val out = StringBuilder()
        fun walk(x: android.view.accessibility.AccessibilityNodeInfo) {
            x.text?.let { out.append(it).append(' ') }
            x.contentDescription?.let { out.append(it).append(' ') }
            for (i in 0 until x.childCount) x.getChild(i)?.let { walk(it) }
        }
        walk(n)
        return out.toString()
    }

    @Test
    fun d1_liveWithRemote() {
        val vm = openApp()
        main { vm.tab = Tab.LIVE }
        Thread.sleep(1500)
        // Aus der Seitenleiste nach rechts über die Kategorien in die Senderliste
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        var steps = 0
        while (!focusedTexts().contains("Nord Eins") && steps++ < 14) {
            key(if (focusedTexts().contains("Alpha HD")) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        assertTrue("Fokus: ${focusedTexts()}", focusedTexts().contains("Nord Eins"))
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
        assertTrue("Fokus: ${focusedTexts()}", focusedTexts().contains("Pause"))
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
        waitFor("vorgespult", 10, { state(p) }) { p.ui.position > before + 20_000 }
        // Auf den Zeitbalken und dort in Schritten springen
        waitFor("Leiste offen") { p.ui.controls }
        key(KeyEvent.KEYCODE_DPAD_UP)
        val at = main { p.ui.position }
        key(KeyEvent.KEYCODE_DPAD_RIGHT, 2)
        waitFor("Zeitbalken springt", 10, { state(p) + " Fokus: " + at }) { p.ui.position > at + 15_000 }
        shot("tv_zeitbalken")
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        waitFor("Pause-Taste") { !p.ui.playing }
    }

    @Test
    fun d3_startAndGuide() {
        val vm = openApp()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(4) { if (main { vm.focused } == null) key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        shot("tv_start")
        main { vm.tab = Tab.GUIDE }
        Thread.sleep(2500)
        var steps = 0
        while (!focusedTexts().contains("Sendung") && steps++ < 12) {
            key(if (focusedTexts().contains("Alpha HD") || steps > 8) KeyEvent.KEYCODE_DPAD_RIGHT else if (steps < 3) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN)
        }
        assertTrue("Fokus: ${focusedTexts()}", focusedTexts().contains("Sendung"))
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        shot("tv_programm")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        expectOnScreen("Beschreibung zu Sendung", "Aufnehmen")
        shot("tv_sendung")
    }
}
