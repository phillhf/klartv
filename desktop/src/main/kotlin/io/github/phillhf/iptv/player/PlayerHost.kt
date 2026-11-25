package io.github.phillhf.iptv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.phillhf.iptv.PlayItem
import io.github.phillhf.iptv.PlaybackQueue
import io.github.phillhf.iptv.PlayerActions
import io.github.phillhf.iptv.PlayerPanel
import io.github.phillhf.iptv.PlayerUi
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.EngineChoice
import io.github.phillhf.iptv.data.OpenSubtitles
import io.github.phillhf.iptv.data.friendlyError
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.openPrefs
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.transfer.Transfers
import io.github.phillhf.iptv.ui.clockTime
import io.github.phillhf.iptv.ui.currentAndNext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Der offene Player, wenn es einen gibt. Er liegt im selben Fenster über der App. */
object PlayerHost {
    var session by mutableStateOf<PlayerSession?>(null)
        private set

    /** Fenster auf Vollbild oder zurück, von der Oberfläche gesetzt. */
    var setFullscreen: (Boolean) -> Unit = {}
    var setMini: (Boolean) -> Unit = {}

    fun open() {
        val items = PlaybackQueue.items
        if (items.isEmpty()) return
        val current = session
        if (current != null) current.replace(items, PlaybackQueue.startIndex) else session = PlayerSession(items, PlaybackQueue.startIndex)
    }

    fun close() {
        val s = session ?: return
        session = null
        s.release()
        setMini(false)
        setFullscreen(false)
    }
}

/** Steuert die Wiedergabe. Verhält sich wie PlayerActivity auf Android. */
class PlayerSession(items: List<PlayItem>, index: Int) : PlayerActions, VlcPlayer.Listener {
    override val ui = PlayerUi()
    val player = VlcPlayer()
    private val scope: CoroutineScope = MainScope()
    private val prefs = AppContext.default.openPrefs("window")
    private var retries = 0
    private var wasReady = false
    private var released = false
    private var epgJob: Job? = null
    private var hideControls: Job? = null
    private var hideInfo: Job? = null
    private var hideMessage: Job? = null
    private var digitsJob: Job? = null
    private var lastVolume = 1f

    init {
        player.listener = this
        ui.canPip = true
        ui.canFullscreen = true
        ui.canCast = false
        ui.canExternal = false
        ui.canSwitchEngine = false
        ui.engineName = "VLC"
        val v = prefs.getInt("volume", 100) / 100f
        ui.volume = v
        player.setVolume(v)
        ui.items = items
        load(index.coerceIn(items.indices))
        scope.launch {
            var count = 0
            while (true) {
                delay(500)
                ui.position = player.positionMs
                ui.duration = player.durationMs
                if (++count % 20 == 0) savePosition()
                if (ui.sleepAt in 1..System.currentTimeMillis()) close()
            }
        }
    }

    fun replace(items: List<PlayItem>, index: Int) {
        savePosition()
        wasReady = false
        retries = 0
        ui.items = items
        ui.previous = -1
        load(index.coerceIn(items.indices))
    }

    fun release() {
        if (released) return
        savePosition()
        released = true
        scope.cancel()
        player.release()
    }

    // ---------- Laden ----------

    private fun load(index: Int, position: Long? = null) {
        savePosition()
        ui.index = index
        val item = ui.item ?: return
        wasReady = false
        ui.buffering = true
        ui.position = 0
        ui.duration = 0
        ui.canStartOver = false
        val at = position ?: if (item.live) 0L else PlaybackQueue.library?.position(item.key) ?: 0L
        player.sink.clear()
        player.load(item, at)
        player.setSpeed(ui.speed)
        PlaybackQueue.onPlay?.invoke(item)
        showInfo(item)
    }

    // ---------- Meldungen von VLC ----------

    override fun onReady() {
        wasReady = true
        retries = 0
        ui.message = null
        refreshTracks()
    }

    override fun onBuffering(active: Boolean) {
        ui.buffering = active
    }

    override fun onPlaying(playing: Boolean) {
        ui.playing = playing
        if (playing) {
            ui.buffering = false
            bump()
        }
    }

    override fun onEnded() {
        val item = ui.item ?: return
        if (item.live) {
            retry()
            return
        }
        if (!wasReady) {
            onError()
            return
        }
        if (!item.catchup) PlaybackQueue.finished(item.key)
        wasReady = false
        if (ui.index < ui.items.lastIndex) load(ui.index + 1) else close()
    }

    override fun onError() {
        val item = ui.item ?: return
        if (retries < if (item.live) 5 else 2) {
            retry()
        } else {
            ui.buffering = false
            showMessage(t("Wiedergabe nicht möglich (%1).", "VLC"), autoHide = false)
            showControls(true)
        }
    }

    private fun retry() {
        retries++
        if (retries > 5) {
            ui.buffering = false
            showMessage(t("Der Sender sendet gerade nicht."), autoHide = false)
            return
        }
        showMessage(t("Verbindung unterbrochen – neuer Versuch (%1) …", retries), autoHide = true)
        val index = ui.index
        val at = if (ui.item?.live == true) 0L else ui.position
        scope.launch {
            delay(1500L * retries)
            if (ui.index == index) player.load(ui.items[index], at)
        }
    }

    private fun refreshTracks() {
        ui.audio = player.audioTracks()
        ui.text = player.textTracks()
    }

    private fun showMessage(text: String, autoHide: Boolean) {
        ui.message = text
        hideMessage?.cancel()
        if (autoHide) {
            hideMessage = scope.launch {
                delay(5_000)
                ui.message = null
            }
        }
    }

    private fun showInfo(item: PlayItem) {
        ui.epg = emptyList()
        ui.info = true
        hideInfo?.cancel()
        hideInfo = scope.launch {
            delay(5_000)
            ui.info = false
        }
        if (!item.live) return
        val provider = PlaybackQueue.epg ?: return
        epgJob?.cancel()
        epgJob = scope.launch {
            val list = try {
                provider(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (ui.item?.key == item.key) {
                ui.epg = list
                ui.canStartOver = item.archive && currentAndNext(list).first != null
            }
        }
    }

    // ---------- Aktionen der Oberfläche ----------

    private fun bump() {
        hideControls?.cancel()
        hideControls = scope.launch {
            delay(3_500)
            if (ui.panel == null && ui.playing) showControls(false)
        }
    }

    /** Maus bewegt: Leiste zeigen. */
    fun mouseMoved() {
        if (ui.inPip) return
        if (!ui.controls) showControls(true) else bump()
    }

    override fun showControls(visible: Boolean) {
        ui.controls = visible
        if (!visible) ui.seeking = false
        if (visible) {
            ui.info = false
            bump()
        }
    }

    override fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            ui.message = null
            player.play()
        }
        bump()
    }

    override fun seekTo(ms: Long) {
        val limit = ui.duration.takeIf { it > 0 } ?: return
        val to = ms.coerceIn(0, (limit - 1000).coerceAtLeast(0))
        player.seekTo(to)
        ui.position = to
        bump()
    }

    override fun seekBy(ms: Long) = seekTo(player.positionMs + ms)

    override fun touchSeek(forward: Boolean) {
        if (ui.duration > 0) {
            seekBy(if (forward) 10_000 else -10_000)
            showControls(true)
        }
    }

    override fun step(delta: Int) {
        val to = ui.index + delta
        if (to in ui.items.indices) jumpTo(to)
    }

    override fun jumpTo(index: Int) {
        if (index !in ui.items.indices) return
        if (index != ui.index) ui.previous = ui.index
        retries = 0
        ui.message = null
        ui.panel = null
        load(index)
    }

    override fun openPanel(panel: PlayerPanel?) {
        if (panel == PlayerPanel.AUDIO || panel == PlayerPanel.SUBTITLES) refreshTracks()
        ui.panel = panel
        if (panel == null) bump()
    }

    override fun pickAudio(id: String) {
        player.selectAudio(id)
        openPanel(null)
    }

    override fun pickText(id: String?) {
        player.selectText(id)
        openPanel(null)
    }

    override fun loadSubtitleFile() {
        val dialog = FileDialog(null as Frame?, t("Untertitel"), FileDialog.LOAD)
        dialog.file = "*.srt;*.ass;*.ssa;*.vtt;*.sub"
        dialog.isVisible = true
        val name = dialog.file ?: return
        addSubtitle(File(dialog.directory, name))
        openPanel(null)
    }

    private fun addSubtitle(file: File) = player.addSubtitle(file.toURI().toASCIIString().replaceFirst("file:/", "file:///"))

    override fun findSubtitles() {
        val item = ui.item ?: return
        ui.found = null
        ui.panel = PlayerPanel.FIND_SUBTITLES
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { OpenSubtitles.search(item.title, AppSettings.subtitleLanguage.ifEmpty { "de" }) }
            }
            result.onFailure { showMessage(friendlyError(it), autoHide = true) }
            ui.found = result.getOrDefault(emptyList())
        }
    }

    override fun useFound(hit: OpenSubtitles.Hit) {
        scope.launch {
            val folder = File(AppContext.default.dataDir, "untertitel").apply { mkdirs() }
            runCatching { withContext(Dispatchers.IO) { OpenSubtitles.download(hit, folder) } }
                .onSuccess {
                    addSubtitle(it)
                    openPanel(null)
                }
                .onFailure { showMessage(friendlyError(it), autoHide = true) }
        }
    }

    override fun speed(value: Float) {
        ui.speed = value
        player.setSpeed(value)
        openPanel(null)
    }

    override fun scale(mode: ScaleMode) {
        ui.scale = mode
        openPanel(null)
    }

    override fun engine(choice: EngineChoice) = Unit

    override fun sleep(minutes: Int) {
        ui.sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0
        openPanel(null)
    }

    /** Kleines Fenster, das über allen anderen bleibt. */
    override fun pip() {
        ui.inPip = !ui.inPip
        ui.controls = false
        ui.info = false
        ui.panel = null
        PlayerHost.setMini(ui.inPip)
    }

    override fun external() = Unit

    override fun record() {
        val item = ui.item ?: return
        if (!item.live) return
        val running = Transfers.runningRecording(item.key)
        if (running != null) {
            Transfers.stop(running)
            showMessage(t("Aufnahme beendet."), autoHide = true)
            return
        }
        val now = currentAndNext(ui.epg).first
        // Ohne Programmdaten zwei Stunden; mit Programm bis zum Ende der Sendung plus Puffer
        val end = now?.let { it.end + 5 * 60_000 } ?: (System.currentTimeMillis() + 2 * 3_600_000L)
        Transfers.record(item.key, now?.title ?: item.title, item.title, item.image, item.recordUrl ?: item.url, 0, end)
        showMessage(t("Aufnahme läuft bis %1.", clockTime(end)), autoHide = true)
    }

    override fun startOver() {
        val item = ui.item ?: return
        val now = currentAndNext(ui.epg).first ?: return
        scope.launch {
            val again = runCatching { PlaybackQueue.catchup?.invoke(item, now) }.getOrNull()
            if (again == null) {
                showMessage(t("Diese Sendung lässt sich nicht von vorn ansehen."), autoHide = true)
            } else {
                ui.items = listOf(again)
                ui.previous = -1
                load(0, 0)
            }
        }
    }

    override fun cast(routeId: String) = Unit

    override fun volume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        ui.volume = v
        player.setVolume(v)
        prefs.edit().putInt("volume", (v * 100).toInt()).apply()
        bump()
    }

    private fun changeVolume(delta: Float) {
        volume((ui.volume ?: 1f) + delta)
        showMessage(t("Lautstärke %1 %", ((ui.volume ?: 0f) * 100).toInt()), autoHide = true)
    }

    fun mute() {
        val v = ui.volume ?: 1f
        if (v > 0f) {
            lastVolume = v
            volume(0f)
        } else {
            volume(lastVolume.coerceAtLeast(0.1f))
        }
    }

    override fun fullscreen() = PlayerHost.setFullscreen(!ui.fullscreen)

    override fun close() = PlayerHost.close()

    /** Esc: erst Seitenleiste, dann kleines Fenster, dann Vollbild, dann den Player schließen. */
    fun back() {
        when {
            ui.panel != null -> openPanel(null)
            ui.inPip -> pip()
            ui.fullscreen -> PlayerHost.setFullscreen(false)
            else -> close()
        }
    }

    // ---------- Tastatur und Mausrad ----------

    enum class Command { PLAY, BACK, LAST, LEFT, RIGHT, UP, DOWN, FULLSCREEN, MUTE, NEXT, PREVIOUS, RECORD, SUBTITLES, AUDIO, CHANNELS, PIP, OK }

    /** true, wenn die Taste verbraucht ist. */
    fun command(c: Command): Boolean {
        val live = ui.item?.live == true
        if (ui.panel != null && c != Command.BACK) return false
        when (c) {
            Command.PLAY -> togglePlay()
            Command.BACK -> back()
            Command.LAST -> lastChannel()
            Command.LEFT -> if (live) openPanel(PlayerPanel.CHANNELS) else touchSeek(false)
            Command.RIGHT -> if (live) ui.item?.let { showInfo(it) } else touchSeek(true)
            Command.UP -> changeVolume(0.05f)
            Command.DOWN -> changeVolume(-0.05f)
            Command.FULLSCREEN -> fullscreen()
            Command.MUTE -> mute()
            Command.NEXT -> step(1)
            Command.PREVIOUS -> step(-1)
            Command.RECORD -> record()
            Command.SUBTITLES -> openPanel(PlayerPanel.SUBTITLES)
            Command.AUDIO -> openPanel(PlayerPanel.AUDIO)
            Command.CHANNELS -> if (ui.items.size > 1) openPanel(PlayerPanel.CHANNELS)
            Command.PIP -> pip()
            Command.OK -> if (ui.digits.isNotEmpty()) enterDigits() else fullscreen()
        }
        return true
    }

    fun digit(d: Int): Boolean {
        if (ui.item?.live != true || ui.panel != null) return false
        ui.digits = (ui.digits + d).takeLast(5)
        digitsJob?.cancel()
        digitsJob = scope.launch {
            delay(1_800)
            enterDigits()
        }
        return true
    }

    private fun enterDigits() {
        digitsJob?.cancel()
        val number = ui.digits.toIntOrNull()
        ui.digits = ""
        if (number != null && number in 1..ui.items.size) jumpTo(number - 1)
    }

    fun wheel(up: Boolean) {
        if (ui.panel == null) changeVolume(if (up) 0.05f else -0.05f)
    }

    private fun savePosition() {
        val item = ui.item ?: return
        if (item.live || item.catchup || !wasReady) return
        val lib = PlaybackQueue.library ?: return
        val duration = player.durationMs
        val position = player.positionMs
        when {
            duration > 120_000 && position > duration - 60_000 -> PlaybackQueue.finished(item.key)
            position > 5_000 -> {
                lib.savePosition(item.key, position)
                // Dateien vom Gerät gehören nicht zum Anbieter und erscheinen nicht in „Weiterschauen“
                if (!item.local) {
                    lib.updateContinue(
                        ContinueEntry(
                            key = item.key,
                            parentKey = item.recentKey,
                            title = item.title,
                            subtitle = item.subtitle,
                            image = item.image,
                            url = item.url,
                            position = position,
                            duration = duration.coerceAtLeast(0),
                        ),
                    )
                }
            }
        }
    }
}
