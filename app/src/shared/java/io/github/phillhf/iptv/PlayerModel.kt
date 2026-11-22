package io.github.phillhf.iptv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.phillhf.iptv.data.EngineChoice
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.OpenSubtitles
import io.github.phillhf.iptv.player.ScaleMode
import io.github.phillhf.iptv.player.Track

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

    /** Leiste wurde durch Spulen geöffnet: Fokus auf den Zeitbalken, damit Rechts/Links weiterspult. */
    var seeking by mutableStateOf(false)
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

    /** 0..1; null, wenn die Lautstärke nicht im Player geregelt wird (Handy und Fernseher). */
    var volume by mutableStateOf<Float?>(null)
    var canFullscreen by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var canCast by mutableStateOf(true)
    var canExternal by mutableStateOf(true)
    var canSwitchEngine by mutableStateOf(true)
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

    /** Nur Windows. */
    fun volume(value: Float) {}
    fun fullscreen() {}
}
