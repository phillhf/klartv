package io.github.phillhf.iptv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.LocalAsyncImagePreviewHandler
import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.ui.KlarTheme
import io.github.phillhf.iptv.ui.Tab
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Jede Seite muss sich allein mit den Pfeiltasten der Fernbedienung bedienen lassen. */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-xhdpi")
class FocusTest {
    @get:Rule
    val compose = createComposeRule()

    private val path = ArrayList<String>()

    private fun start(tab: Tab): AppViewModel {
        val app = RuntimeEnvironment.getApplication()
        AppSettings.theme = "NIGHT"
        AppSettings.language = "de"
        AppSettings.clearPin()
        I18n.init(app)
        val vm = AppViewModel(app)
        vm.backendFor = { DemoBackend() }
        runBlocking { vm.addAccount(Account("demo", "Demo", SourceType.XTREAM, "http://demo.invalid", "demo", "demo")) }
        Kind.entries.forEach { vm.ensureLoaded(it) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides FakeImages) {
                val inputMode = LocalInputModeManager.current
                SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
                KlarTheme { App(vm) }
            }
        }
        compose.waitUntil(20_000) { Kind.entries.all { vm.catalog(it) is Load.Ready<*> } }
        compose.runOnUiThread { vm.tab = tab }
        compose.waitForIdle()
        Thread.sleep(800)
        compose.waitForIdle()
        return vm
    }

    private fun focused(): String {
        val nodes = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        return nodes.joinToString(" + ") { n ->
            (n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                .joinToString(" ").ifEmpty { "?" }
        }
    }

    private fun press(vararg keys: Key) {
        for (k in keys) {
            compose.onRoot().performKeyInput { pressKey(k) }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(300)
            path += "$k -> ${focused()}"
        }
    }

    /** Drückt [key], bis der Fokus auf [text] steht. */
    private fun until(key: Key, text: String, limit: Int) {
        repeat(limit) {
            if (focused().contains(text)) return
            press(key)
        }
        File("build/screenshots").mkdirs()
        File("build/screenshots/fokus_${text.filter { it.isLetterOrDigit() }}.txt").writeText(path.joinToString("\n"))
        assertTrue("Nicht erreicht: $text\n${path.joinToString("\n")}", focused().contains(text))
    }

    @Test
    fun live() {
        start(Tab.LIVE)
        press(Key.DirectionDown)
        until(Key.DirectionRight, "Alpha HD", 4)
        until(Key.DirectionDown, "Doku Welt", 8)
        until(Key.DirectionRight, "Favoriten", 2)
        until(Key.DirectionLeft, "Doku Welt", 2)
        until(Key.DirectionLeft, "Start", 5)
    }

    @Test
    fun guide() {
        start(Tab.GUIDE)
        press(Key.DirectionDown)
        until(Key.DirectionRight, "Unterhaltung", 4)
        until(Key.DirectionDown, " – ", 4)
    }

    @Test
    fun movies() {
        start(Tab.MOVIES)
        press(Key.DirectionDown)
        until(Key.DirectionRight, "Sternenstaub", 4)
        until(Key.DirectionDown, "Das Erbe der Wölfe", 2)
        until(Key.DirectionLeft, "Alle", 4)
    }

    @Test
    fun startPage() {
        start(Tab.START)
        press(Key.DirectionDown)
        until(Key.DirectionRight, "Sternenstaub", 4)
        until(Key.DirectionDown, "Neonstadt", 8)
    }

    @Test
    fun settings() {
        start(Tab.SETTINGS)
        press(Key.DirectionDown)
        until(Key.DirectionRight, "Demo", 4)
        until(Key.DirectionDown, "PIN festlegen", 40)
    }
}
