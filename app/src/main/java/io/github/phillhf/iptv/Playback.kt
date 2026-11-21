package io.github.phillhf.iptv

import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.Stream

/**
 * Ein Eintrag für den Player. [key] merkt sich die Position, [recentKey] landet in „Zuletzt gesehen“
 * (bei Folgen die Serie, nicht die einzelne Folge).
 */
data class PlayItem(
    val key: String,
    val title: String,
    val url: String,
    val live: Boolean,
    val streamId: String? = null,
    val subtitle: String? = null,
    val recentKey: String = key,
)

fun Stream.toPlayItem() = PlayItem(key = key, title = name, url = url, live = kind == Kind.LIVE, streamId = id)

/** Übergabe an den Player. Listen mit tausenden Sendern passen nicht in einen Intent. */
object PlaybackQueue {
    var items: List<PlayItem> = emptyList()
    var startIndex: Int = 0
    var library: Library? = null
    var epg: (suspend (PlayItem) -> List<EpgEntry>)? = null
    var onPlay: ((PlayItem) -> Unit)? = null
}
