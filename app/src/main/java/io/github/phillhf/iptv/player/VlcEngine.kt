package io.github.phillhf.iptv.player

import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import io.github.phillhf.iptv.PlayItem
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.Http
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.concurrent.thread

/** Zweite Engine: libVLC bringt eigene Decoder mit, z. B. für Dolby- und DTS-Ton oder alte Formate. */
class VlcEngine(context: Context) : Engine {
    override val name = "VLC"
    override var listener: Engine.Listener? = null

    private val app = context.applicationContext
    private val vlc = LibVLC(app, options())
    private val player = MediaPlayer(vlc)
    private val layout = VLCVideoLayout(context).apply {
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }
    override val view: View get() = layout

    private var reportedReady = false
    private var ended = false
    private var speed = 1f

    /** Aus VLC-Ereignissen statt per Abfrage: Abfragen während des Stoppens können hängen. */
    private var time = 0L
    private var length = 0L
    private var playing = false
    private var released = false
    private var textChosen = false

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
        )
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }
        .build()

    init {
        player.attachViews(layout, null, true, false)
        player.setEventListener { e ->
            if (released) return@setEventListener
            when (e.type) {
                MediaPlayer.Event.Buffering -> listener?.onBuffering(e.buffering < 100f)
                MediaPlayer.Event.Playing -> {
                    playing = true
                    listener?.onBuffering(false)
                    listener?.onPlaying(true)
                    if (!reportedReady) {
                        reportedReady = true
                        player.rate = speed
                        listener?.onReady()
                    }
                }
                // VLC schaltet als Standard markierte Untertitel von selbst ein
                MediaPlayer.Event.ESAdded -> if (e.esChangedType == IMedia.Track.Type.Text && !textChosen) player.spuTrack = -1
                MediaPlayer.Event.Vout -> player.currentVideoTrack?.let { t ->
                    if (t.width > 0) listener?.onVideoSize(t.width, t.height)
                    if (t.frameRateNum > 0 && t.frameRateDen > 0) listener?.onFrameRate(t.frameRateNum.toFloat() / t.frameRateDen)
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                    playing = false
                    listener?.onPlaying(false)
                }
                MediaPlayer.Event.TimeChanged -> time = e.timeChanged
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.EndReached -> {
                    playing = false
                    ended = true
                    listener?.onPlaying(false)
                    listener?.onEnded()
                }
                MediaPlayer.Event.EncounteredError -> {
                    playing = false
                    ended = true
                    listener?.onError("VLC", unsupported = false)
                }
            }
        }
    }

    private fun options(): ArrayList<String> {
        val rel = (1600 / AppSettings.subtitleSize.coerceIn(50, 250)).coerceIn(6, 32)
        val color = runCatching { Color.parseColor(AppSettings.subtitleColor) and 0xFFFFFF }.getOrDefault(0xFFFFFF)
        val list = arrayListOf(
            "--network-caching=1500",
            "--freetype-rel-fontsize=$rel",
            "--freetype-color=$color",
            "--freetype-background-opacity=${AppSettings.subtitleBackground.coerceIn(0, 100) * 255 / 100}",
        )
        if (AppSettings.audioLanguage.isNotEmpty()) list += "--audio-language=${AppSettings.audioLanguage}"
        if (AppSettings.subtitlesOn && AppSettings.subtitleLanguage.isNotEmpty()) list += "--sub-language=${AppSettings.subtitleLanguage}"
        return list
    }

    override fun load(item: PlayItem, positionMs: Long) {
        reportedReady = false
        ended = false
        time = positionMs.coerceAtLeast(0)
        length = 0
        val media = Media(vlc, Uri.parse(item.url))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":http-user-agent=${Http.USER_AGENT}")
        if (positionMs > 0) media.addOption(":start-time=${positionMs / 1000}")
        textChosen = AppSettings.subtitlesOn
        player.media = media
        media.release()
        audioManager.requestAudioFocus(focus)
        player.play()
    }

    override fun play() {
        audioManager.requestAudioFocus(focus)
        if (ended) {
            // Nach Ende oder Fehler muss VLC das Medium neu anfangen
            ended = false
            player.stop()
        }
        player.play()
    }

    override fun pause() {
        if (playing) player.pause()
    }

    override fun seekTo(positionMs: Long) {
        time = positionMs.coerceAtLeast(0)
        player.time = time
    }

    override val positionMs: Long get() = time
    override val durationMs: Long get() = length.coerceAtLeast(0)
    override val isPlaying: Boolean get() = playing
    override val isActive: Boolean get() = playing

    private fun list(tracks: Array<MediaPlayer.TrackDescription>?, current: Int) =
        tracks.orEmpty().filter { it.id >= 0 }.map { Track(it.id.toString(), it.name ?: "#${it.id}", it.id == current) }

    override fun audioTracks() = list(player.audioTracks, player.audioTrack)

    override fun textTracks() = list(player.spuTracks, player.spuTrack)

    override fun selectAudio(id: String) {
        player.audioTrack = id.toInt()
    }

    override fun selectText(id: String?) {
        textChosen = id != null
        player.spuTrack = id?.toInt() ?: -1
    }

    override fun addSubtitle(uri: Uri, label: String) {
        textChosen = true
        player.addSlave(IMedia.Slave.Type.Subtitle, uri, true)
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        player.rate = speed
    }

    override fun setScale(mode: ScaleMode) {
        player.videoScale = when (mode) {
            ScaleMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            ScaleMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            ScaleMode.STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
        }
    }

    override fun stats(): PlaybackStats {
        if (released) return PlaybackStats()
        val media = player.media ?: return PlaybackStats()
        return try {
            val s = media.stats ?: return PlaybackStats()
            PlaybackStats(
                dropped = s.lostPictures.toLong(),
                shown = s.displayedPictures.toLong(),
                // VLC rechnet in Byte je Millisekunde
                bitrateKbps = (s.inputBitrate * 8000).toInt().takeIf { it > 0 } ?: -1,
            )
        } catch (_: Exception) {
            PlaybackStats()
        } finally {
            media.release()
        }
    }

    override fun release() {
        if (released) return
        released = true
        listener = null
        player.setEventListener(null)
        player.detachViews()
        audioManager.abandonAudioFocusRequest(focus)
        // stop() wartet auf das Netz und würde die Oberfläche einfrieren
        thread(name = "vlc-release") {
            player.stop()
            player.release()
            vlc.release()
        }
    }
}
