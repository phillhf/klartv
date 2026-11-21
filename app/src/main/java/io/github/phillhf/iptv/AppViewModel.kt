package io.github.phillhf.iptv

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AccountStatus
import io.github.phillhf.iptv.data.AccountStore
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.Episode
import io.github.phillhf.iptv.data.Http
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.M3uParser
import io.github.phillhf.iptv.data.SeriesInfo
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.data.VodInfo
import io.github.phillhf.iptv.data.XtreamClient
import io.github.phillhf.iptv.data.friendlyError
import io.github.phillhf.iptv.ui.Tab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface Screen {
    data object Home : Screen
    data object AddAccount : Screen
    data class Movie(val stream: Stream) : Screen
    data class Series(val stream: Stream) : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AccountStore(app)

    var accounts by mutableStateOf(store.all())
        private set
    var active by mutableStateOf<Account?>(null)
        private set
    var liveFormat by mutableStateOf(store.liveFormat)
        private set
    var status by mutableStateOf<AccountStatus?>(null)
        private set
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var recent by mutableStateOf<List<String>>(emptyList())
        private set
    var tab by mutableStateOf(Tab.LIVE)

    /** Seitenverlauf; liegt hier, damit er das Drehen des Bildschirms übersteht. */
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val lastCategory = mutableStateMapOf<Kind, String>()

    private val catalogs = mutableStateMapOf<Kind, Load<Catalog>>()
    private val jobs = HashMap<Kind, Job>()
    private var statusJob: Job? = null
    private var library: Library? = null
    @Volatile private var m3uCache: List<Stream>? = null
    private val m3uLock = Any()
    private val epgCache = HashMap<String, Pair<Long, List<EpgEntry>>>()

    init {
        (accounts.firstOrNull { it.id == store.activeId } ?: accounts.firstOrNull())?.let { activate(it) }
    }

    // ---------- Konten ----------

    private fun client(a: Account) = XtreamClient(a.server, a.username, a.password)

    private fun activate(a: Account) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        statusJob?.cancel()
        catalogs.clear()
        lastCategory.clear()
        epgCache.clear()
        m3uCache = null
        status = null
        active = a
        store.activeId = a.id
        library = Library(getApplication(), a.id).also {
            favorites = it.favorites()
            recent = it.recent()
        }
        stack.clear()
        stack.add(Screen.Home)
        if (a.type == SourceType.XTREAM) {
            statusJob = viewModelScope.launch {
                status = try {
                    withContext(Dispatchers.IO) { client(a).login() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    fun select(a: Account) {
        if (a.id != active?.id) activate(a)
    }

    /** Prüft die Zugangsdaten und speichert das Konto. Gibt bei Erfolg null zurück, sonst die Fehlermeldung. */
    suspend fun addAccount(a: Account): String? = try {
        val playlist = withContext(Dispatchers.IO) {
            when (a.type) {
                SourceType.XTREAM -> {
                    client(a).login()
                    null
                }
                SourceType.M3U -> M3uParser.parse(Http.get(a.m3uUrl.trim())).also {
                    if (it.isEmpty()) throw IOException("Die Playlist enthält keine Einträge.")
                }
            }
        }
        accounts = accounts + a
        store.save(accounts)
        activate(a)
        m3uCache = playlist
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        friendlyError(e)
    }

    fun deleteAccount(a: Account) {
        accounts = accounts.filter { it.id != a.id }
        store.save(accounts)
        Library.delete(getApplication(), a.id)
        if (active?.id == a.id) {
            val next = accounts.firstOrNull()
            if (next != null) {
                activate(next)
            } else {
                jobs.values.forEach { it.cancel() }
                catalogs.clear()
                library = null
                active = null
                store.activeId = null
                stack.clear()
                stack.add(Screen.Home)
            }
        }
    }

    fun updateLiveFormat(format: String) {
        if (format == liveFormat) return
        liveFormat = format
        store.liveFormat = format
        if (catalogs[Kind.LIVE] != null) ensureLoaded(Kind.LIVE, force = true)
    }

    fun reload() {
        m3uCache = null
        Kind.entries.forEach { if (catalogs[it] != null) ensureLoaded(it, force = true) }
        active?.let { a ->
            if (a.type == SourceType.XTREAM) {
                statusJob?.cancel()
                statusJob = viewModelScope.launch {
                    status = runCatching { withContext(Dispatchers.IO) { client(a).login() } }.getOrNull()
                }
            }
        }
    }

    // ---------- Inhalte ----------

    fun catalog(kind: Kind): Load<Catalog> = catalogs[kind] ?: Load.Idle

    @Suppress("UNCHECKED_CAST")
    fun readyCatalog(kind: Kind): Catalog? = (catalogs[kind] as? Load.Ready<Catalog>)?.value

    fun ensureLoaded(kind: Kind, force: Boolean = false) {
        val a = active ?: return
        val current = catalogs[kind]
        if (!force && (current is Load.Ready<*> || current is Load.Loading)) return
        val format = liveFormat
        jobs[kind]?.cancel()
        catalogs[kind] = Load.Loading
        jobs[kind] = viewModelScope.launch {
            catalogs[kind] = try {
                Load.Ready(withContext(Dispatchers.IO) { fetch(a, kind, format) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Load.Error(friendlyError(e))
            }
        }
    }

    private fun fetch(a: Account, kind: Kind, format: String): Catalog = when (a.type) {
        SourceType.XTREAM -> {
            val c = client(a)
            Catalog.build(c.categories(kind), c.streams(kind, format))
        }
        SourceType.M3U -> {
            val all = m3uCache ?: synchronized(m3uLock) {
                m3uCache ?: M3uParser.parse(Http.get(a.m3uUrl.trim())).also { m3uCache = it }
            }
            Catalog.fromGroups(all.filter { it.kind == kind })
        }
    }

    fun findStream(key: String): Stream? {
        val kind = runCatching { Kind.valueOf(key.substringBefore(':')) }.getOrNull() ?: return null
        return readyCatalog(kind)?.byKey?.get(key)
    }

    suspend fun vodInfo(s: Stream): VodInfo? {
        val a = active ?: return null
        if (a.type != SourceType.XTREAM) return null
        return try {
            withContext(Dispatchers.IO) { client(a).vodInfo(s.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun seriesInfo(s: Stream): Load<SeriesInfo> {
        val a = active ?: return Load.Error("Kein Konto ausgewählt.")
        if (a.type != SourceType.XTREAM) return Load.Error("Serien gibt es nur bei Xtream-Anbietern.")
        return try {
            Load.Ready(withContext(Dispatchers.IO) { client(a).seriesInfo(s.id) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Error(friendlyError(e))
        }
    }

    /** Programm des Senders, fünf Minuten zwischengespeichert. */
    suspend fun epg(item: PlayItem): List<EpgEntry> {
        val a = active ?: return emptyList()
        val id = item.streamId ?: return emptyList()
        if (a.type != SourceType.XTREAM) return emptyList()
        val now = System.currentTimeMillis()
        epgCache[id]?.let { (time, list) -> if (now - time < 5 * 60_000) return list }
        val list = withContext(Dispatchers.IO) { client(a).shortEpg(id) }
        epgCache[id] = now to list
        return list
    }

    // ---------- Favoriten und Wiedergabe ----------

    fun toggleFavorite(s: Stream) {
        library?.let { favorites = it.toggleFavorite(s.key) }
    }

    fun position(key: String): Long = library?.position(key) ?: 0L

    fun play(context: Context, items: List<PlayItem>, index: Int) {
        val lib = library ?: return
        if (items.isEmpty()) return
        PlaybackQueue.items = items
        PlaybackQueue.startIndex = index.coerceIn(items.indices)
        PlaybackQueue.library = lib
        PlaybackQueue.epg = { epg(it) }
        PlaybackQueue.onPlay = { item ->
            lib.addRecent(item.recentKey)
            recent = lib.recent()
        }
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    fun playMovie(context: Context, s: Stream, fromStart: Boolean) {
        if (fromStart) library?.clearPosition(s.key)
        play(context, listOf(s.toPlayItem()), 0)
    }

    fun playEpisodes(context: Context, series: Stream, episodes: List<Episode>, index: Int) {
        val items = episodes.map {
            PlayItem(
                key = it.key,
                title = "${series.name} – S${it.season}E${it.number}",
                url = it.url,
                live = false,
                subtitle = it.title,
                recentKey = series.key,
            )
        }
        play(context, items, index)
    }

    /** Öffnet einen Eintrag: Sender spielen sofort, Filme und Serien zeigen erst ihre Seite. */
    fun openStream(context: Context, s: Stream, siblings: List<Stream>) {
        when (s.kind) {
            Kind.LIVE -> {
                val live = siblings.filter { it.kind == Kind.LIVE }.ifEmpty { listOf(s) }
                play(context, live.map { it.toPlayItem() }, live.indexOf(s).coerceAtLeast(0))
            }
            Kind.MOVIE -> open(Screen.Movie(s))
            Kind.SERIES -> open(Screen.Series(s))
        }
    }

    // ---------- Navigation ----------

    fun open(screen: Screen) {
        stack.add(screen)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}
