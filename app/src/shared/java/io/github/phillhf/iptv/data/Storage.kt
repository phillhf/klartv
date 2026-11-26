package io.github.phillhf.iptv.data

import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.deletePrefs
import io.github.phillhf.iptv.platform.openPrefs
import org.json.JSONArray
import org.json.JSONObject

enum class SourceType { XTREAM, M3U }

data class Account(
    val id: String,
    val name: String,
    val type: SourceType,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    /** Eigene XMLTV-Adresse, leer = die des Anbieters. */
    val epgUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type.name)
        .put("server", server).put("username", username).put("password", password).put("m3uUrl", m3uUrl)
        .put("epgUrl", epgUrl)

    companion object {
        fun fromJson(o: JSONObject) = Account(
            id = o.getString("id"),
            name = o.optString("name"),
            type = runCatching { SourceType.valueOf(o.optString("type")) }.getOrDefault(SourceType.XTREAM),
            server = o.optString("server"),
            username = o.optString("username"),
            password = o.optString("password"),
            m3uUrl = o.optString("m3uUrl"),
            epgUrl = o.optString("epgUrl"),
        )
    }
}

/** Konten und Einstellungen. Liegen nur in den privaten App-Daten auf diesem Gerät. */
class AccountStore(context: AppContext) {
    private val prefs = context.openPrefs("accounts")

    fun all(): List<Account> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map { Account.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(list: List<Account>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    var activeId: String?
        get() = prefs.getString("active", null)
        set(value) = prefs.edit().putString("active", value).apply()

    /** „ts“ (MPEG-TS, Standard) oder „m3u8“ (HLS) für Live-TV bei Xtream-Anbietern. */
    var liveFormat: String
        get() = prefs.getString("live_format", "ts") ?: "ts"
        set(value) = prefs.edit().putString("live_format", value).apply()

    /** Live-Vorschau des gewählten Senders in der Senderliste (TV und Tablet). */
    var livePreview: Boolean
        get() = prefs.getBoolean("live_preview", true)
        set(value) = prefs.edit().putBoolean("live_preview", value).apply()
}

/** Ein Profil im Haushalt. Das erste hat die ID "" und behält die Daten von vor den Profilen. */
data class Profile(val id: String, val name: String, val color: Int = 0)

/** Profile: Anbieter und Einstellungen gelten für alle, Favoriten, Verlauf, Gesehen und Erinnerungen je Profil. */
class ProfileStore(context: AppContext) {
    private val prefs = context.openPrefs("profiles")

    /** Welches Profil dieses Gerät gerade nutzt. Wird beim Abgleich nicht übertragen. */
    private val device = context.openPrefs("profile_device")

    fun all(): List<Profile> {
        val saved = runCatching {
            val arr = JSONArray(prefs.getString("list", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(o.getString("id"), o.optString("name"), o.optInt("color"))
            }
        }.getOrDefault(emptyList())
        // Das erste Profil gibt es immer
        return if (saved.none { it.id == "" }) listOf(Profile("", "")) + saved else saved
    }

    fun save(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    var activeId: String
        get() = device.getString("active", "").orEmpty().takeIf { id -> all().any { it.id == id } } ?: ""
        set(value) = device.edit().putString("active", value).apply()
}

/**
 * Favoriten, zuletzt gesehen und Wiedergabepositionen, getrennt pro Konto und Profil.
 * Sperren, eigene Senderordnung und Ersatzcover gelten für alle Profile eines Kontos.
 */
class Library(context: AppContext, accountId: String, profileId: String = "") {
    /** Gemeinsam für alle Profile; beim ersten Profil liegt auch das Persönliche hier (wie vor den Profilen). */
    private val shared = context.openPrefs("library_$accountId")
    private val prefs = if (profileId.isEmpty()) shared else context.openPrefs(prefsName(accountId, profileId))

    /** Favoriten in der Reihenfolge des Nutzers. Bis 0.2.0 waren sie eine ungeordnete Menge. */
    fun favorites(): List<String> {
        prefs.getString("favorite_list", null)?.let { saved -> return saved.split('\n').filter { it.isNotBlank() } }
        return prefs.getStringSet("favorites", emptySet()).orEmpty().sorted()
    }

    fun toggleFavorite(key: String): List<String> {
        val current = favorites()
        return saveFavorites(if (key in current) current - key else current + key)
    }

    /** Verschiebt einen Favoriten innerhalb seiner Art (Sender, Filme, Serien) um einen Platz. */
    fun moveFavorite(key: String, up: Boolean): List<String> {
        val list = favorites().toMutableList()
        val from = list.indexOf(key)
        val kind = key.substringBefore(':')
        val same = list.indices.filter { list[it].substringBefore(':') == kind }
        val place = same.indexOf(from)
        val to = same.getOrNull(if (up) place - 1 else place + 1)
        if (from < 0 || to == null) return list
        list[from] = list[to].also { list[to] = list[from] }
        return saveFavorites(list)
    }

    private fun saveFavorites(list: List<String>): List<String> {
        prefs.edit().putString("favorite_list", list.joinToString("\n")).remove("favorites").apply()
        return list
    }

    /** Gesperrte Kategorien als „ART:ID“. Null, solange der Nutzer noch nichts festgelegt hat. */
    fun lockedCategories(): Set<String>? =
        shared.getString("locked", null)?.split('\n')?.filter { it.isNotBlank() }?.toSet()

    fun saveLockedCategories(set: Set<String>) {
        shared.edit().putString("locked", set.joinToString("\n")).apply()
    }

    /** Ersatzcover, die schon gefunden wurden: Schlüssel → Bildadresse. */
    fun covers(): Map<String, String> = runCatching {
        val o = JSONObject(shared.getString("covers", "{}") ?: "{}")
        o.keys().asSequence().toList().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun saveCovers(map: Map<String, String>) {
        val o = JSONObject()
        map.entries.toList().takeLast(5000).forEach { (k, v) -> o.put(k, v) }
        shared.edit().putString("covers", o.toString()).apply()
    }

    fun custom(): Custom = Custom.fromJson(shared.getString("custom", null))

    fun saveCustom(c: Custom) {
        shared.edit().putString("custom", c.toJson()).apply()
    }

    fun recent(): List<String> = prefs.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun addRecent(key: String) {
        val list = (listOf(key) + recent().filter { it != key }).take(40)
        prefs.edit().putString("recent", list.joinToString("\n")).apply()
    }

    fun continueList(): List<ContinueEntry> = runCatching {
        val arr = JSONArray(prefs.getString("continue", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ContinueEntry(
                key = o.getString("key"),
                parentKey = o.getString("parent"),
                title = o.getString("title"),
                subtitle = o.optString("subtitle").takeIf { it.isNotBlank() },
                image = o.optString("image").takeIf { it.isNotBlank() },
                url = o.getString("url"),
                position = o.optLong("pos"),
                duration = o.optLong("dur"),
            )
        }
    }.getOrDefault(emptyList())

    /** Setzt den Eintrag nach vorn; pro Film oder Serie gibt es nur einen. */
    fun updateContinue(entry: ContinueEntry) {
        saveContinue(listOf(entry) + continueList().filter { it.parentKey != entry.parentKey })
    }

    fun removeContinue(key: String) {
        saveContinue(continueList().filter { it.key != key })
    }

    private fun saveContinue(list: List<ContinueEntry>) {
        val arr = JSONArray()
        list.take(25).forEach {
            arr.put(
                JSONObject().put("key", it.key).put("parent", it.parentKey).put("title", it.title)
                    .put("subtitle", it.subtitle ?: "").put("image", it.image ?: "").put("url", it.url)
                    .put("pos", it.position).put("dur", it.duration),
            )
        }
        prefs.edit().putString("continue", arr.toString()).apply()
    }

    // ---------- Gesehen ----------

    /** Filme und Folgen, die zu Ende geschaut oder von Hand als gesehen markiert wurden. */
    fun watched(): Set<String> = prefs.getString("watched", "").orEmpty().split('\n').filterTo(LinkedHashSet()) { it.isNotBlank() }

    fun setWatched(keys: Collection<String>, on: Boolean): Set<String> {
        val set = LinkedHashSet(watched())
        if (on) {
            set.removeAll(keys.toSet())
            set.addAll(keys)
            val e = prefs.edit()
            keys.forEach { e.remove("pos:$it") }
            e.apply()
            saveContinue(continueList().filter { it.key !in keys })
        } else {
            set.removeAll(keys.toSet())
        }
        prefs.edit().putString("watched", set.toList().takeLast(30_000).joinToString("\n")).apply()
        return set
    }

    /** Zu Ende geschaut: Position weg, aus „Weiterschauen“ raus, als gesehen merken. */
    fun finished(key: String) {
        clearPosition(key)
        removeContinue(key)
        setWatched(listOf(key), true)
    }

    /** Folgen je Serie, damit Plakate zeigen können, wie viel schon gesehen ist. Schlüssel → Folgen-Schlüssel. */
    fun seriesEpisodes(): Map<String, List<String>> = runCatching {
        val o = JSONObject(shared.getString("series_episodes", "{}") ?: "{}")
        o.keys().asSequence().toList().associateWith { k ->
            o.getString(k).split(',').filter { it.isNotBlank() }.map { "EP:$it" }
        }
    }.getOrDefault(emptyMap())

    fun saveSeriesEpisodes(seriesKey: String, episodes: List<String>): Map<String, List<String>> {
        val map = LinkedHashMap(seriesEpisodes())
        if (map[seriesKey] == episodes) return map
        map.remove(seriesKey)
        map[seriesKey] = episodes
        val o = JSONObject()
        map.entries.toList().takeLast(1000).forEach { (k, v) -> o.put(k, v.joinToString(",") { it.removePrefix("EP:") }) }
        shared.edit().putString("series_episodes", o.toString()).apply()
        return map
    }

    fun position(key: String): Long = prefs.getLong("pos:$key", 0L)

    fun savePosition(key: String, positionMs: Long) {
        prefs.edit().putLong("pos:$key", positionMs).apply()
    }

    fun clearPosition(key: String) {
        prefs.edit().remove("pos:$key").apply()
    }

    companion object {
        fun prefsName(accountId: String, profileId: String) = if (profileId.isEmpty()) "library_$accountId" else "library_$accountId@$profileId"

        /** Konto weg: gemeinsame Daten und die aller Profile. */
        fun delete(context: AppContext, accountId: String, profiles: List<Profile> = ProfileStore(context).all()) {
            context.deletePrefs("library_$accountId")
            profiles.filter { it.id.isNotEmpty() }.forEach { context.deletePrefs(prefsName(accountId, it.id)) }
        }

        /** Profil weg: seine persönlichen Daten bei allen Konten. */
        fun deleteProfile(context: AppContext, profileId: String, accounts: List<Account>) {
            if (profileId.isEmpty()) return
            accounts.forEach { context.deletePrefs(prefsName(it.id, profileId)) }
        }
    }
}
