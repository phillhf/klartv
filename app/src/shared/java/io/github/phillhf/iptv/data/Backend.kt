package io.github.phillhf.iptv.data

import io.github.phillhf.iptv.t
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

    /** Ganzes Programm eines Senders, auch vergangene Sendungen, falls kein XMLTV da ist. */
    fun fullEpg(streamId: String): List<EpgEntry> = emptyList()

    /** Adresse des XMLTV-Programmführers, wenn der Anbieter einen hat. */
    fun epgUrl(): String? = null

    fun catchupUrl(s: Stream, start: Long, end: Long): String? = null
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

    override fun fullEpg(streamId: String): List<EpgEntry> = client.fullEpg(streamId)

    override fun epgUrl(): String? = client.epgUrl

    override fun catchupUrl(s: Stream, start: Long, end: Long): String? =
        if (s.archiveDays > 0) client.catchupUrl(s.id, start, end) else null
}

class M3uBackend(private val account: Account) : Backend {
    @Volatile private var cache: List<Stream>? = null
    @Volatile private var playlistEpg: String? = null
    private val lock = Any()

    private fun all(): List<Stream> = cache ?: synchronized(lock) {
        cache ?: Http.get(account.m3uUrl.trim()).let { text ->
            playlistEpg = M3uParser.epgUrl(text)
            M3uParser.parse(text)
        }.also {
            if (it.isEmpty()) throw IOException(t("Die Playlist enthält keine Einträge."))
            cache = it
        }
    }

    override fun login(): AccountStatus? {
        all()
        return null
    }

    override fun catalog(kind: Kind, liveFormat: String): Catalog = Catalog.fromGroups(all().filter { it.kind == kind })

    override fun vodInfo(id: String): VodInfo? = null

    override fun seriesInfo(id: String): SeriesInfo = throw IOException(t("Serien gibt es nur bei Xtream-Anbietern."))

    override fun epg(streamId: String): List<EpgEntry> = emptyList()

    override val hasEpg = false

    override fun epgUrl(): String? {
        all()
        return playlistEpg
    }

    override fun catchupUrl(s: Stream, start: Long, end: Long): String? =
        Catchup.m3uUrl(s, start, end, System.currentTimeMillis())

    fun reset() {
        cache = null
    }
}
