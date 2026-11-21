package io.github.phillhf.iptv.data

import android.content.Context
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type.name)
        .put("server", server).put("username", username).put("password", password).put("m3uUrl", m3uUrl)

    companion object {
        fun fromJson(o: JSONObject) = Account(
            id = o.getString("id"),
            name = o.optString("name"),
            type = runCatching { SourceType.valueOf(o.optString("type")) }.getOrDefault(SourceType.XTREAM),
            server = o.optString("server"),
            username = o.optString("username"),
            password = o.optString("password"),
            m3uUrl = o.optString("m3uUrl"),
        )
    }
}

/** Konten und Einstellungen. Liegen nur in den privaten App-Daten auf diesem Gerät. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

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

/** Favoriten, zuletzt gesehen und Wiedergabepositionen, getrennt pro Konto. */
class Library(context: Context, accountId: String) {
    private val prefs = context.getSharedPreferences("library_$accountId", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

    fun toggleFavorite(key: String): Set<String> {
        val current = favorites()
        val now = if (key in current) current - key else current + key
        prefs.edit().putStringSet("favorites", now).apply()
        return now
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

    fun position(key: String): Long = prefs.getLong("pos:$key", 0L)

    fun savePosition(key: String, positionMs: Long) {
        prefs.edit().putLong("pos:$key", positionMs).apply()
    }

    fun clearPosition(key: String) {
        prefs.edit().remove("pos:$key").apply()
    }

    companion object {
        fun delete(context: Context, accountId: String) {
            context.deleteSharedPreferences("library_$accountId")
        }
    }
}
