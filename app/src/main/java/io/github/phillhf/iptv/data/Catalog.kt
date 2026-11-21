package io.github.phillhf.iptv.data

/** Alle Sender, Filme oder Serien eines Anbieters mit ihren Kategorien. */
class Catalog(val categories: List<Category>, val streams: List<Stream>) {
    private val byCategory: Map<String, List<Stream>> = streams.groupBy { it.categoryId }
    val byKey: Map<String, Stream> by lazy { streams.associateBy { it.key } }

    fun inCategory(id: String): List<Stream> = if (id == ALL) streams else byCategory[id].orEmpty()

    companion object {
        const val ALL = "*"

        /** Kategorien vom Server; leere fallen weg, Einträge ohne bekannte Kategorie landen in „Sonstige“. */
        fun build(categories: List<Category>, streams: List<Stream>): Catalog {
            val present = streams.mapTo(LinkedHashSet()) { it.categoryId }
            val known = categories.mapTo(HashSet()) { it.id }
            val list = ArrayList<Category>()
            list += Category(ALL, "Alle")
            categories.filter { it.id in present }.distinctBy { it.id }.forEach { list += it }
            present.filter { it !in known }.forEach { list += Category(it, "Sonstige") }
            return Catalog(list, streams)
        }

        /** M3U: Die Gruppen der Playlist sind die Kategorien. */
        fun fromGroups(streams: List<Stream>): Catalog {
            val groups = streams.map { it.categoryId }.distinct().map { Category(it, it) }
            return Catalog(listOf(Category(ALL, "Alle")) + groups, streams)
        }
    }
}
