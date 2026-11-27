package io.github.phillhf.iptv.data

import androidx.compose.runtime.mutableStateListOf
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.Prefs
import io.github.phillhf.iptv.platform.openPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Wiederkehrende Aufnahme auf einem Sender. [TITLE]: jede Sendung mit diesem Titel laut Programmführer;
 * [SLOT]: an den Wochentagen [days] zur Uhrzeit [startMinute] für [minutes] Minuten, auch ohne Programmführer.
 */
data class RecordRule(
    val id: String,
    val account: String,
    val channelKey: String,
    val channelName: String,
    val logo: String?,
    val type: Type,
    val title: String,
    /** Bit 0 = Montag … Bit 6 = Sonntag. */
    val days: Int = 0,
    val startMinute: Int = 0,
    val minutes: Int = 0,
    /** Beginn (ms) der Sendungen, die schon einmal eingeplant waren; die kommen nicht noch einmal, auch wenn sie gelöscht wurden. */
    val planned: Set<Long> = emptySet(),
) {
    enum class Type { TITLE, SLOT }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("account", account).put("channel", channelKey).put("channelName", channelName).put("logo", logo ?: "")
        .put("type", type.name).put("title", title).put("days", days).put("start", startMinute).put("minutes", minutes)
        .put("planned", JSONArray(planned.toList()))

    companion object {
        fun fromJson(o: JSONObject): RecordRule {
            val planned = o.optJSONArray("planned")
            return RecordRule(
                id = o.getString("id"),
                account = o.optString("account"),
                channelKey = o.getString("channel"),
                channelName = o.optString("channelName"),
                logo = o.optString("logo").ifEmpty { null },
                type = runCatching { Type.valueOf(o.optString("type")) }.getOrDefault(Type.TITLE),
                title = o.optString("title"),
                days = o.optInt("days"),
                startMinute = o.optInt("start"),
                minutes = o.optInt("minutes"),
                planned = if (planned == null) emptySet() else (0 until planned.length()).mapTo(HashSet()) { planned.getLong(it) },
            )
        }

        const val ALL_DAYS = 0b1111111

        /** Montag = 0 … Sonntag = 6. */
        fun dayBit(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
            1 shl (Instant.ofEpochMilli(epochMs).atZone(zone).dayOfWeek.value - 1)

        fun minuteOfDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
            Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

        /** Zum Vergleichen von Titeln: ohne Groß/klein, Satzzeichen und angehängte Folgennummern wie „(12)“. */
        fun normalize(title: String): String =
            title.lowercase().replace(Regex("\\s*\\((\\d+|s\\d+\\s*e\\d+|folge\\s*\\d+)\\)\\s*$"), "").filter { it.isLetterOrDigit() }
    }
}

/** Eine Aufnahme, die eine Regel einplanen möchte. Zeiten schon mit Vor- und Nachlauf. */
data class PlannedRecording(val rule: RecordRule, val title: String, val start: Long, val end: Long, val programmeStart: Long)

/** Regeln für wiederkehrende Aufnahmen. Bleiben auf dem Gerät, denn hier wird auch aufgenommen. */
object RecordRules {
    /** Vor- und Nachlauf wie bei einer einzelnen Aufnahme. */
    const val BEFORE = 60_000L
    const val AFTER = 5 * 60_000L

    /** So weit im Voraus werden Uhrzeit-Regeln eingeplant; Titel-Regeln so weit, wie das Programm reicht. */
    const val AHEAD = 8 * 86_400_000L

    val list = mutableStateListOf<RecordRule>()
    private var prefs: Prefs? = null

    @Synchronized
    fun init(context: AppContext) {
        val p = context.openPrefs("record_rules")
        if (p === prefs) return
        prefs = p
        val saved = runCatching {
            val arr = JSONArray(p.getString("list", "[]"))
            (0 until arr.length()).map { RecordRule.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        list.clear()
        list.addAll(saved)
    }

    private fun save() {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs?.edit()?.putString("list", arr.toString())?.apply()
    }

    fun add(rule: RecordRule): RecordRule {
        same(rule)?.let { return it }
        list.add(rule)
        save()
        return rule
    }

    fun remove(rule: RecordRule) {
        list.removeAll { it.id == rule.id }
        save()
    }

    /** Gibt es schon eine Regel, die dasselbe aufnimmt? */
    fun same(rule: RecordRule): RecordRule? = list.firstOrNull {
        it.account == rule.account && it.channelKey == rule.channelKey && it.type == rule.type &&
            if (rule.type == RecordRule.Type.TITLE) RecordRule.normalize(it.title) == RecordRule.normalize(rule.title)
            else it.startMinute == rule.startMinute && it.days == rule.days
    }

    /** Regel, die diese Sendung erfasst, für den Dialog im Programm. */
    fun matching(account: String, channelKey: String, e: EpgEntry, zone: ZoneId = ZoneId.systemDefault()): RecordRule? = list.firstOrNull { r ->
        r.account == account && r.channelKey == channelKey && when (r.type) {
            RecordRule.Type.TITLE -> RecordRule.normalize(r.title) == RecordRule.normalize(e.title)
            RecordRule.Type.SLOT -> (r.days and RecordRule.dayBit(e.start, zone)) != 0 && r.startMinute == RecordRule.minuteOfDay(e.start, zone)
        }
    }

    fun newTitleRule(account: String, s: Stream, e: EpgEntry) = RecordRule(
        UUID.randomUUID().toString(), account, s.key, s.name, s.logo, RecordRule.Type.TITLE, e.title,
    )

    /** [days]: Bitmaske, für „jede Woche“ nur der Tag der Sendung. */
    fun newSlotRule(account: String, s: Stream, e: EpgEntry, days: Int, zone: ZoneId = ZoneId.systemDefault()) = RecordRule(
        UUID.randomUUID().toString(), account, s.key, s.name, s.logo, RecordRule.Type.SLOT, e.title,
        days = days,
        startMinute = RecordRule.minuteOfDay(e.start, zone),
        minutes = ((e.end - e.start) / 60_000L).toInt().coerceIn(1, 24 * 60),
    )

    /**
     * Was [rule] ab [now] aufnehmen möchte. [programmes] liefert das Programm des Senders im Zeitraum
     * (für Titel-Regeln und um bei Uhrzeit-Regeln den Titel zu finden).
     */
    fun plan(rule: RecordRule, now: Long, programmes: (from: Long, to: Long) -> List<EpgEntry>, zone: ZoneId = ZoneId.systemDefault()): List<PlannedRecording> {
        val out = ArrayList<PlannedRecording>()
        when (rule.type) {
            RecordRule.Type.TITLE -> {
                val want = RecordRule.normalize(rule.title)
                if (want.isEmpty()) return out
                programmes(now, now + AHEAD).filter { it.end > now && RecordRule.normalize(it.title) == want }.forEach { e ->
                    out += PlannedRecording(rule, e.title, e.start - BEFORE, e.end + AFTER, e.start)
                }
            }
            RecordRule.Type.SLOT -> {
                if ((rule.days and RecordRule.ALL_DAYS) == 0 || rule.minutes <= 0) return out
                val guide = programmes(now - 86_400_000L, now + AHEAD)
                var day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)
                val last = Instant.ofEpochMilli(now + AHEAD).atZone(zone).toLocalDate()
                while (!day.isAfter(last)) {
                    if ((rule.days and (1 shl (day.dayOfWeek.value - 1))) != 0) {
                        val start = startOf(day, rule.startMinute, zone)
                        val end = start + rule.minutes * 60_000L
                        if (end + AFTER > now && start <= now + AHEAD) {
                            // Titel aus dem Programm, wenn dort um diese Zeit etwas anfängt
                            val title = guide.firstOrNull { kotlin.math.abs(it.start - start) <= 5 * 60_000L }?.title ?: rule.title
                            out += PlannedRecording(rule, title, start - BEFORE, end + AFTER, start)
                        }
                    }
                    day = day.plusDays(1)
                }
            }
        }
        return out.filter { it.programmeStart !in rule.planned }
    }

    private fun startOf(day: LocalDate, minute: Int, zone: ZoneId): Long =
        day.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()

    /** Merkt sich, was eingeplant wurde, und vergisst dabei Altes. */
    fun markPlanned(rule: RecordRule, starts: Collection<Long>, now: Long) {
        val i = list.indexOfFirst { it.id == rule.id }
        if (i < 0) return
        val current = list[i]
        val kept = current.planned.filterTo(HashSet()) { it > now - 2 * 86_400_000L }
        kept += starts
        if (kept == current.planned) return
        list[i] = current.copy(planned = kept)
        save()
    }
}
