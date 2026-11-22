package io.github.phillhf.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.BackStack
import io.github.phillhf.iptv.platform.appIcon
import io.github.phillhf.iptv.platform.openPrefs
import io.github.phillhf.iptv.player.PlayerHost
import io.github.phillhf.iptv.player.PlayerSession
import io.github.phillhf.iptv.player.PlayerSession.Command
import io.github.phillhf.iptv.player.VlcVideo
import io.github.phillhf.iptv.transfer.Transfers
import io.github.phillhf.iptv.ui.Klar
import io.github.phillhf.iptv.ui.KlarTheme
import io.github.phillhf.iptv.ui.PlayerScreen
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.exitProcess

private const val TITLE = "Klar TV"

fun main() {
    if (!onlyInstance()) exitProcess(0)
    runApp()
    exitProcess(0)
}

/** Für den Fenster-Test erreichbar. */
internal var mainViewModel: AppViewModel? = null
    private set

internal fun runApp() {
    val context = AppContext.default
    AppSettings.init(context)
    I18n.init(context)
    val vm = AppViewModel(context)
    mainViewModel = vm
    Transfers.init(context)
    application {
        val prefs = remember { context.openPrefs("window") }
        val state = rememberWindowState(
            placement = if (prefs.getBoolean("max", false)) WindowPlacement.Maximized else WindowPlacement.Floating,
            size = startSize(prefs.getInt("w", 1280), prefs.getInt("h", 780)),
            position = WindowPosition(androidx.compose.ui.Alignment.Center),
        )
        var asking by remember { mutableStateOf(false) }
        val quit = {
            PlayerHost.close()
            if (state.placement != WindowPlacement.Fullscreen) {
                val e = prefs.edit().putBoolean("max", state.placement == WindowPlacement.Maximized)
                if (state.placement == WindowPlacement.Floating) e.putInt("w", state.size.width.value.toInt()).putInt("h", state.size.height.value.toInt())
                e.apply()
            }
            Transfers.shutdown()
            exitApplication()
        }
        Window(
            onCloseRequest = { if (Transfers.busy) asking = true else quit() },
            state = state,
            title = PlayerHost.session?.ui?.item?.title?.let { "$it – $TITLE" } ?: TITLE,
            icon = appIcon(),
            onPreviewKeyEvent = { handleKey(it) },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(360, 220)
                darkTitleBar(window)
            }
            val before = remember { arrayOf(state.placement, state.placement) }
            LaunchedEffect(Unit) {
                PlayerHost.setFullscreen = { on ->
                    val full = state.placement == WindowPlacement.Fullscreen
                    if (on && !full) {
                        before[0] = state.placement
                        state.placement = WindowPlacement.Fullscreen
                    } else if (!on && full) {
                        state.placement = before[0]
                    }
                    PlayerHost.session?.ui?.fullscreen = on
                }
                var size = state.size
                var position = state.position
                var mini = false
                PlayerHost.setMini = { on ->
                    if (on && !mini) {
                        PlayerHost.setFullscreen(false)
                        before[1] = state.placement
                        state.placement = WindowPlacement.Floating
                        size = state.size
                        position = state.position
                        val screen = Toolkit.getDefaultToolkit().screenSize
                        state.size = DpSize(480.dp, 310.dp)
                        state.position = WindowPosition((screen.width - 500).dp, (screen.height - 370).dp)
                        window.isAlwaysOnTop = true
                        mini = true
                    } else if (!on && mini) {
                        window.isAlwaysOnTop = false
                        state.size = size
                        state.position = position
                        state.placement = before[1]
                        mini = false
                    }
                }
            }
            KlarTheme {
                Root(vm)
                if (asking) {
                    AlertDialog(
                        onDismissRequest = { asking = false },
                        containerColor = Klar.Surface,
                        title = { Text(t("Klar TV schließen?")) },
                        text = { Text(t("Es läuft noch ein Download oder eine Aufnahme. Beim Schließen wird das beendet.")) },
                        confirmButton = { TextButton(onClick = quit) { Text(t("Trotzdem schließen")) } },
                        dismissButton = { TextButton(onClick = { asking = false }) { Text(t("Abbrechen")) } },
                    )
                }
            }
            val playing = PlayerHost.session?.ui?.playing == true
            LaunchedEffect(playing, Transfers.busy) {
                // Bildschirm an lassen, solange etwas läuft; bei Aufnahmen nur den Rechner wach halten
                while (playing || Transfers.busy) {
                    keepAwake(screen = playing)
                    delay(30_000)
                }
            }
        }
    }
}

/** Nicht größer als der Bildschirm ohne Taskleiste. */
private fun startSize(w: Int, h: Int): DpSize {
    val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    return DpSize(w.coerceIn(700, maxOf(700, area.width)).dp, h.coerceIn(500, maxOf(500, area.height)).dp)
}

/** Wie Androids Lebenszyklus: Solange der Player offen ist, ruht die App darunter. */
private class ScreenOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun active(on: Boolean) {
        registry.currentState = if (on) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Root(vm: AppViewModel) {
    val session = PlayerHost.session
    val owner = remember { ScreenOwner().apply { active(true) } }
    LaunchedEffect(session == null) { owner.active(session == null) }
    Box(
        Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) {
            // Zurück-Taste der Maus
            if (it.button == PointerButton.Back) goBack()
        },
    ) {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) { App(vm) }
        session?.let { PlayerLayer(it) }
    }
}

private val hiddenPointer by lazy {
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "leer"))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerLayer(session: PlayerSession) {
    val ui = session.ui
    val hide = !ui.controls && ui.panel == null && !ui.inPip
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerHoverIcon(if (hide) hiddenPointer else PointerIcon.Default, overrideDescendants = hide)
            .onPointerEvent(PointerEventType.Move) { session.mouseMoved() }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val y = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (y != 0f) session.wheel(up = y < 0)
            },
    ) {
        VlcVideo(session.player, ui.scale, Modifier.fillMaxSize())
        PlayerScreen(ui, session)
        if (ui.inPip) {
            // Doppelklick bringt das große Fenster zurück
            Box(
                Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) {
                    if (it.awtEventOrNull()?.clickCount == 2) session.pip()
                },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun androidx.compose.ui.input.pointer.PointerEvent.awtEventOrNull(): java.awt.event.MouseEvent? =
    nativeEvent as? java.awt.event.MouseEvent

private fun goBack(): Boolean {
    val session = PlayerHost.session
    if (session != null) {
        session.back()
        return true
    }
    return BackStack.back()
}

private fun handleKey(e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    if (e.key == Key.DirectionLeft && e.isAltPressed) return goBack()
    if (e.isAltPressed || e.isCtrlPressed || e.isMetaPressed) return false
    val session = PlayerHost.session
    if (session == null) {
        // In der App gehören die Tasten den Feldern; Esc geht zurück
        return e.key == Key.Escape && BackStack.back()
    }
    val command = when (e.key) {
        Key.Escape, Key.Backspace -> Command.BACK
        Key.Spacebar, Key.K, Key.MediaPlayPause -> Command.PLAY
        Key.DirectionLeft -> Command.LEFT
        Key.DirectionRight -> Command.RIGHT
        Key.DirectionUp -> Command.UP
        Key.DirectionDown -> Command.DOWN
        Key.F, Key.F11 -> Command.FULLSCREEN
        Key.M -> Command.MUTE
        Key.PageUp, Key.N, Key.MediaNext -> Command.NEXT
        Key.PageDown, Key.P, Key.MediaPrevious -> Command.PREVIOUS
        Key.R -> Command.RECORD
        Key.U -> Command.SUBTITLES
        Key.T -> Command.AUDIO
        Key.L -> Command.CHANNELS
        Key.I -> Command.PIP
        Key.Enter, Key.NumPadEnter -> Command.OK
        else -> null
    }
    if (command != null) return session.command(command)
    val digits = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    val pad = listOf(Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9)
    val d = digits.indexOf(e.key).takeIf { it >= 0 } ?: pad.indexOf(e.key)
    return d >= 0 && session.digit(d)
}

// ---------- Windows ----------

private var lockFile: RandomAccessFile? = null

/** Ein zweiter Start holt nur das schon offene Fenster nach vorn. */
private fun onlyInstance(): Boolean = try {
    val f = RandomAccessFile(File(AppContext.default.dataDir, "laeuft.lock"), "rw")
    if (f.channel.tryLock() != null) {
        lockFile = f
        true
    } else {
        User32.INSTANCE.FindWindow(null, TITLE)?.let {
            User32.INSTANCE.ShowWindow(it, 9)
            User32.INSTANCE.SetForegroundWindow(it)
        }
        false
    }
} catch (e: java.io.IOException) {
    true
}

private interface Dwm : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: Pointer, size: Int): Int
}

private fun darkTitleBar(window: Window) {
    runCatching {
        val dwm = Native.load("dwmapi", Dwm::class.java)
        val on = com.sun.jna.Memory(4).apply { setInt(0, 1) }
        dwm.DwmSetWindowAttribute(WinDef.HWND(Native.getWindowPointer(window)), 20, on, 4)
    }
}

private fun keepAwake(screen: Boolean) {
    val flags = WinBase.ES_SYSTEM_REQUIRED or if (screen) WinBase.ES_DISPLAY_REQUIRED else 0
    runCatching { Kernel32.INSTANCE.SetThreadExecutionState(flags) }
}
