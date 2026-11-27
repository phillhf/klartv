package io.github.phillhf.iptv

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import io.github.phillhf.iptv.data.Custom
import io.github.phillhf.iptv.data.EpgDb
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.EpgImport
import io.github.phillhf.iptv.data.Episode
import io.github.phillhf.iptv.data.Http
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.M3uBackend
import io.github.phillhf.iptv.data.Profile
import io.github.phillhf.iptv.data.ProfileStore
import io.github.phillhf.iptv.data.RecordRule
import io.github.phillhf.iptv.data.RecordRules
import io.github.phillhf.iptv.data.Reminders
import io.github.phillhf.iptv.data.SeriesInfo
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.data.Tmdb
import io.github.phillhf.iptv.data.VodInfo
import io.github.phillhf.iptv.data.XtreamBackend
import io.github.phillhf.iptv.data.customized
import io.github.phillhf.iptv.data.friendlyError
import io.github.phillhf.iptv.data.looksAdult
import io.github.phillhf.iptv.data.moveInOrder
import io.github.phillhf.iptv.platform.hasHomeRows
import io.github.phillhf.iptv.platform.publishHomeRows
import io.github.phillhf.iptv.data.shifted
import io.github.phillhf.iptv.platform.AppApplication
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.BaseViewModel
import io.github.phillhf.iptv.platform.openPlayer
import io.github.phillhf.iptv.sync.SyncLink
import io.github.phillhf.iptv.transfer.Stores
import io.github.phillhf.iptv.transfer.Transfer
import io.github.phillhf.iptv.transfer.TransferType
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
    /** [failed]: so viele der Quellen gingen nicht. */
    data class Ready(val time: Long, val count: Int, val failed: Int = 0) : EpgState
    data class Error(val message: String) : EpgState
}

class AppViewModel(private val app: AppApplication) : BaseViewModel(app) {
    private val store = AccountStore(app)
    private val profileStore = ProfileStore(app)

    /** Profile im Haushalt; das erste (ID "") gibt es immer. */
    var profiles by mutableStateOf(profileStore.all())
        private set
    var profileId by mutableStateOf(profileStore.activeId)
        private set
    val profile: Profile get() = profiles.firstOrNull { it.id == profileId } ?: profiles.first()

    /** Beim Start fragen, wer schaut, sobald es mehr als ein Profil gibt. */
    var choosingProfile by mutableStateOf(profiles.size > 1)
        private set

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

    /** Gesehene Filme und Folgen. */
    var watched by mutableStateOf<Set<String>>(emptySet())
        private set
    private var seriesEpisodes by mutableStateOf<Map<String, List<String>>>(emptyMap())
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

    /** Wann die Listen zuletzt vom Anbieter kamen, für das automatische Neuladen. */
    private val loadedAt = HashMap<Kind, Long>()
    private var refreshJob: Job? = null
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

    /** Eigene Ordnung der Listen (ausgeblendet, umbenannt, umsortiert). */
    var custom by mutableStateOf(Custom())
        private set
    private val customView = HashMap<Kind, Triple<Catalog, Custom, Catalog>>()

    // Vor init: activate() braucht sie schon
    /** Ersatzcover, wenn das des Anbieters fehlt oder nicht lädt. null = es gibt keins. */
    val covers = mutableStateMapOf<String, String?>()
    private val coverStage = HashMap<String, Int>()
    private val coverLimit = Semaphore(3)

    /** Geräte abgleichen: offen? und mit welchem Gerät (nach dem Scannen). */
    var syncOpen by mutableStateOf(false)
        private set
    var syncLink by mutableStateOf<SyncLink?>(null)

    fun openSync(link: SyncLink? = null) {
        syncLink = link
        syncOpen = true
    }

    fun closeSync() {
        if (syncLink != null && !syncFromLink) {
            syncLink = null
        } else {
            syncLink = null
            syncOpen = false
        }
        syncFromLink = false
    }

    /** Über einen Link aus der Kamera-App gekommen: Zurück schließt dann ganz. */
    var syncFromLink = false

    /** Nach einem Abgleich: alles neu aus den Dateien lesen. */
    fun reloadStored() {
        AppSettings.reload()
        I18n.init(app)
        liveFormat = store.liveFormat
        livePreview = store.livePreview
        accounts = store.all()
        profiles = profileStore.all()
        profileId = profileStore.activeId
        val a = accounts.firstOrNull { it.id == store.activeId } ?: accounts.firstOrNull()
        if (a != null) {
            activate(a, backendFor(a))
        } else {
            jobs.values.forEach { it.cancel() }
            catalogs.clear()
            library = null
            backend = null
            active = null
            stack.clear()
            stack.add(Screen.Home)
        }
    }

    /** Sender, der geöffnet werden soll, sobald die Liste da ist (aus einer Erinnerung). */
    var pendingChannel by mutableStateOf<String?>(null)

    init {
        RecordRules.init(app)
        (accounts.firstOrNull { it.id == store.activeId } ?: accounts.firstOrNull())?.let { activate(it, backendFor(it)) }
        refreshJob = viewModelScope.launch {
            while (true) {
                delay(10 * 60_000L)
                autoRefresh()
            }
        }
    }

    /**
     * Läuft die App lange (Fernseher mit Dauersender), Senderliste, Filme, Serien und Programm in den eingestellten
     * Abständen neu holen. Die alten Listen bleiben stehen, bis die neuen da sind, und auch, wenn es nicht klappt.
     */
    internal fun autoRefresh(now: Long = System.currentTimeMillis()) {
        val b = backend ?: return
        applyRecordRules()
        val maxAge = AppSettings.refreshMs
        if (maxAge == Long.MAX_VALUE) return
        val due = Kind.entries.filter { k -> catalogs[k] is Load.Ready<*> && now - (loadedAt[k] ?: now) >= maxAge && jobs[k]?.isActive != true }
        if (due.isNotEmpty()) {
            (b as? M3uBackend)?.reset()
            val format = liveFormat
            due.forEach { kind ->
                jobs[kind] = viewModelScope.launch {
                    val fresh = runCatching { withContext(Dispatchers.IO) { b.catalog(kind, format) } }.getOrNull()
                    if (fresh != null && fresh.streams.isNotEmpty() && backend === b) {
                        catalogs[kind] = Load.Ready(fresh)
                        loadedAt[kind] = System.currentTimeMillis()
                        if (kind == Kind.LIVE) {
                            nowEpg.clear()
                            epgCache.clear()
                            epgRequested.clear()
                        }
                    } else {
                        // Nächster Versuch beim nächsten Durchgang in einer Stunde
                        loadedAt[kind] = System.currentTimeMillis() - maxAge + 3_600_000L
                    }
                }
            }
        }
        refreshEpg(force = false)
    }

    // ---------- Konten ----------

    private fun activate(a: Account, b: Backend) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        statusJob?.cancel()
        focusJob?.cancel()
        catalogs.clear()
        loadedAt.clear()
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
        loadLibrary(a)
        coverStage.clear()
        customView.clear()
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

    /** Persönliches des Profils für dieses Konto einlesen; Sperren und Senderordnung gelten für alle Profile. */
    private fun loadLibrary(a: Account) {
        Reminders.profile = profileId
        library = Library(app, a.id, profileId).also {
            favorites = it.favorites()
            recent = it.recent()
            continueList = it.continueList()
            watched = it.watched()
            seriesEpisodes = it.seriesEpisodes()
            locked = it.lockedCategories()
            custom = it.custom()
            covers.clear()
            covers.putAll(it.covers())
        }
        publishHome()
    }

    // ---------- Profile ----------

    fun openProfiles() {
        choosingProfile = true
    }

    fun chooseProfile(id: String) {
        choosingProfile = false
        if (id == profileId || profiles.none { it.id == id }) return
        profileId = id
        profileStore.activeId = id
        Reminders.profile = id
        active?.let { a ->
            loadLibrary(a)
            customView.clear()
        }
        tab = Tab.START
        stack.clear()
        stack.add(Screen.Home)
    }

    fun addProfile(name: String): Profile {
        val p = Profile(java.util.UUID.randomUUID().toString().take(8), name.trim().take(30), profiles.size % PROFILE_COLORS)
        profiles = profiles + p
        profileStore.save(profiles)
        return p
    }

    fun renameProfile(id: String, name: String) {
        profiles = profiles.map { if (it.id == id) it.copy(name = name.trim().take(30)) else it }
        profileStore.save(profiles)
    }

    /** Das erste Profil bleibt; die anderen gehen mit Favoriten, Verlauf und Gesehen weg. */
    fun deleteProfile(id: String) {
        if (id.isEmpty()) return
        profiles = profiles.filter { it.id != id }
        profileStore.save(profiles)
        Library.deleteProfile(app, id, accounts)
        Reminders.removeProfile(id)
        if (profileId == id) {
            profileId = ""
            profileStore.activeId = ""
            chooseProfileReload()
        }
    }

    private fun chooseProfileReload() {
        Reminders.profile = profileId
        active?.let { loadLibrary(it) }
        customView.clear()
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
        Library.delete(app, a.id, profiles)
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
                publishHome()
            }
        }
    }

    private fun changeAccount(change: (Account) -> Account): Account? {
        val a = active ?: return null
        val changed = change(a)
        accounts = accounts.map { if (it.id == a.id) changed else it }
        active = changed
        store.save(accounts)
        return changed
    }

    /** Eigene Programmführer-Quellen und ob der des Anbieters dazukommt; lädt alles neu. */
    fun updateEpgSources(urls: List<String>, provider: Boolean) {
        changeAccount { it.copy(epgUrls = urls.map { u -> u.trim() }.filter { u -> u.isNotEmpty() }.distinct(), epgProvider = provider) } ?: return
        refreshEpg(force = true)
    }

    /** Verschiebt alle Sendezeiten; gilt sofort, ohne neu zu laden. */
    fun updateEpgShift(minutes: Int) {
        changeAccount { it.copy(epgShift = minutes.coerceIn(-Account.EPG_SHIFT_MAX, Account.EPG_SHIFT_MAX)) } ?: return
        epgCache.clear()
        epgRequested.clear()
        fullEpgCache.clear()
        nowEpg.clear()
    }

    /** Verschiebung der Sendezeiten des aktiven Anbieters in ms. */
    private val epgShiftMs: Long get() = (active?.epgShift ?: 0) * 60_000L

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
        val base = lockedView(kind) ?: return null
        val c = custom
        if (c.isEmpty) return base
        customView[kind]?.let { (from, with, result) -> if (from === base && with == c) return result }
        return base.customized(kind, c).also { customView[kind] = Triple(base, c, it) }
    }

    private fun lockedView(kind: Kind): Catalog? {
        val raw = rawCatalog(kind) ?: return null
        if (!AppSettings.hasPin || unlocked) return raw
        val ids = lockedIds(kind, raw)
        visible[kind]?.let { (from, with, result) -> if (from === raw && with == ids) return result }
        return raw.without(ids).also { visible[kind] = Triple(raw, ids, it) }
    }

    // ---------- Eigene Ordnung ----------

    private fun saveCustom(c: Custom) {
        custom = c
        library?.saveCustom(c)
    }

    fun hideStream(s: Stream) = saveCustom(custom.copy(hiddenStreams = custom.hiddenStreams + s.key))

    fun showStream(key: String) = saveCustom(custom.copy(hiddenStreams = custom.hiddenStreams - key))

    /** Leerer Name stellt den des Anbieters wieder her. */
    fun renameStream(s: Stream, name: String) {
        val clean = name.trim()
        val original = rawCatalog(s.kind)?.byKey?.get(s.key)?.name
        saveCustom(custom.copy(names = if (clean.isEmpty() || clean == original) custom.names - s.key else custom.names + (s.key to clean)))
    }

    /** Verschiebt einen Sender in der gerade gezeigten Liste um einen Platz. */
    fun moveStream(s: Stream, shown: List<Stream>, up: Boolean) {
        val all = rawCatalog(s.kind)?.streams?.map { it.key }.orEmpty()
        val order = custom.streamOrder.ifEmpty { all }
        saveCustom(custom.copy(streamOrder = moveInOrder(order, shown.map { it.key }, s.key, up)))
    }

    fun hideGroup(kind: Kind, id: String) {
        if (lastCategory[kind] == id) lastCategory[kind] = Catalog.ALL
        saveCustom(custom.copy(hiddenGroups = custom.hiddenGroups + "${kind.name}:$id"))
    }

    fun showGroup(key: String) = saveCustom(custom.copy(hiddenGroups = custom.hiddenGroups - key))

    fun moveGroup(kind: Kind, id: String, up: Boolean) {
        val shown = readyCatalog(kind)?.categories?.map { it.id }?.filter { it != Catalog.ALL }.orEmpty()
        val order = custom.groupOrder[kind].orEmpty().ifEmpty { shown }
        saveCustom(custom.copy(groupOrder = custom.groupOrder + (kind to moveInOrder(order, shown, id, up))))
    }

    fun resetCustom() = saveCustom(Custom())

    /** Namen fürs Zurückholen in den Einstellungen. */
    fun originalName(key: String): String {
        val kind = runCatching { Kind.valueOf(key.substringBefore(':')) }.getOrNull() ?: return key
        return rawCatalog(kind)?.byKey?.get(key)?.name ?: key
    }

    fun groupName(key: String): String {
        val kind = runCatching { Kind.valueOf(key.substringBefore(':')) }.getOrNull() ?: return key
        val id = key.substringAfter(':')
        return rawCatalog(kind)?.categories?.firstOrNull { it.id == id }?.name ?: id
    }

    // ---------- Cover ----------

    /** Bild für die Kachel: erst das des Anbieters, dann ein gefundener Ersatz. */
    fun cover(s: Stream): String? = if (s.key in covers) covers[s.key] else s.logo?.takeIf { it.isNotBlank() }

    /**
     * Das gezeigte Bild lädt nicht oder fehlt: der Reihe nach Szenenbild, Cover aus den Details beim Anbieter
     * und zuletzt TMDB versuchen (nur mit eigenem Schlüssel).
     */
    fun coverFailed(s: Stream, failed: String?) {
        val current = cover(s)
        if (failed != current) return
        val stage = coverStage[s.key] ?: 0
        if (stage < 0 || stage >= 3) return
        coverStage[s.key] = -1
        viewModelScope.launch {
            val tried = setOfNotNull(s.logo, current)
            var next: String? = null
            var st = stage
            while (next == null && st < 3) {
                st++
                next = runCatching {
                    when (st) {
                        1 -> s.backdrop
                        2 -> coverLimit.withPermit {
                            if (s.kind == Kind.MOVIE) {
                                vodInfo(s)?.let { it.cover ?: it.backdrop }
                            } else {
                                (seriesInfo(s) as? Load.Ready<SeriesInfo>)?.value?.let { it.cover ?: it.backdrop }
                            }
                        }
                        else -> coverLimit.withPermit {
                            withContext(Dispatchers.IO) {
                                Tmdb.poster(s.name, s.year, s.kind == Kind.SERIES, AppSettings.language.ifEmpty { "de" })
                            }
                        }
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() && it !in tried }
            }
            coverStage[s.key] = st
            covers[s.key] = next
            if (next != null && st >= 2) {
                library?.let { lib -> lib.saveCovers(lib.covers() + (s.key to next)) }
            }
        }
    }

    // ---------- Startbildschirm des Fernsehers ----------

    private var homeJob: Job? = null

    /** Gehört zu einer gesperrten Kategorie, egal ob gerade entsperrt; im Zweifel ja. */
    private fun lockedForever(key: String): Boolean {
        if (!AppSettings.hasPin) return false
        val kind = runCatching { Kind.valueOf(key.substringBefore(':')) }.getOrNull() ?: return false
        val raw = rawCatalog(kind) ?: return true
        val s = raw.byKey[key] ?: return false
        return s.categoryId in lockedIds(kind, raw)
    }

    /** „Weiterschauen“ und Lieblingssender auf den Startbildschirm, kurz gesammelt. Gesperrtes kommt nie dorthin. */
    private fun publishHome() {
        if (!hasHomeRows(app)) return
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            delay(1500)
            val a = active
            if (a == null) {
                withContext(Dispatchers.IO) { publishHomeRows(app, null, emptyList(), emptyList()) }
                return@launch
            }
            // Ohne Senderliste ließe sich nicht prüfen, was gesperrt ist; dann lieber warten
            val live = readyCatalog(Kind.LIVE) ?: return@launch
            val continued = continueList.filterNot { lockedForever(it.parentKey) }
            val channels = favorites.filter { it.startsWith("LIVE:") }.mapNotNull { live.byKey[it] }.filterNot { lockedForever(it.key) }
            withContext(Dispatchers.IO) { publishHomeRows(app, a.id, continued, channels) }
        }
    }

    /** Vom Startbildschirm: an der gemerkten Stelle weiterschauen. */
    fun playFromHome(context: AppContext, account: String, key: String) {
        if (active?.id != account) accounts.firstOrNull { it.id == account }?.let { select(it) } ?: return
        val e = continueList.firstOrNull { it.key == key } ?: return
        choosingProfile = false
        if (AppSettings.hasPin && !unlocked && lockedForever(e.parentKey)) requirePin { playContinue(context, e) } else playContinue(context, e)
    }

    // ---------- Erinnerungen ----------

    fun remind(s: Stream, e: EpgEntry) {
        val a = active ?: return
        Reminders.add(a.id, profileId, s, e)
    }

    fun watchChannel(account: String, key: String) {
        if (active?.id != account) accounts.firstOrNull { it.id == account }?.let { select(it) }
        pendingChannel = key
        ensureLoaded(Kind.LIVE)
    }

    fun playPending(context: AppContext) {
        val key = pendingChannel ?: return
        val live = readyCatalog(Kind.LIVE) ?: return
        pendingChannel = null
        val s = live.byKey[key] ?: return
        val list = live.streams.filter { it.categoryId == s.categoryId }
        play(context, list.map { it.toPlayItem() }, list.indexOf(s).coerceAtLeast(0))
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
        publishHome()
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
                Load.Ready(withContext(Dispatchers.IO) { b.catalog(kind, format) }).also { loadedAt[kind] = System.currentTimeMillis() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Load.Error(friendlyError(e))
            }
            if (kind == Kind.LIVE) refreshEpg(force = false)
            publishHome()
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
        val b = backend ?: return Load.Error(t("Kein Anbieter ausgewählt."))
        return try {
            val info = withContext(Dispatchers.IO) { b.seriesInfo(s.id) }
            // Folgen merken, damit das Plakat zeigen kann, wie viel schon gesehen ist
            library?.let { lib -> seriesEpisodes = lib.saveSeriesEpisodes(s.key, info.seasons.toSortedMap().values.flatten().map { it.key }) }
            Load.Ready(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Error(friendlyError(e))
        }
    }

    val hasEpg: Boolean get() = backend?.hasEpg == true || (epgState as? EpgState.Ready)?.let { it.count > 0 } == true

    /** Mit dem Namen des Anbieters, auch wenn der Nutzer den Sender umbenannt hat. */
    private fun epgKey(s: Stream): String = EpgImport.key(s, rawCatalog(s.kind)?.byKey?.get(s.key)?.name ?: s.name)

    /** Liest den Programmführer ein, wenn er älter als in den Einstellungen festgelegt ist, gar keiner da ist oder [force]. */
    fun refreshEpg(force: Boolean) {
        val a = active ?: return
        val b = backend ?: return
        val live = rawCatalog(Kind.LIVE)?.streams?.takeIf { it.isNotEmpty() } ?: return
        if (epgJob?.isActive == true && !force) return
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            try {
                val old = withContext(Dispatchers.IO) { epgDb.info(a.id) }
                if (!force && old != null && System.currentTimeMillis() - old.time < AppSettings.refreshMs) {
                    epgState = EpgState.Ready(old.time, old.count)
                    applyRecordRules()
                    return@launch
                }
                val urls = withContext(Dispatchers.IO) { EpgImport.urls(a) { b.epgUrl() } }
                if (urls.isEmpty()) {
                    epgState = EpgState.None
                    return@launch
                }
                epgState = EpgState.Loading(0)
                val context = currentCoroutineContext()
                val result = withContext(Dispatchers.IO) {
                    EpgImport.run(
                        epgDb, a, urls, live,
                        onProgress = { n -> viewModelScope.launch { if (epgState is EpgState.Loading) epgState = EpgState.Loading(n) } },
                        isCancelled = { !context.isActive },
                    )
                }
                epgCache.clear()
                epgRequested.clear()
                fullEpgCache.clear()
                nowEpg.clear()
                epgState = EpgState.Ready(System.currentTimeMillis(), result.count, result.failed)
                applyRecordRules()
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
        val shift = epgShiftMs
        val stored = withContext(Dispatchers.IO) {
            runCatching { epgDb.programmes(a.id, epgKey(s), from - shift, to - shift) }.getOrDefault(emptyList()).shifted(shift)
        }
        if (stored.isNotEmpty() || !b.hasEpg) return stored
        val all = fullEpgCache[s.id] ?: epgLimit.withPermit {
            withContext(Dispatchers.IO) { runCatching { b.fullEpg(s.id).ifEmpty { b.epg(s.id) } }.getOrDefault(emptyList()) }
        }.also { fullEpgCache[s.id] = it }
        return all.shifted(shift).filter { it.end > from && it.start < to }
    }

    /** Programm des Senders, fünf Minuten zwischengespeichert. */
    suspend fun epg(item: PlayItem): List<EpgEntry> {
        val b = backend ?: return emptyList()
        val a = active ?: return emptyList()
        val id = item.streamId ?: return emptyList()
        val now = System.currentTimeMillis()
        epgCache[id]?.let { (time, list) -> if (now - time < 5 * 60_000) return list }
        val stream = rawCatalog(Kind.LIVE)?.byKey?.get("LIVE:$id")
        val shift = epgShiftMs
        val list = withContext(Dispatchers.IO) {
            val stored = stream?.let { s -> runCatching { epgDb.programmes(a.id, epgKey(s), now - 3_600_000 - shift, now + 12 * 3_600_000 - shift) }.getOrNull() }
            (if (!stored.isNullOrEmpty() || !b.hasEpg) stored.orEmpty() else b.epg(id)).shifted(shift)
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
        publishHome()
    }

    fun isFavorite(key: String): Boolean = key in favoriteSet

    fun favoriteStreams(kind: Kind): List<Stream> {
        val catalog = readyCatalog(kind) ?: return emptyList()
        return favorites.mapNotNull { catalog.byKey[it] }
    }

    fun moveFavorite(s: Stream, up: Boolean) {
        library?.let { favorites = it.moveFavorite(s.key, up) }
        publishHome()
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

    fun playCatchup(context: AppContext, s: Stream, e: EpgEntry, failed: () -> Unit) {
        viewModelScope.launch {
            val item = catchupItem(s, e)
            if (item != null) play(context, listOf(item), 0) else failed()
        }
    }

    // ---------- Serienaufnahmen ----------

    private var rulesJob: Job? = null

    /** Neue Regel: gleich einplanen, was sie findet. */
    fun addRecordRule(rule: RecordRule) {
        RecordRules.add(rule)
        applyRecordRules(force = true)
    }

    /** Regel weg; schon eingeplante Aufnahmen bleiben, damit nichts überraschend verschwindet. */
    fun removeRecordRule(rule: RecordRule) = RecordRules.remove(rule)

    /** Plant für alle Regeln des Anbieters ein, was im Programm neu dazugekommen ist. */
    fun applyRecordRules(force: Boolean = false) {
        val a = active ?: return
        val live = rawCatalog(Kind.LIVE) ?: return
        val rules = RecordRules.list.filter { it.account == a.id }
        if (rules.isEmpty() || (rulesJob?.isActive == true && !force)) return
        rulesJob?.cancel()
        rulesJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            for (rule in rules) {
                val s = live.byKey[rule.channelKey] ?: continue
                val guide = runCatching { programmes(s, now - 86_400_000L, now + RecordRules.AHEAD) }.getOrDefault(emptyList())
                val wanted = RecordRules.plan(rule, now, { from, to -> guide.filter { it.end > from && it.start < to } })
                val handled = ArrayList<Long>()
                for (p in wanted) {
                    val exists = Transfers.list.any { it.type == TransferType.RECORD && it.key == s.key && (it.endAt == p.end || it.startAt == p.start) }
                    if (exists || Transfers.record(s.key, p.title, s.name, s.logo, s.recordUrl() ?: s.url, p.start, p.end)) handled += p.programmeStart
                }
                if (handled.isNotEmpty()) RecordRules.markPlanned(rule, handled, now)
            }
        }
    }

    /** Nimmt eine Sendung mit etwas Vor- und Nachlauf auf. */
    fun record(s: Stream, e: EpgEntry): Boolean =
        Transfers.record(s.key, e.title, s.name, s.logo, s.recordUrl() ?: s.url, e.start - 60_000, e.end + 5 * 60_000)

    fun downloadMovie(s: Stream) = Transfers.download(s.toPlayItem(vodInfos[s.key]?.backdrop ?: s.logo))

    fun downloadEpisode(series: Stream, info: SeriesInfo?, e: Episode) = Transfers.download(episodeItem(series, info, e))

    fun playTransfer(context: AppContext, x: Transfer) {
        val lib = library ?: return
        val item = PlayItem(
            key = "FILE:${x.id}", title = x.title, subtitle = x.subtitle, url = Stores.playUrl(x.file),
            live = false, image = x.image, local = true,
        )
        playItems(context, lib, listOf(item), 0)
    }

    fun playLocal(context: AppContext, items: List<PlayItem>, index: Int) {
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
            watched = it.watched()
        }
        publishHome()
    }

    // ---------- Gesehen ----------

    fun isWatched(key: String): Boolean = key in watched

    /** Von Hand als gesehen oder ungesehen markieren, auch eine ganze Staffel. */
    fun setWatched(keys: Collection<String>, on: Boolean) {
        val lib = library ?: return
        watched = lib.setWatched(keys, on)
        continueList = lib.continueList()
        publishHome()
    }

    /** Gesehene und alle Folgen einer Serie, sobald sie einmal geöffnet war. */
    fun seriesProgress(seriesKey: String): Pair<Int, Int>? {
        val eps = seriesEpisodes[seriesKey]?.takeIf { it.isNotEmpty() } ?: return null
        return eps.count { it in watched } to eps.size
    }

    /** Haken für Filme und fertige Serien, „3/10“ für angefangene Serien. */
    fun watchMark(s: Stream): Pair<Boolean, String?> = when (s.kind) {
        Kind.MOVIE -> isWatched(s.key) to null
        Kind.SERIES -> seriesProgress(s.key)?.let { (seen, all) ->
            when {
                seen >= all -> true to null
                seen > 0 -> false to "$seen/$all"
                else -> false to null
            }
        } ?: (false to null)
        Kind.LIVE -> false to null
    }

    fun removeContinue(entry: ContinueEntry) {
        library?.let {
            it.removeContinue(entry.key)
            it.clearPosition(entry.key)
            continueList = it.continueList()
        }
        publishHome()
    }

    fun play(context: AppContext, items: List<PlayItem>, index: Int) {
        val lib = library ?: return
        playItems(context, lib, items, index)
    }

    private fun playItems(context: AppContext, lib: Library, items: List<PlayItem>, index: Int) {
        if (items.isEmpty()) return
        PlaybackQueue.items = items
        PlaybackQueue.startIndex = index.coerceIn(items.indices)
        PlaybackQueue.library = lib
        PlaybackQueue.epg = { epg(it) }
        PlaybackQueue.onWatched = { refreshLibrary() }
        PlaybackQueue.catchup = { item, e -> rawCatalog(Kind.LIVE)?.byKey?.get(item.key)?.let { catchupItem(it, e) } }
        val account = active?.id
        PlaybackQueue.onPlay = { item ->
            if (!item.local) {
                lib.addRecent(item.recentKey)
                recent = lib.recent()
            }
            if (item.live && !item.catchup && !item.local && account != null) AppSettings.lastChannel = account + "\n" + item.key
        }
        context.openPlayer()
    }

    fun playMovie(context: AppContext, s: Stream, fromStart: Boolean) {
        if (fromStart) library?.clearPosition(s.key)
        val image = vodInfos[s.key]?.backdrop ?: s.logo
        play(context, listOf(s.toPlayItem(image)), 0)
    }

    fun playEpisodes(context: AppContext, series: Stream, info: SeriesInfo?, episodes: List<Episode>, index: Int) {
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
    fun playContinue(context: AppContext, e: ContinueEntry) {
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
    fun openStream(context: AppContext, s: Stream, siblings: List<Stream>) {
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

/** So viele Farben gibt es für Profile (ui/Profiles.kt). */
const val PROFILE_COLORS = 6
