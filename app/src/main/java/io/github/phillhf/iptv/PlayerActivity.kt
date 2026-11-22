package io.github.phillhf.iptv

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.phillhf.iptv.cast.Caster
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.EngineChoice
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.OpenSubtitles
import io.github.phillhf.iptv.data.friendlyError
import io.github.phillhf.iptv.player.Engine
import io.github.phillhf.iptv.player.ExoEngine
import io.github.phillhf.iptv.player.ScaleMode
import io.github.phillhf.iptv.player.Track
import io.github.phillhf.iptv.player.VlcEngine
import io.github.phillhf.iptv.transfer.Transfers
import io.github.phillhf.iptv.ui.KlarTheme
import io.github.phillhf.iptv.ui.PlayerScreen
import io.github.phillhf.iptv.ui.currentAndNext
import io.github.phillhf.iptv.ui.isTv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PlayerPanel { AUDIO, SUBTITLES, SPEED, SCALE, ENGINE, CHANNELS, SLEEP, CAST, FIND_SUBTITLES }

/** Alles, was die Oberfläche des Players anzeigt. */
class PlayerUi {
    var items by mutableStateOf<List<PlayItem>>(emptyList())
    var index by mutableIntStateOf(0)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var controls by mutableStateOf(false)
    var panel by mutableStateOf<PlayerPanel?>(null)
    var info by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var epg by mutableStateOf<List<EpgEntry>>(emptyList())
    var speed by mutableFloatStateOf(1f)
    var scale by mutableStateOf(ScaleMode.FIT)
    var engineName by mutableStateOf("")
    var digits by mutableStateOf("")
    var inPip by mutableStateOf(false)
    var canPip by mutableStateOf(false)
    var canStartOver by mutableStateOf(false)
    var sleepAt by mutableLongStateOf(0L)
    var audio by mutableStateOf<List<Track>>(emptyList())
    var text by mutableStateOf<List<Track>>(emptyList())
    var found by mutableStateOf<List<OpenSubtitles.Hit>?>(null)
    val item: PlayItem? get() = items.getOrNull(index)
}

/** Was die Oberfläche auslösen kann. */
interface PlayerActions {
    fun togglePlay()
    fun seekBy(ms: Long)
    fun seekTo(ms: Long)
    fun step(delta: Int)
    fun jumpTo(index: Int)
    fun openPanel(panel: PlayerPanel?)
    fun pickAudio(id: String)
    fun pickText(id: String?)
    fun loadSubtitleFile()
    fun findSubtitles()
    fun useFound(hit: OpenSubtitles.Hit)
    fun speed(value: Float)
    fun scale(mode: ScaleMode)
    fun engine(choice: EngineChoice)
    fun sleep(minutes: Int)
    fun pip()
    fun external()
    fun record()
    fun startOver()
    fun cast(routeId: String)
    fun showControls(visible: Boolean)
    fun touchSeek(forward: Boolean)
    fun close()
}

class PlayerActivity : ComponentActivity(), PlayerActions, Engine.Listener {
    val ui = PlayerUi()
    private var engine: Engine? = null
    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    private var epgJob: Job? = null
    private var resumeAt = 0L
    private var wasReady = false
    private var started = false

    /** In AUTO gemerkt: dieser Eintrag braucht VLC. */
    private var forced: EngineChoice? = null

    private val openSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) engine?.addSubtitle(uri, displayName(uri))
    }

    private val hideControls = Runnable { if (ui.panel == null && ui.playing) ui.controls = false }
    private val hideInfo = Runnable { ui.info = false }
    private val hideMessage = Runnable { ui.message = null }
    private val enterDigits = Runnable {
        val number = ui.digits.toIntOrNull()
        ui.digits = ""
        if (number != null && number in 1..ui.items.size) jumpTo(number - 1)
    }

    private val tick = object : Runnable {
        private var count = 0
        override fun run() {
            engine?.let {
                ui.position = it.positionMs
                ui.duration = it.durationMs
            }
            if (++count % 20 == 0) savePosition()
            if (ui.sleepAt in 1..System.currentTimeMillis()) {
                finish()
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val all = PlaybackQueue.items
        if (all.isEmpty()) {
            finish()
            return
        }
        ui.items = all
        ui.index = (savedInstanceState?.getInt("index") ?: PlaybackQueue.startIndex).coerceIn(all.indices)
        ui.canPip = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val overlay = ComposeView(this).apply {
            setContent { KlarTheme { PlayerScreen(ui, this@PlayerActivity) } }
        }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(overlay, FrameLayout.LayoutParams(match, match))
        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        ui.panel != null -> openPanel(null)
                        ui.controls && isTv(this@PlayerActivity) -> showControls(false)
                        else -> finish()
                    }
                }
            },
        )
    }

    /** Der Player war noch offen, z. B. klein als Bild-in-Bild: neuen Eintrag übernehmen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val all = PlaybackQueue.items
        if (all.isEmpty() || !::root.isInitialized) return
        savePosition()
        wasReady = false
        forced = null
        retries = 0
        resumeAt = 0
        ui.items = all
        ui.index = PlaybackQueue.startIndex.coerceIn(all.indices)
        if (started) load(ui.index)
    }

    /** Nur für den Emulator-Test: lädt Untertitel ohne Dateiauswahl. */
    internal fun addSubtitleForTest(uri: Uri, label: String) {
        engine?.addSubtitle(uri, label)
    }

    override fun onStart() {
        super.onStart()
        if (ui.items.isEmpty() || started) return
        started = true
        load(ui.index, resumeAt.takeIf { it > 0 })
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        // Im Auto und im Hintergrund darf nichts weiterlaufen; Bild-in-Bild stoppt erst beim Schließen
        started = false
        resumeAt = if (ui.item?.live == true) 0 else engine?.positionMs ?: 0
        handler.removeCallbacksAndMessages(null)
        epgJob?.cancel()
        dropEngine()
        if (ui.inPip) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", ui.index)
    }

    // ---------- Laden und Engine ----------

    private fun wanted(): EngineChoice {
        val choice = forced ?: AppSettings.engine
        return if (choice == EngineChoice.AUTO) EngineChoice.EXO else choice
    }

    private fun load(index: Int, position: Long? = null) {
        savePosition()
        ui.index = index
        val item = ui.item ?: return
        val choice = wanted()
        val current = engine
        val fits = current != null && (current is VlcEngine) == (choice == EngineChoice.VLC)
        if (!fits) {
            dropEngine()
            val created = runCatching { if (choice == EngineChoice.VLC) VlcEngine(this) else ExoEngine(this) }
                .getOrElse { ExoEngine(this) }
            created.listener = this
            engine = created
            val match = ViewGroup.LayoutParams.MATCH_PARENT
            root.addView(created.view, 0, FrameLayout.LayoutParams(match, match))
            created.setScale(ui.scale)
            created.setSpeed(ui.speed)
            ui.engineName = created.name
        }
        wasReady = false
        ui.buffering = true
        ui.position = 0
        ui.duration = 0
        ui.canStartOver = false
        val at = position ?: if (item.live) 0L else PlaybackQueue.library?.position(item.key) ?: 0L
        engine?.load(item, at)
        PlaybackQueue.onPlay?.invoke(item)
        showInfo(item)
    }

    private fun dropEngine() {
        engine?.let {
            it.listener = null
            root.removeView(it.view)
            it.release()
        }
        engine = null
    }

    // ---------- Meldungen der Engine ----------

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

    override fun onVideoSize(width: Int, height: Int) {
        if (!ui.canPip || Build.VERSION.SDK_INT < 26) return
        val ratio = (width.toFloat() / height).coerceIn(0.42f, 2.38f)
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(!isTv(this))
        runCatching { setPictureInPictureParams(b.build()) }
    }

    override fun onEnded() {
        val item = ui.item ?: return
        if (item.live) {
            retry()
            return
        }
        PlaybackQueue.library?.clearPosition(item.key)
        PlaybackQueue.library?.removeContinue(item.key)
        if (ui.index < ui.items.lastIndex) load(ui.index + 1, null) else finish()
    }

    override fun onError(message: String, unsupported: Boolean) {
        val item = ui.item ?: return
        if (unsupported && AppSettings.engine == EngineChoice.AUTO && forced == null && engine is ExoEngine) {
            forced = EngineChoice.VLC
            showMessage(t("Format wird mit VLC abgespielt …"), autoHide = true)
            val at = if (item.live) 0L else engine?.positionMs ?: 0L
            handler.post { load(ui.index, at) }
            return
        }
        // Live bricht öfter kurz ab; auch Filme bekommen nach einem Aussetzer eine zweite Chance
        if (retries < if (item.live) 5 else 2) {
            retry()
        } else {
            ui.buffering = false
            showMessage(t("Wiedergabe nicht möglich (%1).", message), autoHide = false)
            showControls(true)
        }
    }

    private fun retry() {
        retries++
        if (retries > 5) {
            showMessage(t("Der Sender sendet gerade nicht."), autoHide = false)
            return
        }
        showMessage(t("Verbindung unterbrochen – neuer Versuch (%1) …", retries), autoHide = true)
        val index = ui.index
        val at = if (ui.item?.live == true) 0L else ui.position
        handler.postDelayed({ if (started && ui.index == index) engine?.load(ui.items[index], at) }, 1500L * retries)
    }

    private fun refreshTracks() {
        ui.audio = engine?.audioTracks().orEmpty()
        ui.text = engine?.textTracks().orEmpty()
    }

    private fun showMessage(text: String, autoHide: Boolean) {
        ui.message = text
        handler.removeCallbacks(hideMessage)
        if (autoHide) handler.postDelayed(hideMessage, 5_000)
    }

    private fun showInfo(item: PlayItem) {
        ui.epg = emptyList()
        ui.info = true
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 5_000)
        if (!item.live) return
        val provider = PlaybackQueue.epg ?: return
        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
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
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 5_000)
    }

    override fun showControls(visible: Boolean) {
        ui.controls = visible
        if (visible) {
            ui.info = false
            bump()
        }
    }

    override fun togglePlay() {
        val e = engine ?: return
        if (e.isPlaying) {
            e.pause()
        } else {
            ui.message = null
            e.play()
        }
        bump()
    }

    override fun seekTo(ms: Long) {
        val limit = ui.duration.takeIf { it > 0 } ?: return
        val to = ms.coerceIn(0, (limit - 1000).coerceAtLeast(0))
        engine?.seekTo(to)
        ui.position = to
        bump()
    }

    override fun seekBy(ms: Long) = seekTo((engine?.positionMs ?: 0) + ms)

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
        forced = null
        retries = 0
        ui.message = null
        ui.panel = null
        load(index)
    }

    override fun openPanel(panel: PlayerPanel?) {
        if (panel == PlayerPanel.AUDIO || panel == PlayerPanel.SUBTITLES) refreshTracks()
        if (panel == PlayerPanel.CAST) Caster.startDiscovery(this) else Caster.stopDiscovery()
        ui.panel = panel
        if (panel == null) bump()
    }

    override fun pickAudio(id: String) {
        engine?.selectAudio(id)
        openPanel(null)
    }

    override fun pickText(id: String?) {
        engine?.selectText(id)
        openPanel(null)
    }

    override fun loadSubtitleFile() {
        runCatching { openSubtitle.launch(arrayOf("*/*")) }
            .onFailure { showMessage(t("Auf diesem Gerät gibt es keine Dateiauswahl."), autoHide = true) }
    }

    override fun findSubtitles() {
        val item = ui.item ?: return
        ui.found = null
        ui.panel = PlayerPanel.FIND_SUBTITLES
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { OpenSubtitles.search(item.title, AppSettings.subtitleLanguage.ifEmpty { "de" }) }
            }
            result.onFailure { showMessage(friendlyError(it), autoHide = true) }
            ui.found = result.getOrDefault(emptyList())
        }
    }

    override fun useFound(hit: OpenSubtitles.Hit) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { OpenSubtitles.download(hit, cacheDir) } }
                .onSuccess { file ->
                    engine?.addSubtitle(Uri.fromFile(file), file.name)
                    openPanel(null)
                }
                .onFailure { showMessage(friendlyError(it), autoHide = true) }
        }
    }

    override fun speed(value: Float) {
        ui.speed = value
        engine?.setSpeed(value)
        openPanel(null)
    }

    override fun scale(mode: ScaleMode) {
        ui.scale = mode
        engine?.setScale(mode)
        openPanel(null)
    }

    override fun engine(choice: EngineChoice) {
        val at = if (ui.item?.live == true) 0L else engine?.positionMs ?: 0L
        forced = choice
        ui.panel = null
        load(ui.index, at)
    }

    override fun sleep(minutes: Int) {
        ui.sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0
        openPanel(null)
    }

    override fun pip() {
        if (ui.canPip) runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
    }

    override fun external() {
        val item = ui.item ?: return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.url), "video/*").putExtra("title", item.title)
        runCatching { startActivity(Intent.createChooser(intent, t("Öffnen mit"))) }
            .onFailure { showMessage(t("Keine passende App gefunden."), autoHide = true) }
    }

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
        showMessage(t("Aufnahme läuft bis %1.", io.github.phillhf.iptv.ui.clockTime(end)), autoHide = true)
    }

    override fun startOver() {
        val item = ui.item ?: return
        val now = currentAndNext(ui.epg).first ?: return
        lifecycleScope.launch {
            val again = runCatching { PlaybackQueue.catchup?.invoke(item, now) }.getOrNull()
            if (again == null) {
                showMessage(t("Diese Sendung lässt sich nicht von vorn ansehen."), autoHide = true)
            } else {
                ui.items = listOf(again)
                forced = null
                load(0, 0)
            }
        }
    }

    override fun cast(routeId: String) {
        val item = ui.item ?: return
        Caster.play(this, routeId, item, if (item.live) 0 else ui.position) { error ->
            if (error == null) {
                engine?.pause()
                showMessage(t("Läuft jetzt auf dem anderen Gerät."), autoHide = true)
            } else {
                showMessage(error, autoHide = true)
            }
        }
        openPanel(null)
    }

    override fun close() = finish()

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    // ---------- Fernbedienung und Touch ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ui.controls) bump()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ui.items.isEmpty()) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(event.keyCode)) return true
        if (ui.controls) bump()
        return super.dispatchKeyEvent(event)
    }

    private fun handleKey(code: Int): Boolean {
        val live = ui.item?.live == true
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> engine?.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> engine?.pause()
            KeyEvent.KEYCODE_MEDIA_STOP -> finish()
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> step(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> step(-1)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(30_000)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10_000)
            KeyEvent.KEYCODE_MEDIA_RECORD -> record()
            KeyEvent.KEYCODE_CAPTIONS -> openPanel(PlayerPanel.SUBTITLES)
            KeyEvent.KEYCODE_INFO -> ui.item?.let { showInfo(it) }
            KeyEvent.KEYCODE_GUIDE -> if (live) openPanel(PlayerPanel.CHANNELS)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (!live || ui.panel != null) return false
                ui.digits = (ui.digits + (code - KeyEvent.KEYCODE_0)).takeLast(5)
                handler.removeCallbacks(enterDigits)
                handler.postDelayed(enterDigits, 1_800)
            }
            else -> {
                if (ui.controls || ui.panel != null) return false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        if (ui.digits.isNotEmpty()) {
                            handler.removeCallbacks(enterDigits)
                            enterDigits.run()
                        } else {
                            showControls(true)
                        }
                    KeyEvent.KEYCODE_DPAD_UP -> if (live) step(1) else showControls(true)
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (live) step(-1) else showControls(true)
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (live) openPanel(PlayerPanel.CHANNELS) else touchSeek(false)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (live) ui.item?.let { showInfo(it) } else touchSeek(true)
                    else -> return false
                }
            }
        }
        return true
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && ui.playing && !isTv(this)) pip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            ui.controls = false
            ui.info = false
            ui.panel = null
        }
    }

    private fun savePosition() {
        val item = ui.item ?: return
        val e = engine ?: return
        if (item.live || item.catchup || !wasReady) return
        val lib = PlaybackQueue.library ?: return
        val duration = e.durationMs
        val position = e.positionMs
        when {
            duration > 120_000 && position > duration - 60_000 -> {
                lib.clearPosition(item.key)
                lib.removeContinue(item.key)
            }
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
