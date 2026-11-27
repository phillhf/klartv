package io.github.phillhf.iptv.player

import android.net.Uri
import android.view.View
import io.github.phillhf.iptv.PlayItem

/** Gemeinsame Sicht auf ExoPlayer und VLC, damit die Oberfläche nicht wissen muss, wer abspielt. */
interface Engine {
    val name: String
    val view: View
    var listener: Listener?

    fun load(item: PlayItem, positionMs: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    val positionMs: Long

    /** 0, wenn unbekannt oder endlos. */
    val durationMs: Long
    val isPlaying: Boolean

    /** Spielt oder lädt gerade, um gleich zu spielen; danach richtet sich die Pause-Taste. */
    val isActive: Boolean

    fun audioTracks(): List<Track>
    fun textTracks(): List<Track>
    fun selectAudio(id: String)

    /** null schaltet Untertitel aus. */
    fun selectText(id: String?)
    fun addSubtitle(uri: Uri, label: String)
    fun setSpeed(speed: Float)

    /** Tonversatz in ms, positiv = Ton später. */
    fun setAudioDelay(ms: Int)
    fun setScale(mode: ScaleMode)

    /** Werte für Selbstheilung und Diagnose. */
    fun stats(): PlaybackStats
    fun release()

    interface Listener {
        fun onReady()
        fun onBuffering(active: Boolean)
        fun onPlaying(playing: Boolean)
        fun onEnded()

        /** [unsupported]: Das Format passt nicht zu dieser Engine, die andere könnte es können. */
        fun onError(message: String, unsupported: Boolean)
        fun onVideoSize(width: Int, height: Int)

        /** Bilder je Sekunde, sobald bekannt. */
        fun onFrameRate(fps: Float) {}
    }
}
