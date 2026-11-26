package io.github.phillhf.iptv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.Prefs
import io.github.phillhf.iptv.platform.cancelReminder
import io.github.phillhf.iptv.platform.openPrefs
import io.github.phillhf.iptv.platform.scheduleReminder
import org.json.JSONArray
import java.util.UUID

/** Erinnerungen an Sendungen. Kurz vor dem Beginn meldet sich die App. */
object Reminders {
    /** So viel vor Beginn. */
    const val AHEAD = 60_000L

    val list = mutableStateListOf<Reminder>()

    /** Gerade fällig; die Oberfläche zeigt dann einen Hinweis. */
    var due by mutableStateOf<Reminder?>(null)

    private var prefs: Prefs? = null
    private var context: AppContext? = null

    @Synchronized
    fun init(context: AppContext) {
        val p = context.openPrefs("reminders")
        if (p === prefs) return
        prefs = p
        this.context = context
        val now = System.currentTimeMillis()
        val saved = runCatching {
            val arr = JSONArray(p.getString("list", "[]"))
            (0 until arr.length()).map { Reminder.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        list.clear()
        list.addAll(saved.filter { it.start > now - 10 * 60_000 })
    }

    private fun save() {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs?.edit()?.putString("list", arr.toString())?.apply()
    }

    /** Profil, dessen Erinnerungen im Programm zu sehen sind. */
    var profile by mutableStateOf("")

    fun find(channel: String, start: Long): Reminder? = list.firstOrNull { it.channel == channel && it.start == start && it.profile == profile }

    fun add(account: String, profile: String, s: Stream, e: EpgEntry): Reminder? {
        if (e.start <= System.currentTimeMillis()) return null
        find(s.key, e.start)?.let { return it }
        val r = Reminder(UUID.randomUUID().toString(), account, s.key, s.name, e.title, e.start, profile)
        list.add(r)
        save()
        context?.scheduleReminder(r)
        return r
    }

    /** Profil gelöscht: seine Erinnerungen auch. */
    fun removeProfile(id: String) {
        list.filter { it.profile == id }.forEach { remove(it) }
    }

    fun remove(r: Reminder) {
        list.removeAll { it.id == r.id }
        save()
        context?.cancelReminder(r)
    }

    /** Nach einem Neustart des Geräts: alle Wecker neu stellen. */
    fun rearm() {
        list.forEach { context?.scheduleReminder(it) }
    }

    /** Vom Wecker aufgerufen. Gibt die Erinnerung zurück, wenn sie noch gilt. */
    fun fire(id: String): Reminder? {
        val r = list.firstOrNull { it.id == id } ?: return null
        list.removeAll { it.id == id }
        save()
        due = r
        return r
    }

    /** Für Uhren ohne eigenen Wecker: alles Fällige auslösen. */
    fun fireDue(now: Long = System.currentTimeMillis()): List<Reminder> =
        list.filter { it.start - AHEAD <= now }.mapNotNull { fire(it.id) }
}
