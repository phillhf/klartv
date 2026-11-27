package io.github.phillhf.iptv.server

import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AccountStore
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.Backend
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.EpgDb
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.EpgImport
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.Profile
import io.github.phillhf.iptv.data.ProfileStore
import io.github.phillhf.iptv.data.M3uBackend
import io.github.phillhf.iptv.data.SeriesInfo
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.data.VodInfo
import io.github.phillhf.iptv.data.XtreamBackend
import io.github.phillhf.iptv.data.customized
import io.github.phillhf.iptv.data.looksAdult
import io.github.phillhf.iptv.data.shifted
import io.github.phillhf.iptv.platform.AppContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Was die App im ViewModel macht, für den Server: Anbieter, Listen, Programmführer und Bibliothek.
 * Alle Aufrufe blockieren; der Webserver ruft sie aus eigenen Threads auf.
 */
class Core(val context: AppContext) {
    private val accounts = AccountStore(context)

    /** Für Tests: eine Beispiel-Quelle statt des echten Anbieters. */
    var backendFor: (Account) -> Backend = { a -> if (a.type == SourceType.M3U) M3uBackend(a) else XtreamBackend(a) }

    @Volatile var active: Account? = null
        private set

    @Volatile private var backend: Backend? = null

    private class Cached<T>(val value: T, val time: Long)

    private val catalogs = ConcurrentHashMap<Kind, Cached<Catalog>>()
    private val catalogLocks = Kind.entries.associateWith { Any() }
    private val series = ConcurrentHashMap<String, Cached<SeriesInfo>>()
    private val vod = ConcurrentHashMap<String, Cached<VodInfo?>>()
    private val shortEpg = ConcurrentHashMap<String, Cached<List<EpgEntry>>>()
    private val epgLimit = Semaphore(4)

    val epgDb = EpgDb(context)
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "programmfuehrer").apply { isDaemon = true } }

    /** Stand des Programmführers für die Einstellungen. */
    @Volatile var epgStatus: String = ""
        private set

    @Volatile private var epgRunning = false

    init {
        reload()
        // Alle 30 Minuten nachsehen, ob der Programmführer älter als zwölf Stunden ist
        timer.scheduleWithFixedDelay({ runCatching { refreshEpg(false) } }, 1, 30, TimeUnit.MINUTES)
    }

    /** Nach dem Start, einem Abgleich oder einer Änderung der Anbieter. */
    @Synchronized
    fun reload() {
        val list = accounts.all()
        val a = list.firstOrNull { it.id == accounts.activeId } ?: list.firstOrNull()
        active = a
        backend = a?.let(backendFor)
        catalogs.clear()
        series.clear()
        vod.clear()
        shortEpg.clear()
        epgStatus = ""
    }

    fun accounts(): List<Account> = accounts.all()

    /** „ts“ oder „m3u8“ für Live-TV bei Xtream-Anbietern, wie in der App. */
    var liveFormat: String
        get() = accounts.liveFormat
        set(value) {
            accounts.liveFormat = value
            catalogs.remove(Kind.LIVE)
        }

    /** Prüft den Zugang beim Anbieter und speichert ihn dann. */
    fun saveAccount(a: Account) {
        backendFor(a).login()
        val list = accounts.all()
        accounts.save(if (list.any { it.id == a.id }) list.map { if (it.id == a.id) a else it } else list + a)
        accounts.activeId = a.id
        reload()
        Thread { runCatching { refreshEpg(true) } }.start()
    }

    /** Programmführer-Einstellungen des aktiven Anbieters, ohne neue Anmeldung. */
    fun updateEpg(a: Account, reload: Boolean) {
        accounts.save(accounts.all().map { if (it.id == a.id) a else it })
        synchronized(this) { if (active?.id == a.id) active = a }
        shortEpg.clear()
        if (reload) Thread { runCatching { refreshEpg(true) } }.start()
    }

    fun deleteAccount(id: String) {
        accounts.save(accounts.all().filter { it.id != id })
        if (accounts.activeId == id) accounts.activeId = null
        Library.delete(context, id)
        runCatching { epgDb.remove(id) }
        reload()
    }

    fun activate(id: String) {
        if (accounts.all().none { it.id == id }) return
        accounts.activeId = id
        reload()
        Thread { runCatching { refreshEpg(false) } }.start()
    }

    private fun requireBackend(): Backend = backend ?: throw IOException("Noch kein Anbieter eingerichtet.")

    /** Profile wie in der App; "" ist das erste. */
    fun profiles(): List<Profile> = ProfileStore(context).all()

    /** Daten des Kontos für dieses Profil; ein unbekanntes Profil fällt auf das erste zurück. */
    fun library(profile: String = ""): Library? = active?.let { a ->
        Library(context, a.id, profile.takeIf { p -> profiles().any { it.id == p } } ?: "")
    }

    // ---------- Listen ----------

    /** Liste vom Anbieter, zwischengespeichert so lange wie in den Einstellungen („Automatisch neu laden“). */
    fun rawCatalog(kind: Kind, refresh: Boolean = false): Catalog {
        val b = requireBackend()
        synchronized(catalogLocks.getValue(kind)) {
            catalogs[kind]?.let { if (!refresh && System.currentTimeMillis() - it.time < AppSettings.refreshMs) return it.value }
            // Playlist neu holen, sonst kommt dieselbe aus dem Speicher
            if (refresh) (b as? M3uBackend)?.reset()
            val c = b.catalog(kind, accounts.liveFormat)
            if (backend !== b) throw IOException("Der Anbieter wurde gewechselt.")
            catalogs[kind] = Cached(c, System.currentTimeMillis())
            if (kind == Kind.LIVE) Thread { runCatching { refreshEpg(false) } }.start()
            return c
        }
    }

    /** Die Liste, wie der Nutzer sie eingerichtet hat: eigene Ordnung, ohne gesperrte Gruppen. */
    fun catalog(kind: Kind, unlocked: Boolean): Catalog {
        val raw = rawCatalog(kind)
        val lib = library()
        val shown = if (AppSettings.hasPin && !unlocked) raw.without(lockedIds(kind, raw, lib)) else raw
        return lib?.custom()?.let { shown.customized(kind, it) } ?: shown
    }

    private fun lockedIds(kind: Kind, raw: Catalog, lib: Library?): Set<String> {
        val saved = lib?.lockedCategories()
        return if (saved == null) {
            raw.categories.filter { looksAdult(it.name) }.mapTo(HashSet()) { it.id }
        } else {
            saved.filter { it.startsWith("${kind.name}:") }.mapTo(HashSet()) { it.substringAfter(':') }
        }
    }

    fun stream(key: String): Stream? {
        val kind = runCatching { Kind.valueOf(key.substringBefore(':')) }.getOrNull() ?: return null
        return rawCatalog(kind).byKey[key]
    }

    fun vodInfo(id: String): VodInfo? {
        vod[id]?.let { if (System.currentTimeMillis() - it.time < 3_600_000L) return it.value }
        return requireBackend().vodInfo(id).also { vod[id] = Cached(it, System.currentTimeMillis()) }
    }

    fun seriesInfo(id: String): SeriesInfo {
        series[id]?.let { if (System.currentTimeMillis() - it.time < 3_600_000L) return it.value }
        return requireBackend().seriesInfo(id).also { info ->
            series[id] = Cached(info, System.currentTimeMillis())
            // Wie in der App: Folgen merken, damit Plakate „3/10“ zeigen können
            library()?.saveSeriesEpisodes("SERIES:$id", info.seasons.toSortedMap().values.flatten().map { it.key })
        }
    }

    // ---------- Abspielen ----------

    /** Was abgespielt werden soll, als Verweis aus der Weboberfläche. Die Adresse des Anbieters bleibt hier. */
    data class Target(val url: String, val live: Boolean, val durationMs: Long = 0)

    fun resolve(ref: String, profile: String = ""): Target? {
        val parts = ref.split(':')
        return when (parts[0]) {
            "LIVE" -> stream(ref)?.let { Target(it.url, live = true) }
            "MOVIE" -> stream(ref)?.let { Target(it.url, live = false) }
            "EP" -> {
                if (parts.size < 3) return null
                seriesInfo(parts[1]).seasons.values.flatten().firstOrNull { it.id == parts[2] }?.let { Target(it.url, live = false) }
            }
            "CU" -> {
                if (parts.size < 4) return null
                val s = stream("LIVE:${parts[1]}") ?: return null
                val start = parts[2].toLongOrNull() ?: return null
                val end = parts[3].toLongOrNull() ?: return null
                requireBackend().catchupUrl(s, start, end)?.let { Target(it, live = false, durationMs = end - start) }
            }
            // Aus „Weiterschauen“, auch von einem anderen Gerät
            "CONT" -> library(profile)?.continueList()?.firstOrNull { it.key == ref.removePrefix("CONT:") }?.let { Target(it.url, live = false) }
            else -> null
        }
    }

    // ---------- Programmführer ----------

    /** Mit dem Namen des Anbieters, auch wenn der Nutzer den Sender umbenannt hat. Wie in der App. */
    private fun epgKey(s: Stream): String = EpgImport.key(s, catalogs[Kind.LIVE]?.value?.byKey?.get(s.key)?.name ?: s.name)

    /** Verschiebung der Sendezeiten des aktiven Anbieters in ms. */
    private val shiftMs: Long get() = (active?.epgShift ?: 0) * 60_000L

    /** Programm eines Senders im Zeitraum, aus dem Programmführer oder direkt vom Anbieter. */
    fun programmes(s: Stream, from: Long, to: Long): List<EpgEntry> {
        val a = active ?: return emptyList()
        val b = backend ?: return emptyList()
        val shift = shiftMs
        val stored = runCatching { epgDb.programmes(a.id, epgKey(s), from - shift, to - shift) }.getOrDefault(emptyList()).shifted(shift)
        if (stored.isNotEmpty() || !b.hasEpg) return stored
        val key = "full:${s.id}"
        val all = shortEpg[key]?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }?.value ?: run {
            epgLimit.acquire()
            try {
                runCatching { b.fullEpg(s.id).ifEmpty { b.epg(s.id) } }.getOrDefault(emptyList())
            } finally {
                epgLimit.release()
            }
        }.also { shortEpg[key] = Cached(it, System.currentTimeMillis()) }
        return all.shifted(shift).filter { it.end > from && it.start < to }
    }

    /** Laufende und nächste Sendung. */
    fun nowNext(s: Stream): List<EpgEntry> {
        val now = System.currentTimeMillis()
        val a = active ?: return emptyList()
        val b = backend ?: return emptyList()
        val shift = shiftMs
        val stored = runCatching { epgDb.programmes(a.id, epgKey(s), now - shift, now + 8 * 3_600_000L - shift) }.getOrDefault(emptyList()).shifted(shift)
        if (stored.isNotEmpty() || !b.hasEpg) return stored.take(2)
        val key = "short:${s.id}"
        shortEpg[key]?.let { if (now - it.time < 10 * 60_000L) return it.value.shifted(shift).filter { e -> e.end > now }.take(2) }
        epgLimit.acquire()
        val list = try {
            runCatching { b.epg(s.id) }.getOrDefault(emptyList())
        } finally {
            epgLimit.release()
        }
        shortEpg[key] = Cached(list, now)
        return list.shifted(shift).filter { it.end > now }.take(2)
    }

    /** Liest den Programmführer ein, wenn er älter als in den Einstellungen festgelegt ist, gar keiner da ist oder [force]. */
    fun refreshEpg(force: Boolean) {
        val a = active ?: return
        val b = backend ?: return
        val live = catalogs[Kind.LIVE]?.value?.streams?.takeIf { it.isNotEmpty() } ?: return
        synchronized(this) {
            if (epgRunning) return
            epgRunning = true
        }
        try {
            val old = epgDb.info(a.id)
            if (!force && old != null && System.currentTimeMillis() - old.time < AppSettings.refreshMs) {
                if (!epgStatus.startsWith("ok:")) epgStatus = "ok:${old.time}:${old.count}"
                return
            }
            val urls = EpgImport.urls(a) { b.epgUrl() }
            if (urls.isEmpty()) {
                epgStatus = "none"
                return
            }
            epgStatus = "loading"
            val result = EpgImport.run(epgDb, a, urls, live, onProgress = {}, isCancelled = { active?.id != a.id })
            val count = result.count
            shortEpg.clear()
            epgStatus = "ok:${System.currentTimeMillis()}:$count:${result.failed}"
        } catch (e: Throwable) {
            epgStatus = "error:" + (e.message ?: e.javaClass.simpleName)
        } finally {
            epgRunning = false
        }
    }

    // ---------- Weiterschauen ----------

    /** Speichert die Position wie der Player der App; die Adresse kommt vom Server, nicht aus dem Browser. */
    fun saveProgress(ref: String, parentKey: String, title: String, subtitle: String?, image: String?, position: Long, duration: Long, profile: String = "") {
        val lib = library(profile) ?: return
        val key = keyOf(ref) ?: return
        when {
            duration > 120_000 && position > duration - 60_000 -> lib.finished(key)
            position > 5_000 -> {
                val url = resolve(ref, profile)?.url ?: return
                lib.savePosition(key, position)
                lib.updateContinue(ContinueEntry(key, parentKey, title, subtitle, image, url, position, duration))
            }
        }
    }

    /** Schlüssel, unter dem die App die Position merkt. Sendungen von früher werden nicht gemerkt. */
    fun keyOf(ref: String): String? = when {
        ref.startsWith("EP:") -> "EP:" + ref.split(':').getOrElse(2) { "" }
        ref.startsWith("MOVIE:") -> ref
        ref.startsWith("CONT:") -> ref.removePrefix("CONT:")
        else -> null
    }
}
