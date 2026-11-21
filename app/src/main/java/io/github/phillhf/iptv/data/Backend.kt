package io.github.phillhf.iptv.data

import java.io.IOException

/**
 * Alles, was die App vom Anbieter braucht. Alle Aufrufe blockieren und laufen im Hintergrund.
 * Für Screenshots und Tests lässt sich eine Beispiel-Quelle einsetzen.
 */
interface Backend {
    /** Prüft den Zugang. Gibt den Kontostand zurück, wenn der Anbieter einen kennt. */
    fun login(): AccountStatus?
    fun catalog(kind: Kind, liveFormat: String): Catalog
    fun vodInfo(id: String): VodInfo?
    fun seriesInfo(id: String): SeriesInfo
    fun epg(streamId: String): List<EpgEntry>
    val hasEpg: Boolean
}

class XtreamBackend(account: Account) : Backend {
    private val client = XtreamClient(account.server, account.username, account.password)

    override fun login(): AccountStatus = client.login()

    override fun catalog(kind: Kind, liveFormat: String): Catalog =
        Catalog.build(client.categories(kind), client.streams(kind, liveFormat))

    override fun vodInfo(id: String): VodInfo = client.vodInfo(id)

    override fun seriesInfo(id: String): SeriesInfo = client.seriesInfo(id)

    override fun epg(streamId: String): List<EpgEntry> = client.shortEpg(streamId)

    override val hasEpg = true
}

class M3uBackend(private val account: Account) : Backend {
    @Volatile private var cache: List<Stream>? = null
    private val lock = Any()

    private fun all(): List<Stream> = cache ?: synchronized(lock) {
        cache ?: M3uParser.parse(Http.get(account.m3uUrl.trim())).also {
            if (it.isEmpty()) throw IOException("Die Playlist enthält keine Einträge.")
            cache = it
        }
    }

    override fun login(): AccountStatus? {
        all()
        return null
    }

    override fun catalog(kind: Kind, liveFormat: String): Catalog = Catalog.fromGroups(all().filter { it.kind == kind })

    override fun vodInfo(id: String): VodInfo? = null

    override fun seriesInfo(id: String): SeriesInfo = throw IOException("Serien gibt es nur bei Xtream-Anbietern.")

    override fun epg(streamId: String): List<EpgEntry> = emptyList()

    override val hasEpg = false

    fun reset() {
        cache = null
    }
}
