package io.github.phillhf.iptv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.ui.KlarTheme
import io.github.phillhf.iptv.ui.PlayerScreen
import io.github.phillhf.iptv.ui.Tab
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import javax.swing.SwingUtilities
import kotlin.math.abs

/** Fotos aller Seiten in Fenstergröße, mit erfundenen Sendern. */
@OptIn(ExperimentalCoilApi::class)
class ShotsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val out = File(System.getProperty("klartv.out")).apply { mkdirs() }

    private class Owner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val images = AsyncImagePreviewHandler { request ->
        val url = request.data.toString()
        val poster = url.startsWith("demo://poster")
        val w = if (poster) 300 else 640
        val h = if (poster) 450 else 360
        val hue = (abs(url.substringAfterLast('/').hashCode() * 2654435761L) % 360) / 360f
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.createGraphics().apply {
            paint = GradientPaint(0f, 0f, Color.getHSBColor(hue, 0.6f, 0.6f), w.toFloat(), h.toFloat(), Color.getHSBColor(hue + 0.12f, 0.8f, 0.15f))
            fillRect(0, 0, w, h)
            dispose()
        }
        val bmp = Bitmap().apply { allocN32Pixels(w, h, false) }
        val px = (img.raster.dataBuffer as DataBufferInt).data
        val bytes = ByteArray(px.size * 4)
        px.forEachIndexed { i, v ->
            bytes[i * 4] = v.toByte()
            bytes[i * 4 + 1] = (v shr 8).toByte()
            bytes[i * 4 + 2] = (v shr 16).toByte()
            bytes[i * 4 + 3] = (v ushr 24).toByte()
        }
        bmp.installPixels(bytes)
        bmp.asImage()
    }

    private fun viewModel(): AppViewModel {
        val context = AppContext(File(temp.root, "daten"))
        AppSettings.init(context)
        AppSettings.language = "de"
        AppSettings.theme = "NIGHT"
        I18n.init(context)
        Library(context, "demo").apply {
            listOf("LIVE:c0", "LIVE:c6", "LIVE:c10", "MOVIE:m2", "SERIES:s1").forEach { toggleFavorite(it) }
            updateContinue(ContinueEntry("MOVIE:m4", "MOVIE:m4", "Das Erbe der Wölfe", null, "demo://backdrop/m4", "", 2_400_000, 6_300_000))
            updateContinue(ContinueEntry("MOVIE:m7", "MOVIE:m7", "Der stille Zeuge", null, "demo://backdrop/m7", "", 600_000, 5_900_000))
        }
        val vm = AppViewModel(context)
        vm.backendFor = { DemoBackend() }
        runBlocking { vm.addAccount(Account("demo", "Demo-Anbieter", SourceType.XTREAM, "http://demo.invalid", "demo", "demo")) }
        Kind.entries.forEach { vm.ensureLoaded(it) }
        return vm
    }

    private fun onUi(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun shoot(scene: ImageComposeScene, name: String) {
        var time = 0L
        repeat(25) {
            onUi { scene.render(time) }
            time += 60_000_000L
            Thread.sleep(30)
        }
        onUi {
            val data = scene.render(time).encodeToData(EncodedImageFormat.PNG)!!
            File(out, "$name.png").writeBytes(data.bytes)
            assertTrue("$name ist leer", data.size > 15_000)
        }
    }

    @Test
    fun screens() {
        lateinit var vm: AppViewModel
        onUi { vm = viewModel() }
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && Kind.entries.any { vm.catalog(it) !is Load.Ready<*> }) Thread.sleep(50)
        lateinit var scene: ImageComposeScene
        onUi {
            scene = ImageComposeScene(1280, 780, Density(1f)) {
                CompositionLocalProvider(
                    LocalInspectionMode provides true,
                    LocalAsyncImagePreviewHandler provides images,
                    LocalLifecycleOwner provides Owner(),
                ) { KlarTheme { App(vm) } }
            }
        }
        for (tab in listOf(Tab.START, Tab.LIVE, Tab.GUIDE, Tab.MOVIES, Tab.SERIES, Tab.FAVORITES, Tab.MEDIA, Tab.SEARCH, Tab.SETTINGS)) {
            onUi { vm.tab = tab }
            shoot(scene, "win_${tab.name.lowercase()}")
        }
        onUi { vm.open(Screen.Movie(vm.readyCatalog(Kind.MOVIE)!!.streams.first())) }
        shoot(scene, "win_film")
        onUi { vm.back(); vm.open(Screen.MultiView) }
        shoot(scene, "win_mehrere")
        onUi { vm.back(); vm.open(Screen.AddAccount) }
        shoot(scene, "win_anbieter")
        onUi { vm.back(); vm.openSync() }
        shoot(scene, "win_abgleich")
        onUi { vm.closeSync() }
        onUi { scene.close() }
    }

    @Test
    fun player() {
        lateinit var scene: ImageComposeScene
        val ui = PlayerUi().apply {
            items = listOf(PlayItem("MOVIE:1", "Sternenstaub", "", live = false))
            position = 1_500_000
            duration = 6_000_000
            playing = true
            buffering = false
            controls = true
            volume = 0.7f
            canFullscreen = true
            canPip = true
            canCast = false
            canExternal = false
            canSwitchEngine = false
        }
        onUi { scene = ImageComposeScene(1280, 780, Density(1f)) { KlarTheme { PlayerScreen(ui, NoActions) } } }
        shoot(scene, "win_player")
        onUi { scene.close() }
    }

    private object NoActions : PlayerActions by java.lang.reflect.Proxy.newProxyInstance(
        PlayerActions::class.java.classLoader, arrayOf(PlayerActions::class.java), { _, _, _ -> null },
    ) as PlayerActions
}
