package io.github.phillhf.iptv.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.phillhf.iptv.PlayItem
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.Http
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** Nimmt die Bilder von VLC entgegen und reicht immer nur das neueste an die Oberfläche weiter. */
class FrameSink(private val maxWidth: Int, private val maxHeight: Int) {
    private class Buffer(val memory: Memory, val width: Int, val height: Int)

    private var buffers: List<Buffer> = emptyList()
    private var writing = -1
    private var latest = -1
    private var image: Image? = null
    private var stale = false

    /** Zählt jedes Bild; Compose zeichnet neu, sobald sich die Zahl ändert. */
    var frame by mutableLongStateOf(0L)
        private set

    @Volatile
    var count = 0L
        private set

    val lock = object : LibVlc.LockCallback {
        override fun invoke(opaque: Pointer?, planes: Pointer): Pointer? = synchronized(this@FrameSink) {
            if (buffers.isEmpty()) return null
            writing = buffers.indices.first { it != latest }
            planes.setPointer(0, buffers[writing].memory)
            Pointer(writing + 1L)
        }
    }

    val display = object : LibVlc.DisplayCallback {
        override fun invoke(opaque: Pointer?, picture: Pointer?) {
            synchronized(this@FrameSink) {
                val i = Pointer.nativeValue(picture).toInt() - 1
                if (i !in buffers.indices) return
                latest = i
                stale = true
            }
            count++
            frame = count
        }
    }

    val setup = object : LibVlc.SetupCallback {
        override fun invoke(opaque: Pointer?, chroma: Pointer, width: Pointer, height: Pointer, pitches: Pointer, lines: Pointer): Int {
            var w = width.getInt(0)
            var h = height.getInt(0)
            if (w <= 0 || h <= 0) return 0
            // Decoder runden die Höhe auf 16 auf; die Zusatzzeilen gehören nicht zum Bild
            h = when (h) {
                1088, 1090 -> 1080
                736 -> 720
                544 -> 540
                else -> h
            }
            val scale = minOf(1f, maxWidth / w.toFloat(), maxHeight / h.toFloat())
            w = ((w * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
            h = ((h * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
            chroma.write(0, "RV32".toByteArray(Charsets.US_ASCII), 0, 4)
            width.setInt(0, w)
            height.setInt(0, h)
            pitches.setInt(0, w * 4)
            lines.setInt(0, h)
            synchronized(this@FrameSink) {
                latest = -1
                buffers = List(3) { Buffer(Memory(w * 4L * h + 64), w, h) }
            }
            return 1
        }
    }

    val cleanup = object : LibVlc.CleanupCallback {
        override fun invoke(opaque: Pointer?) {
            // Das letzte Bild bleibt stehen, bis das nächste kommt
        }
    }

    init {
        // Ein dauerhafter Java-Faden je VLC-Faden, sonst kostet jedes Bild einen neuen
        listOf(lock, display, setup, cleanup).forEach {
            Native.setCallbackThreadInitializer(it, CallbackThreadInitializer(true, false, "vlc-bild"))
        }
    }

    fun clear() {
        synchronized(this) {
            latest = -1
            stale = false
            image?.close()
            image = null
        }
        frame = ++count
    }

    /** Das aktuelle Bild, nur vom Zeichnen aus. */
    fun current(): Image? = synchronized(this) {
        if (stale && latest in buffers.indices) {
            stale = false
            val b = buffers[latest]
            val info = ImageInfo(b.width, b.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            val next = Image.makeFromPixmap(Pixmap.make(info, Pointer.nativeValue(b.memory), b.width * 4))
            image?.close()
            image = next
        }
        image
    }
}

/** Ein VLC-Player. Befehle laufen der Reihe nach auf einem eigenen Faden, denn VLC wartet dabei auf das Netz. */
class VlcPlayer(maxWidth: Int = 1920, maxHeight: Int = 1080) {
    interface Listener {
        fun onReady() {}
        fun onBuffering(active: Boolean) {}
        fun onPlaying(playing: Boolean) {}
        fun onEnded() {}
        fun onError() {}
    }

    var listener: Listener? = null
    val sink = FrameSink(maxWidth, maxHeight)

    /** Seitenverhältnis laut Stream, 0 = wie das Bild. */
    var aspect by mutableFloatStateOf(0f)
        private set

    private val vlc = Vlc.lib
    private val player: Pointer = vlc.libvlc_media_player_new(Vlc.instance(Vlc.options())) ?: throw IllegalStateException("libVLC")
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vlc-befehle").apply { isDaemon = true } }

    @Volatile
    private var released = false

    @Volatile
    private var textChosen = false

    @Volatile
    private var time = 0L

    @Volatile
    private var length = 0L

    @Volatile
    private var playing = false

    /** Gegen verspätete Ereignisse des vorigen Streams. */
    @Volatile
    private var ready = false
    private var ended = false
    private var speed = 1f
    private var volume = 1f

    private val events = listOf(
        Vlc.EVENT_BUFFERING, Vlc.EVENT_PLAYING, Vlc.EVENT_PAUSED, Vlc.EVENT_STOPPED, Vlc.EVENT_END, Vlc.EVENT_ERROR,
        Vlc.EVENT_TIME, Vlc.EVENT_LENGTH, Vlc.EVENT_VOUT, Vlc.EVENT_ES_ADDED,
    )

    private val callback = object : LibVlc.EventCallback {
        override fun invoke(event: Pointer, data: Pointer?) {
            if (released) return
            when (event.getInt(0)) {
                Vlc.EVENT_BUFFERING -> {
                    val active = event.getFloat(16) < 100f
                    ui { listener?.onBuffering(active) }
                }
                Vlc.EVENT_PLAYING -> {
                    playing = true
                    run {
                        vlc.libvlc_media_player_set_rate(player, speed)
                        applyVolume()
                        vlc.libvlc_audio_set_delay(player, audioDelayMs * 1000L)
                    }
                    ui {
                        listener?.onBuffering(false)
                        listener?.onPlaying(true)
                        if (!ready) {
                            ready = true
                            listener?.onReady()
                        }
                    }
                }
                Vlc.EVENT_PAUSED, Vlc.EVENT_STOPPED -> {
                    playing = false
                    ui { listener?.onPlaying(false) }
                }
                Vlc.EVENT_TIME -> time = event.getLong(16)
                Vlc.EVENT_LENGTH -> length = event.getLong(16)
                Vlc.EVENT_VOUT -> run {
                    val a = Vlc.aspect(player)
                    ui { aspect = a }
                }
                // VLC schaltet als Standard markierte Untertitel von selbst ein
                Vlc.EVENT_ES_ADDED -> if (event.getInt(16) == Vlc.TRACK_TEXT && !textChosen) {
                    run { if (!textChosen) vlc.libvlc_video_set_spu(player, -1) }
                }
                Vlc.EVENT_END -> {
                    playing = false
                    ended = true
                    ui {
                        listener?.onPlaying(false)
                        listener?.onEnded()
                    }
                }
                Vlc.EVENT_ERROR -> {
                    playing = false
                    ended = true
                    ui { listener?.onError() }
                }
            }
        }
    }

    init {
        // VLC hält nur nackte Adressen: Bis zum Freigeben darf nichts davon aufgeräumt werden
        synchronized(alive) { alive += this }
        Native.setCallbackThreadInitializer(callback, CallbackThreadInitializer(true, false, "vlc-ereignis"))
        vlc.libvlc_video_set_callbacks(player, sink.lock, null, sink.display, null)
        vlc.libvlc_video_set_format_callbacks(player, sink.setup, sink.cleanup)
        val manager = vlc.libvlc_media_player_event_manager(player)
        events.forEach { vlc.libvlc_event_attach(manager, it, callback, null) }
    }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater { if (!released) block() }

    private fun run(block: () -> Unit) {
        try {
            worker.execute { if (!released) block() }
        } catch (_: RejectedExecutionException) {
        }
    }

    fun load(item: PlayItem, positionMs: Long) {
        time = positionMs.coerceAtLeast(0)
        length = 0
        ready = false
        ended = false
        textChosen = AppSettings.subtitlesOn
        lastStats = PlaybackStats()
        run {
            vlc.libvlc_media_player_stop(player)
            ready = false
            lastStats = PlaybackStats()
            val media = vlc.libvlc_media_new_location(Vlc.instance(Vlc.options()), item.url) ?: return@run
            vlc.libvlc_media_add_option(media, ":http-user-agent=${Http.USER_AGENT}")
            if (positionMs > 0) vlc.libvlc_media_add_option(media, ":start-time=${positionMs / 1000}")
            vlc.libvlc_media_player_set_media(player, media)
            vlc.libvlc_media_release(media)
            vlc.libvlc_media_player_play(player)
        }
    }

    fun play() = run {
        if (ended) {
            // Nach Ende oder Fehler muss VLC das Medium neu anfangen
            ended = false
            vlc.libvlc_media_player_stop(player)
        }
        vlc.libvlc_media_player_play(player)
    }

    fun pause() = run { vlc.libvlc_media_player_set_pause(player, 1) }

    fun stop() {
        ready = false
        run { vlc.libvlc_media_player_stop(player) }
        sink.clear()
    }

    fun seekTo(ms: Long) {
        time = ms.coerceAtLeast(0)
        run { vlc.libvlc_media_player_set_time(player, ms.coerceAtLeast(0)) }
    }

    val positionMs: Long get() = time
    val durationMs: Long get() = length.coerceAtLeast(0)
    val isPlaying: Boolean get() = playing

    fun audioTracks(): List<Track> = Vlc.descriptions(vlc.libvlc_audio_get_track_description(player), vlc.libvlc_audio_get_track(player))

    fun textTracks(): List<Track> = Vlc.descriptions(vlc.libvlc_video_get_spu_description(player), vlc.libvlc_video_get_spu(player))

    fun selectAudio(id: String) = run { vlc.libvlc_audio_set_track(player, id.toInt()) }

    fun selectText(id: String?) {
        textChosen = id != null
        run { vlc.libvlc_video_set_spu(player, id?.toInt() ?: -1) }
    }

    fun addSubtitle(uri: String) {
        textChosen = true
        run { vlc.libvlc_media_player_add_slave(player, 0, uri, 1) }
    }

    fun setSpeed(value: Float) {
        speed = value
        run { vlc.libvlc_media_player_set_rate(player, value) }
    }

    /** Tonversatz in ms, positiv = Ton später. VLC vergisst ihn bei jedem neuen Medium, deshalb auch beim Abspielen setzen. */
    @Volatile private var audioDelayMs = 0

    fun setAudioDelay(ms: Int) {
        audioDelayMs = ms
        run { vlc.libvlc_audio_set_delay(player, ms * 1000L) }
    }

    /** 0..1 */
    fun setVolume(value: Float) {
        volume = value
        run { applyVolume() }
    }

    private fun applyVolume() {
        vlc.libvlc_audio_set_volume(player, (volume * 100).roundToInt().coerceIn(0, 100))
        vlc.libvlc_audio_set_mute(player, if (volume <= 0f) 1 else 0)
    }

    @Volatile
    private var lastStats = PlaybackStats()
    private val statsBuffer = Memory(64)

    /** Letzte bekannte Werte; holt im Hintergrund schon die nächsten. */
    fun stats(): PlaybackStats {
        run {
            val media = vlc.libvlc_media_player_get_media(player) ?: return@run
            try {
                // libvlc_media_stats_t: lauter 4-Byte-Felder, Datenrate in Byte je Millisekunde
                if (vlc.libvlc_media_get_stats(media, statsBuffer) != 0) {
                    val bitrate = (statsBuffer.getFloat(4) * 8000).toInt()
                    lastStats = PlaybackStats(
                        dropped = statsBuffer.getInt(36).toLong(),
                        shown = statsBuffer.getInt(32).toLong(),
                        bitrateKbps = bitrate.takeIf { it > 0 } ?: -1,
                    )
                }
            } finally {
                vlc.libvlc_media_release(media)
            }
        }
        return lastStats
    }

    fun release() {
        if (released) return
        listener = null
        worker.execute {
            released = true
            vlc.libvlc_media_player_stop(player)
            val manager = vlc.libvlc_media_player_event_manager(player)
            events.forEach { vlc.libvlc_event_detach(manager, it, callback, null) }
            vlc.libvlc_media_player_release(player)
            synchronized(alive) { alive -= this }
            SwingUtilities.invokeLater { sink.clear() }
        }
        worker.shutdown()
    }

    private companion object {
        val alive = HashSet<VlcPlayer>()
    }
}

/** Zeichnet das aktuelle Bild eines Players. */
@Composable
fun VlcVideo(player: VlcPlayer, scale: ScaleMode, modifier: Modifier = Modifier) {
    Canvas(modifier.clipToBounds()) {
        // Lesen genügt, damit bei jedem Bild neu gezeichnet wird
        if (player.sink.frame < 0) return@Canvas
        val image = player.sink.current() ?: return@Canvas
        val ratio = player.aspect.takeIf { it > 0.1f } ?: (image.width.toFloat() / image.height)
        val box = size.width / size.height
        val (w, h) = when (scale) {
            ScaleMode.STRETCH -> size.width to size.height
            ScaleMode.FIT -> if (ratio > box) size.width to size.width / ratio else size.height * ratio to size.height
            ScaleMode.ZOOM -> if (ratio > box) size.height * ratio to size.height else size.width to size.width / ratio
        }
        val x = (size.width - w) / 2
        val y = (size.height - h) / 2
        drawIntoCanvas {
            it.nativeCanvas.drawImageRect(
                image, Rect(0f, 0f, image.width.toFloat(), image.height.toFloat()), Rect.makeXYWH(x, y, w, h), SamplingMode.LINEAR, null, true,
            )
        }
    }
}
