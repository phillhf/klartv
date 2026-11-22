package io.github.phillhf.iptv.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Baut Adressen, unter denen Anbieter vergangene Sendungen bereitstellen (Catch-up). */
object Catchup {
    private val xtreamTime = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

    fun minutes(startMs: Long, endMs: Long): Long = ((endMs - startMs + 59_999) / 60_000).coerceAtLeast(1)

    /** Xtream erwartet die Startzeit in der Zeitzone des Servers. */
    fun xtreamPath(startMs: Long, endMs: Long, zone: ZoneId): String =
        "${minutes(startMs, endMs)}/${xtreamTime.format(Instant.ofEpochMilli(startMs).atZone(zone))}"

    fun available(s: Stream, startMs: Long, nowMs: Long): Boolean =
        s.kind == Kind.LIVE && s.archiveDays > 0 && startMs < nowMs && startMs > nowMs - s.archiveDays * 86_400_000L

    /** M3U: Vorgaben aus catchup/catchup-source der Playlist. Null, wenn der Sender nichts davon kann. */
    fun m3uUrl(s: Stream, startMs: Long, endMs: Long, nowMs: Long): String? {
        val type = s.catchupType?.lowercase() ?: return null
        val source = s.catchupSource.orEmpty()
        val start = startMs / 1000
        val end = endMs / 1000
        val now = nowMs / 1000
        return when (type) {
            "default" -> if (source.isBlank()) null else fill(if (source.contains("://")) source else s.url + source, start, end, now)
            "append" -> if (source.isBlank()) null else fill(s.url + source, start, end, now)
            "shift", "timeshift" -> s.url + (if (s.url.contains('?')) "&" else "?") + "utc=$start&lutc=$now"
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(s.url, start, end)
            "xc" -> xtreamFromUrl(s.url, startMs, endMs)
            else -> null
        }
    }

    private fun flussonic(url: String, start: Long, end: Long): String? {
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val file = base.substringAfterLast('/')
        val dir = base.substringBeforeLast('/')
        return when {
            file == "mpegts" -> "$dir/timeshift_abs-$start.ts$query"
            file.endsWith(".m3u8") -> "$dir/${file.removeSuffix(".m3u8")}-$start-${end - start}.m3u8$query"
            else -> null
        }
    }

    private fun xtreamFromUrl(url: String, startMs: Long, endMs: Long): String? {
        val m = Regex("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/([^/.?]+)(?:\\.[a-z0-9]+)?").find(url) ?: return null
        val (host, user, pass, id) = m.destructured
        return "$host/timeshift/$user/$pass/${xtreamPath(startMs, endMs, ZoneOffset.UTC)}/$id.ts"
    }

    private val token = Regex("\\$?\\{([a-zA-Z]+)(?::([^}]*))?\\}")

    /** Ersetzt Platzhalter wie {utc}, ${'$'}{start}, {duration:60} oder {Y}-{m}-{d}. */
    fun fill(template: String, start: Long, end: Long, now: Long): String {
        val t = Instant.ofEpochSecond(start).atZone(ZoneOffset.UTC)
        return token.replace(template) { m ->
            val name = m.groupValues[1]
            val arg = m.groupValues[2]
            when (name) {
                "utc", "start" -> stamp(start, arg)
                "utcend", "end" -> stamp(end, arg)
                "lutc", "now", "timestamp" -> stamp(now, arg)
                "duration" -> divided(end - start, arg)
                "offset" -> divided(now - start, arg)
                "Y", "m", "d", "H", "M", "S" -> letter(t, name[0])
                else -> m.value
            }
        }
    }

    private fun divided(value: Long, arg: String): String = (value / (arg.toLongOrNull()?.takeIf { it > 0 } ?: 1)).toString()

    private fun stamp(seconds: Long, format: String): String {
        if (format.isEmpty()) return seconds.toString()
        val t = Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC)
        return format.map { c -> if (c in "YmdHMS") letter(t, c) else c.toString() }.joinToString("")
    }

    private fun letter(t: ZonedDateTime, c: Char): String = when (c) {
        'Y' -> t.year.toString()
        'm' -> "%02d".format(t.monthValue)
        'd' -> "%02d".format(t.dayOfMonth)
        'H' -> "%02d".format(t.hour)
        'M' -> "%02d".format(t.minute)
        else -> "%02d".format(t.second)
    }
}
