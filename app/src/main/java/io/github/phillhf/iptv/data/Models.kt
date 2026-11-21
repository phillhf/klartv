package io.github.phillhf.iptv.data

enum class Kind { LIVE, MOVIE, SERIES }

data class Category(val id: String, val name: String)

/** Ein Sender, Film oder eine Serie. Bei Serien ist [url] leer, die Folgen kommen einzeln. */
data class Stream(
    val kind: Kind,
    val id: String,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val url: String,
    val epgId: String? = null,
    val number: Int = 0,
    val plot: String? = null,
    val rating: String? = null,
    val backdrop: String? = null,
    val genre: String? = null,
    val year: String? = null,
    /** Wann der Anbieter den Eintrag hinzugefügt hat (Sekunden seit 1970), für „Neu“. */
    val added: Long = 0,
) {
    val key: String get() = "${kind.name}:$id"
}

/**
 * Eintrag in „Weiterschauen“. [parentKey] ist der Film oder die Serie, damit pro Serie nur die
 * zuletzt gesehene Folge erscheint.
 */
data class ContinueEntry(
    val key: String,
    val parentKey: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val url: String,
    val position: Long,
    val duration: Long,
) {
    val progress: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val url: String,
    val plot: String?,
    val image: String?,
    val duration: String?,
) {
    val key: String get() = "EP:$id"
}

data class SeriesInfo(
    val plot: String?,
    val cover: String?,
    val seasons: Map<Int, List<Episode>>,
    val backdrop: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val year: String? = null,
)

data class VodInfo(
    val plot: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val duration: String? = null,
    val cover: String? = null,
    val backdrop: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val rating: String? = null,
)

/** Eine Sendung im Programmführer, Zeiten in Millisekunden seit 1970. */
data class EpgEntry(val title: String, val description: String, val start: Long, val end: Long)

data class AccountStatus(
    val status: String?,
    val expires: Long?,
    val maxConnections: String?,
    val activeConnections: String?,
)

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Error(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}
