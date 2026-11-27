package io.github.phillhf.iptv.data

import java.io.FilterInputStream
import java.io.InputStream

/** Liest die Programmführer eines Anbieters ein; gemeinsam für App und Server. */
object EpgImport {
    /** [failed]: so viele Quellen waren nicht erreichbar oder kaputt. */
    data class Result(val count: Int, val failed: Int, val sources: Int)

    /** Eigene Adressen zuerst, dann die des Anbieters, wenn gewünscht. [provider] kann Netzwerk brauchen. */
    fun urls(a: Account, provider: () -> String?): List<String> {
        val own = a.epgUrls.map { it.trim() }.filter { it.isNotEmpty() }
        val fromProvider = if (a.epgProvider) provider()?.trim()?.ifEmpty { null } else null
        return (own + listOfNotNull(fromProvider)).distinct()
    }

    /** Zum Vergleichen: Programmführer-Schlüssel eines Senders, mit dem Namen des Anbieters. */
    fun key(s: Stream, originalName: String = s.name): String =
        s.epgId?.trim()?.lowercase()?.ifEmpty { null } ?: ("n:" + Xmltv.normalizeName(originalName))

    fun run(
        db: EpgDb,
        account: Account,
        urls: List<String>,
        live: List<Stream>,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
        now: Long = System.currentTimeMillis(),
    ): Result {
        val ids = live.mapNotNullTo(HashSet()) { it.epgId?.trim()?.lowercase()?.ifEmpty { null } }
        val names = live.filter { it.epgId.isNullOrBlank() }.mapTo(HashSet()) { Xmltv.normalizeName(it.name) }
        names.remove("")
        val back = (live.maxOfOrNull { it.archiveDays } ?: 0).coerceIn(1, 7)
        // Bei verschobenen Zeiten etwas mehr behalten, damit nach dem Verschieben nichts fehlt
        val margin = kotlin.math.abs(account.epgShift) * 60_000L
        var failed = 0
        val count = db.import(
            account.id,
            urls.map { url -> { open(url) } },
            from = now - back * 86_400_000L - margin,
            to = now + 4 * 86_400_000L + margin,
            keyFor = { id, shown ->
                buildList {
                    val key = id.trim().lowercase()
                    if (key in ids) add(key)
                    shown.map { Xmltv.normalizeName(it) }.filter { it in names }.distinct().forEach { add("n:$it") }
                }
            },
            onProgress = onProgress,
            isCancelled = isCancelled,
            onSourceFailed = { _, _ -> failed++ },
        )
        return Result(count, failed, urls.size)
    }

    /** Schließen trennt auch die Verbindung. */
    private fun open(url: String): InputStream {
        val conn = Http.open(url)
        return object : FilterInputStream(conn.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    conn.disconnect()
                }
            }
        }
    }
}

/** Sendezeiten um [ms] verschieben, wenn der Anbieter sie falsch liefert. */
fun List<EpgEntry>.shifted(ms: Long): List<EpgEntry> = if (ms == 0L) this else map { it.copy(start = it.start + ms, end = it.end + ms) }
