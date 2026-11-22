package io.github.phillhf.iptv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
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
import java.io.File

/** Gemeinsame Hilfen der Emulator-Tests gegen tools/demo_server.py (10.0.2.2 ist der Rechner). */
abstract class DeviceBase {
    protected val ins = InstrumentationRegistry.getInstrumentation()
    protected val app: Context = ins.targetContext.applicationContext
    protected val server = "http://10.0.2.2:8080"
    protected val account = Account("test", "Testanbieter", SourceType.XTREAM, server, "demo", "demo")
    protected val shots = File(app.getExternalFilesDir(null), "shots").apply { mkdirs() }

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

    protected fun activities(): List<Activity> {
        val out = ArrayList<Activity>()
        ins.runOnMainSync {
            Stage.entries.filter { it != Stage.DESTROYED }.forEach { s ->
                out += ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(s)
            }
        }
        return out.distinct()
    }

    protected fun <T> main(block: () -> T): T {
        var result: T? = null
        ins.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    protected fun waitFor(what: String, seconds: Int = 40, describe: () -> String = { "" }, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            if (main(condition)) return
            Thread.sleep(250)
        }
        throw AssertionError("Nicht eingetreten: $what ${main(describe)}")
    }

    protected fun shot(name: String) {
        dismissSystemDialogs()
        Thread.sleep(800)
        val bmp = ins.uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    /** Alle Texte, die gerade wirklich auf dem Bildschirm stehen (Text und Beschreibung von Knöpfen). */
    protected fun onScreen(): List<String> {
        if (Build.VERSION.SDK_INT >= 34) ins.uiAutomation.clearCache()
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

    protected fun expectOnScreen(vararg texts: String) {
        val end = System.currentTimeMillis() + 12_000
        var seen = emptyList<String>()
        while (System.currentTimeMillis() < end) {
            dismissSystemDialogs()
            seen = onScreen()
            if (texts.all { t -> seen.any { it.contains(t) } }) return
            Thread.sleep(300)
        }
        throw AssertionError("Nicht sichtbar: ${texts.filter { t -> seen.none { it.contains(t) } }}, sichtbar: $seen")
    }

    protected fun play(vararg items: PlayItem, index: Int = 0): PlayerActivity {
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

    protected fun state(p: PlayerActivity) = p.ui.run { "engine=$engineName playing=$playing buffering=$buffering pos=$position dur=$duration msg=$message" }

    protected fun expectPlaying(p: PlayerActivity, engine: String, from: Long = 2_500, seconds: Int = 60) =
        waitFor("Wiedergabe mit $engine", seconds, { state(p) }) { p.ui.engineName == engine && p.ui.playing && p.ui.position > from }

    protected val liveTs = PlayItem("LIVE:100", "Alpha HD", "$server/live/demo/demo/100.ts", live = true, streamId = "100")
    protected val liveHls = PlayItem("LIVE:101", "Nord Eins", "$server/live/demo/demo/101.m3u8", live = true, streamId = "101")
    protected val movie = PlayItem("MOVIE:200", "Sternenstaub", "$server/movie/demo/demo/200.mp4", live = false)
    protected val dolby = PlayItem("MOVIE:201", "Dolby Nacht", "$server/movie/demo/demo/201.mkv", live = false)

    /** Schließt Dialoge des Systems („App reagiert nicht“), die sonst Tasten und Fotos abfangen. */
    protected fun dismissSystemDialogs() {
        repeat(3) {
            if (Build.VERSION.SDK_INT >= 34) ins.uiAutomation.clearCache()
            val root = ins.uiAutomation.rootInActiveWindow ?: return
            val wait = root.findAccessibilityNodeInfosByViewId("android:id/aerr_wait").firstOrNull()
                ?: root.findAccessibilityNodeInfosByViewId("android:id/aerr_close").firstOrNull()
                ?: return
            wait.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(1000)
        }
    }

    /** Tippt auf das erste Element mit diesem Text, wie ein Finger. */
    protected fun clickOn(text: String, index: Int = 0) {
        val hits = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (n.isVisibleToUser && (n.text?.contains(text) == true || n.contentDescription?.contains(text) == true)) hits += n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        val end = System.currentTimeMillis() + 10_000
        while (hits.size <= index && System.currentTimeMillis() < end) {
            hits.clear()
            dismissSystemDialogs()
            walk(ins.uiAutomation.rootInActiveWindow)
            if (hits.size <= index) Thread.sleep(300)
        }
        assertTrue("Nicht gefunden: $text, sichtbar: ${onScreen()}", hits.size > index)
        var n: AccessibilityNodeInfo? = hits[index]
        while (n != null && !n.isClickable) n = n.parent
        assertTrue("Nicht anklickbar: $text", n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(700)
    }
}
