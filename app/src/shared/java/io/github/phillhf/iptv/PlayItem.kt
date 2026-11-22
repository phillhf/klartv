package io.github.phillhf.iptv

import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.Stream

/**
 * Ein Eintrag für den Player. [key] merkt sich die Position, [recentKey] landet in „Zuletzt gesehen“
 * und „Weiterschauen“ (bei Folgen die Serie, nicht die einzelne Folge).
 */
data class PlayItem(
    val key: String,
    val title: String,
    val url: String,
    val live: Boolean,
    val streamId: String? = null,
    val subtitle: String? = null,
    val recentKey: String = key,
    val image: String? = null,
    /** Vergangene Sendung; die Position wird nicht gemerkt. */
    val catchup: Boolean = false,
    /** Datei auf dem Gerät. */
    val local: Boolean = false,
    /** Sender mit Catch-up, also „Von vorn“ möglich. */
    val archive: Boolean = false,
    /** Adresse zum Aufnehmen, wenn sie von der Wiedergabe abweicht. */
    val recordUrl: String? = null,
)

fun Stream.toPlayItem(image: String? = logo) =
    PlayItem(
        key = key,
        title = name,
        url = url,
        live = kind == Kind.LIVE,
        streamId = id,
        image = image,
        archive = archiveDays > 0,
        recordUrl = recordUrl(),
    )

/** Xtream liefert denselben Sender auch als durchgehenden Strom, der sich besser aufnehmen lässt. */
fun Stream.recordUrl(): String? =
    if (kind == Kind.LIVE && url.contains("/live/") && url.endsWith("/$id.m3u8")) url.removeSuffix(".m3u8") + ".ts" else null

/** Übergabe an den Player. Listen mit tausenden Sendern passen nicht in einen Intent. */
object PlaybackQueue {
    var items: List<PlayItem> = emptyList()
    var startIndex: Int = 0
    var library: Library? = null
    var epg: (suspend (PlayItem) -> List<EpgEntry>)? = null
    var onPlay: ((PlayItem) -> Unit)? = null

    /** Baut den Eintrag, um eine Sendung von vorn anzusehen. */
    var catchup: (suspend (PlayItem, EpgEntry) -> PlayItem?)? = null
}
