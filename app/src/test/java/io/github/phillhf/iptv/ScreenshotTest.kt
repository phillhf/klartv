package io.github.phillhf.iptv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.ui.KlarTheme
import io.github.phillhf.iptv.ui.PlayerOverlay
import io.github.phillhf.iptv.ui.PlayerScreen
import io.github.phillhf.iptv.ui.Tab
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val TV = "w960dp-h540dp-land-xhdpi"
private const val PHONE = "w400dp-h860dp-port-xhdpi"

/**
 * Rendert die echten Bildschirme mit einem erfundenen Anbieter als PNG (app/build/screenshots).
 * So lässt sich die Oberfläche ohne Gerät prüfen. Läuft mit ./gradlew recordRoborazziDebug.
 */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun demoViewModel(): AppViewModel {
        val app = RuntimeEnvironment.getApplication()
        AppSettings.theme = "NIGHT"
        AppSettings.language = "de"
        I18n.init(app)
        // Favoriten und „Weiterschauen“ wie nach ein paar Tagen Nutzung
        Library(app, "demo").apply {
            listOf("LIVE:c0", "LIVE:c6", "LIVE:c10", "LIVE:c13", "LIVE:c3", "MOVIE:m2", "SERIES:s1").forEach { toggleFavorite(it) }
            updateContinue(ContinueEntry("MOVIE:m4", "MOVIE:m4", "Das Erbe der Wölfe", null, "demo://backdrop/m4", "", 2_400_000, 6_300_000))
            updateContinue(ContinueEntry("EP:s0-1-3", "SERIES:s0", "Die Akte Nord – S1E3", "Doppeltes Spiel", "demo://backdrop/s0-1-3", "", 1_500_000, 2_700_000))
            updateContinue(ContinueEntry("MOVIE:m7", "MOVIE:m7", "Der stille Zeuge", null, "demo://backdrop/m7", "", 600_000, 5_900_000))
            savePosition("EP:s0-1-3", 1_500_000)
        }
        val vm = AppViewModel(app)
        vm.backendFor = { DemoBackend() }
        runBlocking { vm.addAccount(Account("demo", "Demo-Anbieter", SourceType.XTREAM, "http://demo.invalid", "demo", "demo")) }
        Kind.entries.forEach { vm.ensureLoaded(it) }
        return vm
    }

    private fun shoot(name: String, setup: (AppViewModel) -> Unit = {}, after: () -> Unit = {}) {
        val vm = demoViewModel()
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides FakeImages) {
                // Wie mit Fernbedienung: nur dann gibt es Fokus
                val inputMode = LocalInputModeManager.current
                SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
                KlarTheme { App(vm) }
            }
        }
        compose.waitUntil(20_000) { Kind.entries.all { vm.catalog(it) is Load.Ready<*> } }
        compose.runOnUiThread { setup(vm) }
        compose.waitForIdle()
        after()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun focusOn(description: String, index: Int = 0) {
        compose.onAllNodesWithContentDescription(description)[index]
            .performSemanticsAction(SemanticsActions.RequestFocus)
    }

    @Test
    @Config(qualifiers = TV)
    fun tv_1_start() = shoot("tv_1_start")

    @Test
    @Config(qualifiers = TV)
    fun tv_2_start_fokus() = shoot("tv_2_start_fokus", after = { focusOn("Das Erbe der Wölfe") })

    @Test
    @Config(qualifiers = TV)
    fun tv_3_live() = shoot("tv_3_live", setup = { it.tab = Tab.LIVE })

    @Test
    @Config(qualifiers = TV)
    fun tv_4_filme() = shoot("tv_4_filme", setup = { vm ->
        vm.tab = Tab.MOVIES
        vm.readyCatalog(Kind.MOVIE)?.streams?.get(5)?.let { runBlocking { vm.vodInfo(it) } }
    }, after = { focusOn("Neonstadt") })

    @Test
    @Config(qualifiers = TV)
    fun tv_5_serien() = shoot("tv_5_serien", setup = { it.tab = Tab.SERIES })

    @Test
    @Config(qualifiers = TV)
    fun tv_6_film_detail() = shoot("tv_6_film_detail", setup = { vm ->
        vm.readyCatalog(Kind.MOVIE)?.streams?.get(5)?.let {
            runBlocking { vm.vodInfo(it) }
            vm.open(Screen.Movie(it))
        }
    })

    @Test
    @Config(qualifiers = TV)
    fun tv_7_serie_detail() = shoot("tv_7_serie_detail", setup = { vm ->
        vm.readyCatalog(Kind.SERIES)?.streams?.get(0)?.let { vm.open(Screen.Series(it)) }
    })

    @Test
    @Config(qualifiers = TV)
    fun tv_8_player() {
        val now = System.currentTimeMillis()
        val epg = DemoBackend().epg("c6")
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides FakeImages) {
                KlarTheme {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage("demo://backdrop/spiel", null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        PlayerOverlay(
                            item = PlayItem("LIVE:c6", "Sport Arena 1", "", live = true, streamId = "c6", image = "demo://logo/Sport Arena 1"),
                            epg = epg.map { it.copy(start = it.start - (now - System.currentTimeMillis())) },
                            visible = true,
                            message = null,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("build/screenshots/tv_8_player.png")
    }

    @Test
    @Config(qualifiers = TV)
    fun tv_9_einstellungen() = shoot("tv_9_einstellungen", setup = { it.tab = Tab.SETTINGS })

    @Test
    @Config(qualifiers = TV)
    fun tv_10_programm() = shoot("tv_10_programm", setup = { it.tab = Tab.GUIDE }, after = { Thread.sleep(1500) })

    @Test
    @Config(qualifiers = TV)
    fun tv_11_mediathek() = shoot("tv_11_mediathek", setup = { it.tab = Tab.MEDIA })

    @Test
    @Config(qualifiers = TV)
    fun tv_12_mehrere_sender() = shoot("tv_12_mehrere_sender", setup = { it.open(Screen.MultiView) })

    @Test
    @Config(qualifiers = TV)
    fun tv_13_design_violett() = shoot("tv_13_design_violett", setup = { AppSettings.theme = "VIOLET" })

    @Test
    @Config(qualifiers = TV)
    fun tv_14_englisch() = shoot("tv_14_englisch", setup = {
        AppSettings.language = "en"
        I18n.init(RuntimeEnvironment.getApplication())
    })

    @Test
    @Config(qualifiers = PHONE)
    fun phone_6_programm() = shoot("phone_6_programm", setup = { it.tab = Tab.GUIDE }, after = { Thread.sleep(1500) })

    private fun player(name: String, change: PlayerUi.() -> Unit) {
        val ui = PlayerUi().apply {
            items = listOf(
                PlayItem("LIVE:c6", "Sport Arena 1", "", live = true, streamId = "c6", image = "demo://logo/Sport Arena 1", archive = true),
                PlayItem("LIVE:c7", "Sport Arena 2", "", live = true),
                PlayItem("LIVE:c8", "Motor TV", "", live = true),
            )
            epg = DemoBackend().epg("c6")
            playing = true
            buffering = false
            canPip = true
            canStartOver = true
            engineName = "ExoPlayer"
            change()
        }
        val actions = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PlayerActions::class.java)) { _, _, _ -> null } as PlayerActions
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides FakeImages) {
                val inputMode = LocalInputModeManager.current
                SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
                KlarTheme {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage("demo://backdrop/spiel", null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        PlayerScreen(ui, actions)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = TV)
    fun tv_15_player_live() = player("tv_15_player_live") { controls = true }

    @Test
    @Config(qualifiers = TV)
    fun tv_16_player_senderliste() = player("tv_16_player_senderliste") { panel = PlayerPanel.CHANNELS }

    @Test
    @Config(qualifiers = PHONE)
    fun phone_7_player_film() = player("phone_7_player_film") {
        items = listOf(PlayItem("MOVIE:m1", "Die letzte Grenze", "", live = false))
        duration = 6_300_000
        position = 2_400_000
        canStartOver = false
        controls = true
    }

    @Test
    @Config(qualifiers = PHONE)
    fun phone_1_start() = shoot("phone_1_start")

    @Test
    @Config(qualifiers = PHONE)
    fun phone_2_live() = shoot("phone_2_live", setup = { it.tab = Tab.LIVE })

    @Test
    @Config(qualifiers = PHONE)
    fun phone_3_filme() = shoot("phone_3_filme", setup = { it.tab = Tab.MOVIES })

    @Test
    @Config(qualifiers = PHONE)
    fun phone_4_film_detail() = shoot("phone_4_film_detail", setup = { vm ->
        vm.readyCatalog(Kind.MOVIE)?.streams?.get(5)?.let {
            runBlocking { vm.vodInfo(it) }
            vm.open(Screen.Movie(it))
        }
    })

    @Test
    @Config(qualifiers = PHONE)
    fun phone_5_serie_detail() = shoot("phone_5_serie_detail", setup = { vm ->
        vm.readyCatalog(Kind.SERIES)?.streams?.get(0)?.let { vm.open(Screen.Series(it)) }
    })
}
