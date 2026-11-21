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
) {
    val key: String get() = "${kind.name}:$id"
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

data class SeriesInfo(val plot: String?, val cover: String?, val seasons: Map<Int, List<Episode>>)

data class VodInfo(
    val plot: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val duration: String? = null,
    val cover: String? = null,
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
