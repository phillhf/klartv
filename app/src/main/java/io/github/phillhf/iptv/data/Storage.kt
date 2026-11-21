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
