package io.github.phillhf.iptv.platform

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import io.github.phillhf.iptv.data.Reminder
import io.github.phillhf.iptv.data.Reminders
import io.github.phillhf.iptv.player.PlayerHost
import java.awt.Desktop
import java.io.File
import java.io.InputStream
import java.net.URI

// Was der gemeinsame Code vom System braucht, für Windows. Das Gegenstück für Android liegt in app/src/main.

/** Stellvertreter für Androids Context: Datenordner und Systemaufrufe. */
class AppContext(val dataDir: File) {
    init {
        dataDir.mkdirs()
    }

    private val open = HashMap<String, Prefs>()

    @Synchronized
    fun prefs(name: String): Prefs = open.getOrPut(name) { Prefs(File(dataDir, "$name.dat")) }

    @Synchronized
    fun delete(name: String) {
        open.remove(name)
        File(dataDir, "$name.dat").delete()
    }

    companion object {
        val default: AppContext by lazy {
            val base = System.getProperty("klartv.data") ?: ((System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")) + "\\Klar TV")
            AppContext(File(base))
        }
    }
}

typealias AppApplication = AppContext

open class BaseViewModel(@Suppress("UNUSED_PARAMETER") app: AppApplication) : ViewModel()

val LocalAppContext = staticCompositionLocalOf { AppContext.default }

/** Mit der Maus bedient (Windows). */
const val mouseDriven = true

val appVersion: String by lazy { AppContext::class.java.getResource("/version.txt")?.readText()?.trim() ?: "?" }

fun AppContext.openPrefs(name: String): Prefs = prefs(name)

fun AppContext.deletePrefs(name: String) = delete(name)

fun AppContext.openAsset(path: String): InputStream =
    AppContext::class.java.getResourceAsStream("/$path") ?: throw java.io.FileNotFoundException(path)

@Suppress("UnusedReceiverParameter")
fun AppContext.openUrl(url: String) {
    // Nur Webseiten: Adressen kommen zum Teil vom Anbieter
    if (!url.startsWith("https://") && !url.startsWith("http://")) return
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

/** Unter Windows schaut eine Uhr alle paar Sekunden nach; das geht nur, solange Klar TV offen ist. */
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun AppContext.scheduleReminder(r: Reminder) = ReminderClock.start()

@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun AppContext.cancelReminder(r: Reminder) = Unit

private object ReminderClock {
    private val timer = javax.swing.Timer(10_000) { Reminders.fireDue() }

    fun start() {
        if (!timer.isRunning) timer.start()
    }
}

/** Unter Windows wählt man jeden Ordner selbst, auch verbundene Netzlaufwerke. */
@Suppress("UNUSED_PARAMETER")
fun storageChoices(context: AppContext): List<Pair<String, String>> = emptyList()

const val canPickFolder = true

fun pickFolder(): String? {
    val chooser = javax.swing.JFileChooser().apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY }
    return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile.path else null
}

@Suppress("UNUSED_PARAMETER")
fun deviceName(context: AppContext): String =
    runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: System.getenv("COMPUTERNAME") ?: "PC"

/** Windows zeigt den Code nur, scannen kann es nicht. */
@Suppress("UNUSED_PARAMETER")
fun canScan(context: AppContext): Boolean = false

@Composable
fun QrScanner(onResult: (String) -> Unit, onClose: () -> Unit) {
    LaunchedEffect(Unit) { onClose() }
}

@Suppress("UnusedReceiverParameter")
fun AppContext.openPlayer() = PlayerHost.open()

@Suppress("UNUSED_PARAMETER")
fun isTv(context: AppContext): Boolean = false

/** Windows meldet Wiederholungen nicht; erst nach dem Loslassen zählt ein Tastendruck wieder. */
@Suppress("UNUSED_PARAMETER")
fun isKeyRepeat(event: KeyEvent): Boolean = true

@Composable
fun ServerPermission(@Suppress("UNUSED_PARAMETER") server: String?) {
}

private val bars = ScrollbarStyle(
    minimalHeight = 40.dp, thickness = 8.dp, shape = RoundedCornerShape(4.dp), hoverDurationMillis = 200,
    unhoverColor = Color.White.copy(alpha = 0.18f), hoverColor = Color.White.copy(alpha = 0.5f),
)

@Composable
fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) =
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier.fillMaxHeight().padding(vertical = 4.dp), style = bars)

@Composable
fun ListScrollbar(state: LazyGridState, modifier: Modifier = Modifier) =
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier.fillMaxHeight().padding(vertical = 4.dp), style = bars)

/** Zurück mit Esc, Alt+Links oder der Zurück-Taste der Maus. Der zuletzt angemeldete zählt. */
object BackStack {
    private val handlers = ArrayList<() -> Boolean>()

    fun add(handler: () -> Boolean) {
        handlers += handler
    }

    fun remove(handler: () -> Boolean) {
        handlers -= handler
    }

    fun back(): Boolean = handlers.toList().asReversed().any { it() }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val on by rememberUpdatedState(enabled)
    val run by rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        val handler = {
            val use = on
            if (use) run()
            use
        }
        BackStack.add(handler)
        onDispose { BackStack.remove(handler) }
    }
}

@Composable
fun appIcon(): Painter = remember { IconPainter }

/** Dasselbe Zeichen wie im App-Symbol, im 108er-Raster. */
private object IconPainter : Painter() {
    override val intrinsicSize = Size(108f, 108f)

    override fun DrawScope.onDraw() {
        val accent = Color(0xFF38BDF8)
        scale(size.width / 108f, size.height / 108f, Offset.Zero) {
            drawRoundRect(accent, Offset(30f, 36f), Size(48f, 36f), CornerRadius(4f), Stroke(4f))
            drawPath(
                Path().apply {
                    moveTo(48f, 45f)
                    lineTo(62f, 54f)
                    lineTo(48f, 63f)
                    close()
                },
                accent,
            )
            drawLine(accent, Offset(44f, 79f), Offset(64f, 79f), 4f, StrokeCap.Round)
        }
    }
}
