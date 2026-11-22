@file:Suppress("FunctionName")

package io.github.phillhf.iptv.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.phillhf.iptv.data.AppSettings
import java.io.File

/** Eigene schmale Anbindung an libVLC 3 (LGPL), dynamisch geladen. */
interface LibVlc : Library {
    fun libvlc_new(argc: Int, argv: Array<String>): Pointer?
    fun libvlc_release(vlc: Pointer)
    fun libvlc_get_version(): String
    fun libvlc_errmsg(): String?

    fun libvlc_media_new_location(vlc: Pointer, mrl: String): Pointer?
    fun libvlc_media_add_option(media: Pointer, option: String)
    fun libvlc_media_release(media: Pointer)
    fun libvlc_media_tracks_get(media: Pointer, tracks: PointerByReference): Int
    fun libvlc_media_tracks_release(tracks: Pointer, count: Int)

    fun libvlc_media_player_new(vlc: Pointer): Pointer?
    fun libvlc_media_player_release(player: Pointer)
    fun libvlc_media_player_set_media(player: Pointer, media: Pointer)
    fun libvlc_media_player_get_media(player: Pointer): Pointer?
    fun libvlc_media_player_play(player: Pointer): Int
    fun libvlc_media_player_set_pause(player: Pointer, pause: Int)
    fun libvlc_media_player_stop(player: Pointer)
    fun libvlc_media_player_set_time(player: Pointer, ms: Long)
    fun libvlc_media_player_set_rate(player: Pointer, rate: Float): Int
    fun libvlc_media_player_event_manager(player: Pointer): Pointer
    fun libvlc_media_player_add_slave(player: Pointer, type: Int, uri: String, select: Byte): Int

    fun libvlc_audio_set_volume(player: Pointer, volume: Int): Int
    fun libvlc_audio_set_mute(player: Pointer, mute: Int)
    fun libvlc_audio_get_track(player: Pointer): Int
    fun libvlc_audio_set_track(player: Pointer, id: Int): Int
    fun libvlc_audio_get_track_description(player: Pointer): Pointer?
    fun libvlc_video_get_spu(player: Pointer): Int
    fun libvlc_video_set_spu(player: Pointer, id: Int): Int
    fun libvlc_video_get_spu_description(player: Pointer): Pointer?
    fun libvlc_track_description_list_release(list: Pointer)

    fun libvlc_video_set_callbacks(player: Pointer, lock: LockCallback, unlock: UnlockCallback?, display: DisplayCallback, opaque: Pointer?)
    fun libvlc_video_set_format_callbacks(player: Pointer, setup: SetupCallback, cleanup: CleanupCallback)
    fun libvlc_event_attach(manager: Pointer, type: Int, callback: EventCallback, data: Pointer?): Int
    fun libvlc_event_detach(manager: Pointer, type: Int, callback: EventCallback, data: Pointer?)

    interface LockCallback : Callback {
        fun invoke(opaque: Pointer?, planes: Pointer): Pointer?
    }

    interface UnlockCallback : Callback {
        fun invoke(opaque: Pointer?, picture: Pointer?, planes: Pointer)
    }

    interface DisplayCallback : Callback {
        fun invoke(opaque: Pointer?, picture: Pointer?)
    }

    interface SetupCallback : Callback {
        fun invoke(opaque: Pointer?, chroma: Pointer, width: Pointer, height: Pointer, pitches: Pointer, lines: Pointer): Int
    }

    interface CleanupCallback : Callback {
        fun invoke(opaque: Pointer?)
    }

    interface EventCallback : Callback {
        fun invoke(event: Pointer, data: Pointer?)
    }
}

object Vlc {
    const val EVENT_BUFFERING = 0x103
    const val EVENT_PLAYING = 0x104
    const val EVENT_PAUSED = 0x105
    const val EVENT_STOPPED = 0x106
    const val EVENT_END = 0x109
    const val EVENT_ERROR = 0x10A
    const val EVENT_TIME = 0x10B
    const val EVENT_LENGTH = 0x111
    const val EVENT_VOUT = 0x112
    const val EVENT_ES_ADDED = 0x114

    const val TRACK_VIDEO = 1
    const val TRACK_TEXT = 2

    val folder: File by lazy {
        File(System.getProperty("compose.application.resources.dir") ?: "build/appResources/windows-x64", "vlc")
    }

    val lib: LibVlc by lazy {
        NativeLibrary.addSearchPath("libvlc", folder.path)
        // libvlc.dll findet libvlccore.dll nur, wenn sie schon geladen ist
        System.load(File(folder, "libvlccore.dll").absolutePath)
        Native.load(File(folder, "libvlc.dll").absolutePath, LibVlc::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    }

    private val instances = HashMap<List<String>, Pointer>()

    /** Eine VLC-Instanz je Einstellung; sie bleibt bis zum Programmende bestehen. */
    @Synchronized
    fun instance(options: List<String>): Pointer = instances.getOrPut(options) {
        lib.libvlc_new(options.size, options.toTypedArray()) ?: throw IllegalStateException("libVLC: ${lib.libvlc_errmsg()}")
    }

    fun options(): List<String> {
        val rel = (1600 / AppSettings.subtitleSize.coerceIn(50, 250)).coerceIn(6, 32)
        val color = AppSettings.subtitleColor.removePrefix("#").toIntOrNull(16) ?: 0xFFFFFF
        val list = mutableListOf(
            "--quiet",
            "--no-video-title-show",
            "--no-metadata-network-access",
            "--network-caching=1500",
            // Lieber kurz stocken als Klötzchen zeigen, wenn der Rechner nicht nachkommt
            "--no-avcodec-hurry-up",
            "--freetype-rel-fontsize=$rel",
            "--freetype-color=$color",
            "--freetype-background-opacity=${AppSettings.subtitleBackground.coerceIn(0, 100) * 255 / 100}",
        )
        if (AppSettings.audioLanguage.isNotEmpty()) list += "--audio-language=${AppSettings.audioLanguage}"
        if (AppSettings.subtitlesOn && AppSettings.subtitleLanguage.isNotEmpty()) list += "--sub-language=${AppSettings.subtitleLanguage}"
        return list
    }

    /** Verkettete Liste aus Nummer, Name und Zeiger auf den nächsten Eintrag. */
    fun descriptions(list: Pointer?, current: Int): List<Track> {
        val out = ArrayList<Track>()
        var p = list
        while (p != null) {
            val id = p.getInt(0)
            val name = p.getPointer(8)?.getString(0, "UTF-8")
            if (id >= 0) out += Track(id.toString(), name ?: "#$id", id == current)
            p = p.getPointer(16)
        }
        if (list != null) lib.libvlc_track_description_list_release(list)
        return out
    }

    /** Seitenverhältnis des Bildes, wie es der Stream angibt; 0, wenn unbekannt. */
    fun aspect(player: Pointer): Float {
        val media = lib.libvlc_media_player_get_media(player) ?: return 0f
        try {
            val ref = PointerByReference()
            val count = lib.libvlc_media_tracks_get(media, ref)
            val array = ref.value ?: return 0f
            try {
                for (i in 0 until count) {
                    val t = array.getPointer(i * 8L)
                    if (t.getInt(12) != TRACK_VIDEO) continue
                    val v = t.getPointer(24) ?: continue
                    val height = v.getInt(0)
                    val width = v.getInt(4)
                    val sarNum = v.getInt(8).takeIf { it > 0 } ?: 1
                    val sarDen = v.getInt(12).takeIf { it > 0 } ?: 1
                    if (width > 0 && height > 0) return width.toFloat() * sarNum / (height.toFloat() * sarDen)
                }
            } finally {
                lib.libvlc_media_tracks_release(array, count)
            }
            return 0f
        } finally {
            lib.libvlc_media_release(media)
        }
    }
}
