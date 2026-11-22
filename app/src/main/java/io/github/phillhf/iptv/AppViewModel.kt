package io.github.phillhf.iptv

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.derivedStateOf
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
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.Backend
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.Catchup
import io.github.phillhf.iptv.data.Category
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.EpgDb
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.Episode
import io.github.phillhf.iptv.data.Http
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.M3uBackend
import io.github.phillhf.iptv.data.SeriesInfo
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.data.Tmdb
import io.github.phillhf.iptv.data.VodInfo
import io.github.phillhf.iptv.data.Xmltv
import io.github.phillhf.iptv.data.XtreamBackend
import io.github.phillhf.iptv.data.friendlyError
import io.github.phillhf.iptv.data.looksAdult
import io.github.phillhf.iptv.transfer.Transfer
import io.github.phillhf.iptv.transfer.Transfers
import io.github.phillhf.iptv.ui.Tab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Screen {
    data object Home : Screen
    data object AddAccount : Screen
    data object MultiView : Screen
    data class Movie(val stream: Stream) : Screen
    data class Series(val stream: Stream) : Screen
}

sealed interface EpgState {
    data object Idle : EpgState
    data object None : EpgState
    data class Loading(val count: Int) : EpgState
    data class Ready(val time: Long, val count: Int) : EpgState
    data class Error(val message: String) : EpgState
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
    /** Favoriten in der Reihenfolge des Nutzers. */
    var favorites by mutableStateOf<List<String>>(emptyList())
        private set
    private val favoriteSet by derivedStateOf { favorites.toSet() }
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
    private val fullEpgCache = HashMap<String, List<EpgEntry>>()
    private val epgDb = EpgDb(app)
    private var epgJob: Job? = null

    var epgState by mutableStateOf<EpgState>(EpgState.Idle)
        private set

    /** Schauspielerbilder und Ergänzungen von TMDB, nur mit eigenem Schlüssel des Nutzers. */
    val tmdb = mutableStateMapOf<String, Tmdb.Details>()

    // Kindersicherung: gesperrte Kategorien und ob für diese Sitzung entsperrt wurde
    private var locked by mutableStateOf<Set<String>?>(null)
    var unlocked by mutableStateOf(false)
        private set
    var pinRequest by mutableStateOf<(() -> Unit)?>(null)
    private val visible = HashMap<Kind, Triple<Catalog, Set<String>, Catalog>>()

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
        fullEpgCache.clear()
        tmdb.clear()
        visible.clear()
        epgJob?.cancel()
        epgState = EpgState.Idle
        unlocked = false
        focused = null
        status = null
        backend = b
        active = a
        store.activeId = a.id
        library = Library(getApplication(), a.id).also {
            favorites = it.favorites()
            recent = it.recent()
            continueList = it.continueList()
            locked = it.lockedCategories()
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
        viewModelScope.launch(Dispatchers.IO) { runCatching { epgDb.remove(a.id) } }
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

    /** Eigene Adresse für den Programmführer; leer nimmt die des Anbieters. */
    fun updateEpgUrl(url: String) {
        val a = active ?: return
        val changed = a.copy(epgUrl = url.trim())
        accounts = accounts.map { if (it.id == a.id) changed else it }
        active = changed
        store.save(accounts)
        refreshEpg(force = true)
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
        fullEpgCache.clear()
        Kind.entries.forEach { if (catalogs[it] != null) ensureLoaded(it, force = true) }
        val b = backend ?: return
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            status = runCatching { withContext(Dispatchers.IO) { b.login() } }.getOrNull()
        }
    }

    // ---------- Inhalte ----------

    fun catalog(kind: Kind): Load<Catalog> {
        val shown = readyCatalog(kind)
        return if (shown != null) Load.Ready(shown) else catalogs[kind] ?: Load.Idle
    }

    @Suppress("UNCHECKED_CAST")
    private fun rawCatalog(kind: Kind): Catalog? = (catalogs[kind] as? Load.Ready<Catalog>)?.value

    /** Was gerade gezeigt werden darf, also ohne gesperrte Kategorien. */
    fun readyCatalog(kind: Kind): Catalog? {
        val raw = rawCatalog(kind) ?: return null
        if (!AppSettings.hasPin || unlocked) return raw
        val ids = lockedIds(kind, raw)
        visible[kind]?.let { (from, with, result) -> if (from === raw && with == ids) return result }
        return raw.without(ids).also { visible[kind] = Triple(raw, ids, it) }
    }

    // ---------- Kindersicherung ----------

    /** Solange nichts festgelegt ist, gelten Kategorien mit eindeutigen Namen als gesperrt. */
    private fun lockedIds(kind: Kind, raw: Catalog): Set<String> {
        val saved = locked
        return if (saved == null) {
            raw.categories.filter { looksAdult(it.name) }.mapTo(HashSet()) { it.id }
        } else {
            saved.filter { it.startsWith("${kind.name}:") }.mapTo(HashSet()) { it.substringAfter(':') }
        }
    }

    /** Kategorien für die Einstellungen: Art, Kategorie, gesperrt? */
    fun lockChoices(kind: Kind): List<Pair<Category, Boolean>> {
        val raw = rawCatalog(kind) ?: return emptyList()
        val ids = lockedIds(kind, raw)
        return raw.categories.filter { it.id != Catalog.ALL }.map { it to (it.id in ids) }
    }

    fun setLocked(kind: Kind, categoryId: String, lock: Boolean) {
        // Beim ersten Ändern die Vorschläge aller Bereiche übernehmen
        val base = locked ?: Kind.entries.flatMapTo(HashSet()) { k ->
            rawCatalog(k)?.let { raw -> lockedIds(k, raw).map { "${k.name}:$it" } }.orEmpty()
        }
        val key = "${kind.name}:$categoryId"
        val now = if (lock) base + key else base - key
        locked = now
        library?.saveLockedCategories(now)
    }

    /** Führt [action] aus, wenn keine PIN gesetzt oder schon entsperrt ist, sonst nach Eingabe der PIN. */
    fun requirePin(action: () -> Unit) {
        if (!AppSettings.hasPin || unlocked) {
            action()
        } else {
            pinRequest = {
                unlocked = true
                action()
            }
        }
    }

    fun lockNow() {
        unlocked = false
        lastCategory.clear()
        focused = null
        stack.clear()
        stack.add(Screen.Home)
    }

    fun chooseCategory(kind: Kind, id: String) {
        if (readyCatalog(kind)?.lockedIds?.contains(id) == true) {
            requirePin { lastCategory[kind] = id }
        } else {
            lastCategory[kind] = id
        }
    }

    /** Gehört der Eintrag zu einer gesperrten Kategorie? Im Zweifel ja, solange die Liste noch lädt. */
    fun hidden(key: String): Boolean {
        if (!AppSettings.hasPin || unlocked) return false
        val kind = runCatching { Kind.valueOf(key.substringBefore(':')) }.getOrNull() ?: return false
        val raw = rawCatalog(kind) ?: return true
        return raw.byKey.containsKey(key) && readyCatalog(kind)?.byKey?.containsKey(key) != true
    }

    val shownContinue: List<ContinueEntry> get() = continueList.filterNot { hidden(it.parentKey) }

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
            if (kind == Kind.LIVE) refreshEpg(force = false)
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

    val hasEpg: Boolean get() = backend?.hasEpg == true || (epgState as? EpgState.Ready)?.let { it.count > 0 } == true

    private fun epgKey(s: Stream): String = s.epgId?.trim()?.lowercase()?.ifEmpty { null } ?: ("n:" + Xmltv.normalizeName(s.name))

    /** Liest den Programmführer des Anbieters ein, wenn er älter als zwölf Stunden ist oder [force]. */
    fun refreshEpg(force: Boolean) {
        val a = active ?: return
        val b = backend ?: return
        val live = rawCatalog(Kind.LIVE)?.streams?.takeIf { it.isNotEmpty() } ?: return
        if (epgJob?.isActive == true && !force) return
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            try {
                val old = withContext(Dispatchers.IO) { epgDb.info(a.id) }
                if (!force && old != null && System.currentTimeMillis() - old.time < 12 * 3_600_000L) {
                    epgState = EpgState.Ready(old.time, old.count)
                    return@launch
                }
                val url = withContext(Dispatchers.IO) { a.epgUrl.ifBlank { b.epgUrl().orEmpty() } }
                if (url.isBlank()) {
                    epgState = EpgState.None
                    return@launch
                }
                epgState = EpgState.Loading(0)
                val context = currentCoroutineContext()
                val count = withContext(Dispatchers.IO) {
                    val ids = live.mapNotNullTo(HashSet()) { it.epgId?.trim()?.lowercase()?.ifEmpty { null } }
                    val names = live.filter { it.epgId.isNullOrBlank() }.mapTo(HashSet()) { Xmltv.normalizeName(it.name) }
                    names.remove("")
                    val back = live.maxOf { it.archiveDays }.coerceIn(1, 7)
                    val now = System.currentTimeMillis()
                    val conn = Http.open(url)
                    try {
                        epgDb.import(
                            a.id,
                            conn.inputStream,
                            from = now - back * 86_400_000L,
                            to = now + 4 * 86_400_000L,
                            keyFor = { id, shown ->
                                buildList {
                                    val key = id.trim().lowercase()
                                    if (key in ids) add(key)
                                    shown.map { Xmltv.normalizeName(it) }.filter { it in names }.distinct().forEach { add("n:$it") }
                                }
                            },
                            onProgress = { n -> viewModelScope.launch { if (epgState is EpgState.Loading) epgState = EpgState.Loading(n) } },
                            isCancelled = { !context.isActive },
                        )
                    } finally {
                        conn.disconnect()
                    }
                }
                epgCache.clear()
                epgRequested.clear()
                fullEpgCache.clear()
                nowEpg.clear()
                epgState = EpgState.Ready(System.currentTimeMillis(), count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                epgState = EpgState.Error(friendlyError(e))
            }
        }
    }

    /** Programm eines Senders im Zeitraum, aus dem Programmführer oder direkt vom Anbieter. */
    suspend fun programmes(s: Stream, from: Long, to: Long): List<EpgEntry> {
        val a = active ?: return emptyList()
        val b = backend ?: return emptyList()
        val stored = withContext(Dispatchers.IO) { runCatching { epgDb.programmes(a.id, epgKey(s), from, to) }.getOrDefault(emptyList()) }
        if (stored.isNotEmpty() || !b.hasEpg) return stored
        val all = fullEpgCache[s.id] ?: epgLimit.withPermit {
            withContext(Dispatchers.IO) { runCatching { b.fullEpg(s.id).ifEmpty { b.epg(s.id) } }.getOrDefault(emptyList()) }
        }.also { fullEpgCache[s.id] = it }
        return all.filter { it.end > from && it.start < to }
    }

    /** Programm des Senders, fünf Minuten zwischengespeichert. */
    suspend fun epg(item: PlayItem): List<EpgEntry> {
        val b = backend ?: return emptyList()
        val a = active ?: return emptyList()
        val id = item.streamId ?: return emptyList()
        val now = System.currentTimeMillis()
        epgCache[id]?.let { (time, list) -> if (now - time < 5 * 60_000) return list }
        val stream = rawCatalog(Kind.LIVE)?.byKey?.get("LIVE:$id")
        val list = withContext(Dispatchers.IO) {
            val stored = stream?.let { s -> runCatching { epgDb.programmes(a.id, epgKey(s), now - 3_600_000, now + 12 * 3_600_000) }.getOrNull() }
            if (!stored.isNullOrEmpty() || !b.hasEpg) stored.orEmpty() else b.epg(id)
        }
        epgCache[id] = now to list
        nowEpg[id] = list
        return list
    }

    /** Lädt das Programm für einen sichtbaren Sender nach, höchstens vier Anfragen gleichzeitig. */
    fun requestEpg(s: Stream) {
        if (!hasEpg || s.kind != Kind.LIVE || !epgRequested.add(s.id)) return
        // Ohne eigenen Programmführer gehen die Anfragen an den Anbieter; dann nicht alle auf einmal
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

    fun isFavorite(key: String): Boolean = key in favoriteSet

    fun favoriteStreams(kind: Kind): List<Stream> {
        val catalog = readyCatalog(kind) ?: return emptyList()
        return favorites.mapNotNull { catalog.byKey[it] }
    }

    fun moveFavorite(s: Stream, up: Boolean) {
        library?.let { favorites = it.moveFavorite(s.key, up) }
    }

    // ---------- Catch-up, Aufnahmen und Downloads ----------

    suspend fun catchupItem(s: Stream, e: EpgEntry): PlayItem? {
        val b = backend ?: return null
        if (!Catchup.available(s, e.start, System.currentTimeMillis())) return null
        val url = withContext(Dispatchers.IO) { runCatching { b.catchupUrl(s, e.start, e.end) }.getOrNull() } ?: return null
        return PlayItem(
            key = "CU:${s.id}:${e.start}",
            title = e.title,
            subtitle = s.name,
            url = url,
            live = false,
            recentKey = s.key,
            image = s.logo,
            catchup = true,
        )
    }

    fun playCatchup(context: Context, s: Stream, e: EpgEntry, failed: () -> Unit) {
        viewModelScope.launch {
            val item = catchupItem(s, e)
            if (item != null) play(context, listOf(item), 0) else failed()
        }
    }

    /** Nimmt eine Sendung mit etwas Vor- und Nachlauf auf. */
    fun record(s: Stream, e: EpgEntry): Boolean =
        Transfers.record(s.key, e.title, s.name, s.logo, s.recordUrl() ?: s.url, e.start - 60_000, e.end + 5 * 60_000)

    fun downloadMovie(s: Stream) = Transfers.download(s.toPlayItem(vodInfos[s.key]?.backdrop ?: s.logo))

    fun downloadEpisode(series: Stream, info: SeriesInfo?, e: Episode) = Transfers.download(episodeItem(series, info, e))

    fun playTransfer(context: Context, x: Transfer) {
        val lib = library ?: return
        val item = PlayItem(
            key = "FILE:${x.id}", title = x.title, subtitle = x.subtitle, url = android.net.Uri.fromFile(java.io.File(x.file)).toString(),
            live = false, image = x.image, local = true,
        )
        playItems(context, lib, listOf(item), 0)
    }

    fun playLocal(context: Context, items: List<PlayItem>, index: Int) {
        val lib = library ?: return
        playItems(context, lib, items, index)
    }

    fun loadTmdb(s: Stream, year: String?, id: String?) {
        if (AppSettings.tmdbKey.isBlank() || s.key in tmdb) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { Tmdb.details(s.name, year, id, s.kind == Kind.SERIES, AppSettings.language.ifEmpty { "de" }) }
            }.getOrNull()?.let { tmdb[s.key] = it }
        }
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
        playItems(context, lib, items, index)
    }

    private fun playItems(context: Context, lib: Library, items: List<PlayItem>, index: Int) {
        if (items.isEmpty()) return
        PlaybackQueue.items = items
        PlaybackQueue.startIndex = index.coerceIn(items.indices)
        PlaybackQueue.library = lib
        PlaybackQueue.epg = { epg(it) }
        PlaybackQueue.catchup = { item, e -> rawCatalog(Kind.LIVE)?.byKey?.get(item.key)?.let { catchupItem(it, e) } }
        PlaybackQueue.onPlay = { item ->
            if (!item.local) {
                lib.addRecent(item.recentKey)
                recent = lib.recent()
            }
        }
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    fun playMovie(context: Context, s: Stream, fromStart: Boolean) {
        if (fromStart) library?.clearPosition(s.key)
        val image = vodInfos[s.key]?.backdrop ?: s.logo
        play(context, listOf(s.toPlayItem(image)), 0)
    }

    fun playEpisodes(context: Context, series: Stream, info: SeriesInfo?, episodes: List<Episode>, index: Int) {
        play(context, episodes.map { episodeItem(series, info, it) }, index)
    }

    private fun episodeItem(series: Stream, info: SeriesInfo?, e: Episode) = PlayItem(
        key = e.key,
        title = "${series.name} – S${e.season}E${e.number}",
        url = e.url,
        live = false,
        subtitle = e.title,
        recentKey = series.key,
        image = e.image ?: info?.backdrop ?: series.backdrop ?: series.logo,
    )

    /** Spielt weiter; bei Serien mit allen folgenden Folgen, damit es danach von selbst weitergeht. */
    fun playContinue(context: Context, e: ContinueEntry) {
        viewModelScope.launch {
            val series = findStream(e.parentKey)?.takeIf { it.kind == Kind.SERIES && e.key.startsWith("EP:") }
            val info = series?.let { withTimeoutOrNull(6_000) { seriesInfo(it) } as? Load.Ready<SeriesInfo> }?.value
            val episodes = info?.seasons?.values?.flatten().orEmpty()
            val index = episodes.indexOfFirst { it.key == e.key }
            if (series != null && index >= 0) {
                playEpisodes(context, series, info, episodes, index)
            } else {
                play(
                    context,
                    listOf(PlayItem(key = e.key, title = e.title, url = e.url, live = false, subtitle = e.subtitle, recentKey = e.parentKey, image = e.image)),
                    0,
                )
            }
        }
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
