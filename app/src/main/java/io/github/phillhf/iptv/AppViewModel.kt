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
import io.github.phillhf.iptv.data.Backend
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.Episode
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.M3uBackend
import io.github.phillhf.iptv.data.SeriesInfo
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.data.VodInfo
import io.github.phillhf.iptv.data.XtreamBackend
import io.github.phillhf.iptv.data.friendlyError
import io.github.phillhf.iptv.ui.Tab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object AddAccount : Screen
    data class Movie(val stream: Stream) : Screen
    data class Series(val stream: Stream) : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AccountStore(app)

    /** Woher die Daten kommen. Screenshots setzen hier eine Beispiel-Quelle ein. */
    internal var backendFor: (Account) -> Backend = { a ->
        if (a.type == SourceType.XTREAM) XtreamBackend(a) else M3uBackend(a)
    }

    var accounts by mutableStateOf(store.all())
        private set
    var active by mutableStateOf<Account?>(null)
        private set
    var liveFormat by mutableStateOf(store.liveFormat)
        private set
    var livePreview by mutableStateOf(store.livePreview)
        private set
    var status by mutableStateOf<AccountStatus?>(null)
        private set
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var recent by mutableStateOf<List<String>>(emptyList())
        private set
    var continueList by mutableStateOf<List<ContinueEntry>>(emptyList())
        private set
    var tab by mutableStateOf(Tab.START)

    /** Seitenverlauf; liegt hier, damit er das Drehen des Bildschirms übersteht. */
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val lastCategory = mutableStateMapOf<Kind, String>()

    /** Programm je Sender (Stream-ID), für „läuft gerade“ in den Listen. */
    val nowEpg = mutableStateMapOf<String, List<EpgEntry>>()

    /** Film oder Serie unter dem Fokus der Fernbedienung bzw. zuletzt angetippt. */
    var focused by mutableStateOf<Stream?>(null)
        private set

    private val catalogs = mutableStateMapOf<Kind, Load<Catalog>>()
    private val vodInfos = mutableStateMapOf<String, VodInfo>()
    private val jobs = HashMap<Kind, Job>()
    private var statusJob: Job? = null
    private var focusJob: Job? = null
    private var backend: Backend? = null
    private var library: Library? = null
    private val epgCache = HashMap<String, Pair<Long, List<EpgEntry>>>()
    private val epgRequested = HashSet<String>()
    private val epgLimit = Semaphore(4)

    init {
        (accounts.firstOrNull { it.id == store.activeId } ?: accounts.firstOrNull())?.let { activate(it, backendFor(it)) }
    }

    // ---------- Konten ----------

    private fun activate(a: Account, b: Backend) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        statusJob?.cancel()
        focusJob?.cancel()
        catalogs.clear()
        lastCategory.clear()
        vodInfos.clear()
        nowEpg.clear()
        epgCache.clear()
        epgRequested.clear()
        focused = null
        status = null
        backend = b
        active = a
        store.activeId = a.id
        library = Library(getApplication(), a.id).also {
            favorites = it.favorites()
            recent = it.recent()
            continueList = it.continueList()
        }
        tab = Tab.START
        stack.clear()
        stack.add(Screen.Home)
        statusJob = viewModelScope.launch {
            status = try {
                withContext(Dispatchers.IO) { b.login() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    fun select(a: Account) {
        if (a.id != active?.id) activate(a, backendFor(a))
    }

    /** Prüft die Zugangsdaten und speichert das Konto. Gibt bei Erfolg null zurück, sonst die Fehlermeldung. */
    suspend fun addAccount(a: Account): String? = try {
        val b = backendFor(a)
        withContext(Dispatchers.IO) { b.login() }
        accounts = accounts + a
        store.save(accounts)
        activate(a, b)
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
                activate(next, backendFor(next))
            } else {
                jobs.values.forEach { it.cancel() }
                catalogs.clear()
                library = null
                backend = null
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

    fun updateLivePreview(on: Boolean) {
        livePreview = on
        store.livePreview = on
    }

    fun reload() {
        (backend as? M3uBackend)?.reset()
        nowEpg.clear()
        epgCache.clear()
        epgRequested.clear()
        Kind.entries.forEach { if (catalogs[it] != null) ensureLoaded(it, force = true) }
        val b = backend ?: return
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            status = runCatching { withContext(Dispatchers.IO) { b.login() } }.getOrNull()
        }
    }

    // ---------- Inhalte ----------

    fun catalog(kind: Kind): Load<Catalog> = catalogs[kind] ?: Load.Idle

    @Suppress("UNCHECKED_CAST")
    fun readyCatalog(kind: Kind): Catalog? = (catalogs[kind] as? Load.Ready<Catalog>)?.value

    fun ensureLoaded(kind: Kind, force: Boolean = false) {
        val b = backend ?: return
        val current = catalogs[kind]
        if (!force && (current is Load.Ready<*> || current is Load.Loading)) return
        val format = liveFormat
        jobs[kind]?.cancel()
        catalogs[kind] = Load.Loading
        jobs[kind] = viewModelScope.launch {
            catalogs[kind] = try {
                Load.Ready(withContext(Dispatchers.IO) { b.catalog(kind, format) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Load.Error(friendlyError(e))
            }
        }
    }

    fun findStream(key: String): Stream? {
        val kind = runCatching { Kind.valueOf(key.substringBefore(':')) }.getOrNull() ?: return null
        return readyCatalog(kind)?.byKey?.get(key)
    }

    /** Neueste Filme und Serien mit Bild für die große Vorschau auf der Startseite. */
    fun featured(): List<Stream> {
        val movies = readyCatalog(Kind.MOVIE)?.streams.orEmpty().sortedByDescending { it.added }.take(5)
        val series = readyCatalog(Kind.SERIES)?.streams.orEmpty()
            .filter { it.backdrop != null || it.logo != null }
            .sortedByDescending { it.added }
            .take(5)
        val mixed = ArrayList<Stream>()
        for (i in 0 until 5) {
            series.getOrNull(i)?.let { mixed += it }
            movies.getOrNull(i)?.let { mixed += it }
        }
        return mixed
    }

    fun newest(kind: Kind, count: Int = 30): List<Stream> =
        readyCatalog(kind)?.streams.orEmpty().filter { it.added > 0 }.sortedByDescending { it.added }.take(count)

    suspend fun vodInfo(s: Stream): VodInfo? {
        vodInfos[s.key]?.let { return it }
        val b = backend ?: return null
        return try {
            withContext(Dispatchers.IO) { b.vodInfo(s.id) }?.also { vodInfos[s.key] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Filmdetails, die schon geladen sind. */
    fun cachedVodInfo(s: Stream): VodInfo? = vodInfos[s.key]

    /** Details für die Startseiten-Vorschau im Voraus holen. */
    fun prefetch(list: List<Stream>) {
        list.filter { it.kind == Kind.MOVIE && it.key !in vodInfos }.forEach { s ->
            viewModelScope.launch { vodInfo(s) }
        }
    }

    /**
     * Merkt sich den Titel unter dem Fokus. Filmdetails werden erst geladen, wenn der Fokus kurz stehen
     * bleibt, damit schnelles Durchscrollen den Server nicht mit Anfragen flutet.
     */
    fun focus(s: Stream) {
        if (focused?.key == s.key) return
        focused = s
        focusJob?.cancel()
        if (s.kind == Kind.MOVIE && s.key !in vodInfos) {
            focusJob = viewModelScope.launch {
                delay(400)
                vodInfo(s)
            }
        }
    }

    suspend fun seriesInfo(s: Stream): Load<SeriesInfo> {
        val b = backend ?: return Load.Error("Kein Anbieter ausgewählt.")
        return try {
            Load.Ready(withContext(Dispatchers.IO) { b.seriesInfo(s.id) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Error(friendlyError(e))
        }
    }

    val hasEpg: Boolean get() = backend?.hasEpg == true

    /** Programm des Senders, fünf Minuten zwischengespeichert. */
    suspend fun epg(item: PlayItem): List<EpgEntry> {
        val b = backend ?: return emptyList()
        val id = item.streamId ?: return emptyList()
        if (!b.hasEpg) return emptyList()
        val now = System.currentTimeMillis()
        epgCache[id]?.let { (time, list) -> if (now - time < 5 * 60_000) return list }
        val list = withContext(Dispatchers.IO) { b.epg(id) }
        epgCache[id] = now to list
        nowEpg[id] = list
        return list
    }

    /** Lädt das Programm für einen sichtbaren Sender nach, höchstens vier Anfragen gleichzeitig. */
    fun requestEpg(s: Stream) {
        if (!hasEpg || s.kind != Kind.LIVE || !epgRequested.add(s.id)) return
        viewModelScope.launch {
            epgLimit.withPermit {
                try {
                    epg(s.toPlayItem())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    nowEpg[s.id] = emptyList()
                }
            }
        }
    }

    // ---------- Favoriten und Wiedergabe ----------

    fun toggleFavorite(s: Stream) {
        library?.let { favorites = it.toggleFavorite(s.key) }
    }

    fun position(key: String): Long = library?.position(key) ?: 0L

    /** Fortschritt 0..1 eines Films aus „Weiterschauen“, 0 wenn unbekannt. */
    fun progress(key: String): Float = continueList.firstOrNull { it.key == key }?.progress ?: 0f

    /** Nach dem Player: Verlauf und „Weiterschauen“ neu einlesen. */
    fun refreshLibrary() {
        library?.let {
            recent = it.recent()
            continueList = it.continueList()
        }
    }

    fun removeContinue(entry: ContinueEntry) {
        library?.let {
            it.removeContinue(entry.key)
            it.clearPosition(entry.key)
            continueList = it.continueList()
        }
    }

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
        val image = vodInfos[s.key]?.backdrop ?: s.logo
        play(context, listOf(s.toPlayItem(image)), 0)
    }

    fun playEpisodes(context: Context, series: Stream, info: SeriesInfo?, episodes: List<Episode>, index: Int) {
        val items = episodes.map {
            PlayItem(
                key = it.key,
                title = "${series.name} – S${it.season}E${it.number}",
                url = it.url,
                live = false,
                subtitle = it.title,
                recentKey = series.key,
                image = it.image ?: info?.backdrop ?: series.backdrop ?: series.logo,
            )
        }
        play(context, items, index)
    }

    fun playContinue(context: Context, e: ContinueEntry) {
        play(
            context,
            listOf(PlayItem(key = e.key, title = e.title, url = e.url, live = false, subtitle = e.subtitle, recentKey = e.parentKey, image = e.image)),
            0,
        )
    }

    /** Öffnet einen Eintrag: Sender spielen sofort, Filme und Serien zeigen erst ihre Seite. */
    fun openStream(context: Context, s: Stream, siblings: List<Stream>) {
        when (s.kind) {
            Kind.LIVE -> {
                val live = siblings.filter { it.kind == Kind.LIVE }.ifEmpty { listOf(s) }
                play(context, live.map { it.toPlayItem() }, live.indexOf(s).coerceAtLeast(0))
            }
            Kind.MOVIE -> {
                focus(s)
                open(Screen.Movie(s))
            }
            Kind.SERIES -> {
                focus(s)
                open(Screen.Series(s))
            }
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
