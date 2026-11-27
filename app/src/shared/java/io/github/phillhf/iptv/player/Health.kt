package io.github.phillhf.iptv.player

import io.github.phillhf.iptv.t
import java.util.Locale

/** Momentaufnahme der Wiedergabe. -1 heißt: weiß dieser Player nicht. */
data class PlaybackStats(
    /** Verlorene Bilder seit dem Start des Streams. */
    val dropped: Long = -1,
    val shown: Long = -1,
    val bufferedMs: Long = -1,
    /** Abstand zum Live-Bild, wie ihn der Player selbst kennt (nur HLS und DASH). */
    val liveOffsetMs: Long = -1,
    val bitrateKbps: Int = -1,
)

/** Speicher der App in MB. -1 heißt unbekannt. */
data class MemoryStats(val heapMb: Int = -1, val heapMaxMb: Int = -1, val nativeMb: Int = -1, val processMb: Int = -1)

/**
 * Beobachtet einen laufenden Live-Stream und meldet, wann er neu aufgebaut werden sollte:
 * wenn sich verlorene Bilder oder Hänger häufen, Bild und Uhr auseinanderlaufen, der Speicher knapp wird
 * oder spätestens nach [MAX_RUN_MS]. Das entspricht dem, was beim Aus- und Einschalten des Fernsehers passiert.
 */
class PlaybackHealth(private val clock: () -> Long = System::currentTimeMillis) {
    enum class Reason { FRAMES, STALLS, DRIFT, MEMORY, TIMER }

    private class FrameSample(val time: Long, val dropped: Long, val shown: Long)

    private var live = false
    private var loadedAt = 0L
    private var firstPlay = 0L
    private val frames = ArrayDeque<FrameSample>()
    private val stalls = ArrayDeque<Long>()
    private var buffering = false
    private var played = false

    private var baseTime = -1L
    private var basePosition = 0L
    private var baseOffset = -1L
    private var lastPosition = -1L
    private var lastTime = 0L
    private var lastPlaying = false

    /** Abweichung vom Live-Bild seit dem Start, in ms; positiv = Bild hängt hinterher. */
    var driftMs = 0L
        private set

    private val heals = ArrayDeque<Pair<Long, Reason>>()
    val healCount: Int get() = heals.size
    val lastHeal: Pair<Long, Reason>? get() = heals.lastOrNull()

    /** Neu aufbauen wegen schlechter Wiedergabe hat dreimal in kurzer Zeit nicht geholfen. */
    var gaveUp = false
        private set

    private var lastStats = PlaybackStats()
    private var lastMemory = MemoryStats()

    /**
     * Ein Stream beginnt. [fresh]: anderer Sender oder Film, dann fängt auch die Zählung der
     * Neuaufbauten von vorn an; bei einem neuen Versuch oder Neuaufbau desselben Streams nicht.
     */
    fun start(live: Boolean, fresh: Boolean) {
        this.live = live
        loadedAt = clock()
        firstPlay = 0L
        frames.clear()
        stalls.clear()
        buffering = false
        played = false
        resetDrift()
        driftMs = 0
        if (fresh) {
            heals.clear()
            gaveUp = false
        }
    }

    /** Der Stream wurde gerade aus [reason] neu aufgebaut. */
    fun healed(reason: Reason) {
        val now = clock()
        heals.addLast(now to reason)
        while (heals.size > 50) heals.removeFirst()
        if (reason != Reason.TIMER) {
            val recent = heals.count { it.second != Reason.TIMER && now - it.first < QUALITY_WINDOW_MS }
            if (recent >= MAX_QUALITY_HEALS) gaveUp = true
        }
        start(live, fresh = false)
    }

    private fun resetDrift() {
        baseTime = -1L
        baseOffset = -1L
        lastPosition = -1L
    }

    /**
     * Etwa jede Sekunde aufrufen. Gibt einen Grund zurück, wenn der Stream jetzt neu aufgebaut werden sollte;
     * solange der Grund besteht, kommt er bei jedem Aufruf wieder.
     */
    fun sample(
        positionMs: Long,
        playing: Boolean,
        buffering: Boolean,
        speed: Float,
        stats: PlaybackStats,
        memory: MemoryStats,
        enabled: Boolean,
    ): Reason? {
        val now = clock()
        lastStats = stats
        lastMemory = memory
        if (playing && !played) {
            played = true
            firstPlay = now
        }

        // Hänger: Wechsel in den Pufferzustand, nachdem schon einmal Bild kam
        if (buffering && !this.buffering && played) stalls.addLast(now)
        this.buffering = buffering
        while (stalls.isNotEmpty() && now - stalls.first() > STALL_WINDOW_MS) stalls.removeFirst()

        // Verlorene Bilder der letzten Minute
        if (stats.dropped >= 0) {
            val last = frames.lastOrNull()
            if (last != null && stats.dropped < last.dropped) frames.clear()
            frames.addLast(FrameSample(now, stats.dropped, stats.shown))
            while (frames.size > 2 && now - frames.first().time > FRAME_WINDOW_MS) frames.removeFirst()
        }

        // Abweichung zum Live-Bild: Uhrzeit gegen Position, solange nicht pausiert
        val active = playing || buffering
        if (!active || speed != 1f) {
            resetDrift()
        } else if (stats.liveOffsetMs >= 0) {
            if (baseOffset < 0 && playing) baseOffset = stats.liveOffsetMs
            if (baseOffset >= 0) driftMs = stats.liveOffsetMs - baseOffset
        } else if (playing || baseTime >= 0) {
            val expected = when {
                lastPosition < 0 -> positionMs
                lastPlaying -> lastPosition + (now - lastTime)
                else -> lastPosition
            }
            // Sprung in der Zeitachse des Streams (neuer Abschnitt, Spulen): von dort neu messen
            if (baseTime < 0 || kotlin.math.abs(positionMs - expected) > JUMP_MS) {
                baseTime = now
                basePosition = positionMs
            }
            lastPosition = positionMs
            lastTime = now
            lastPlaying = playing
            driftMs = (now - baseTime) - (positionMs - basePosition)
        }

        if (!enabled || !live || !playing) return null
        if (now - loadedAt >= MAX_RUN_MS) return Reason.TIMER
        if (gaveUp || firstPlay == 0L || now - firstPlay < WARMUP_MS) return null
        if (droppedLastMinute() >= MAX_DROPPED && droppedShare() >= MAX_DROPPED_SHARE) return Reason.FRAMES
        if (stalls.size >= MAX_STALLS) return Reason.STALLS
        if (kotlin.math.abs(driftMs) >= MAX_DRIFT_MS) return Reason.DRIFT
        if (memory.heapMaxMb > 0 && memory.heapMb >= memory.heapMaxMb * MAX_HEAP_SHARE) return Reason.MEMORY
        return null
    }

    private fun droppedLastMinute(): Long {
        if (frames.size < 2) return 0
        return frames.last().dropped - frames.first().dropped
    }

    private fun droppedShare(): Float {
        if (frames.size < 2) return 0f
        val dropped = droppedLastMinute()
        val first = frames.first()
        val last = frames.last()
        if (first.shown < 0 || last.shown < 0) return 1f
        val total = dropped + (last.shown - first.shown)
        return if (total <= 0) 0f else dropped.toFloat() / total
    }

    /** Zeilen für die Diagnose-Einblendung. */
    fun lines(engine: String, enabled: Boolean): List<String> {
        val now = clock()
        val s = lastStats
        val m = lastMemory
        val out = ArrayList<String>()
        out += t("Player: %1", engine)
        out += t("Läuft seit: %1", duration(now - loadedAt))
        out += t("Verlorene Bilder: %1 (letzte Minute: %2)", known(s.dropped), if (frames.size < 2) "–" else droppedLastMinute().toString())
        out += t("Puffer: %1", if (s.bufferedMs < 0) "–" else seconds(s.bufferedMs))
        if (live) {
            out += t("Abweichung zum Live-Bild: %1", (if (driftMs > 0) "+" else "") + seconds(driftMs))
            out += t("Hänger (5 Min.): %1", stalls.size)
        }
        if (s.bitrateKbps > 0) out += t("Datenrate: %1 Mbit/s", String.format(Locale.ROOT, "%.1f", s.bitrateKbps / 1000f))
        val memory = buildList {
            if (m.heapMb >= 0) add(if (m.heapMaxMb > 0) t("%1 von %2 MB", m.heapMb, m.heapMaxMb) else "${m.heapMb} MB")
            if (m.nativeMb >= 0) add(t("nativ %1 MB", m.nativeMb))
            if (m.processMb >= 0) add(t("gesamt %1 MB", m.processMb))
        }
        if (memory.isNotEmpty()) out += t("Speicher: %1", memory.joinToString(", "))
        if (live) {
            out += when {
                !enabled -> t("Selbstheilung: aus")
                gaveUp -> t("Selbstheilung: pausiert, hat nicht geholfen")
                else -> t("Selbstheilung: an")
            }
            lastHeal?.let { (time, reason) ->
                out += t("Neu aufgebaut: %1× (zuletzt vor %2: %3)", heals.size, duration(now - time), reasonText(reason))
            }
        }
        return out
    }

    private fun known(v: Long) = if (v < 0) "–" else v.toString()

    companion object {
        const val MAX_RUN_MS = 3 * 3_600_000L
        const val WARMUP_MS = 60_000L
        const val FRAME_WINDOW_MS = 60_000L
        const val MAX_DROPPED = 60L
        const val MAX_DROPPED_SHARE = 0.04f
        const val STALL_WINDOW_MS = 5 * 60_000L
        const val MAX_STALLS = 3
        const val MAX_DRIFT_MS = 20_000L
        const val MAX_HEAP_SHARE = 0.9f
        const val JUMP_MS = 15_000L
        const val QUALITY_WINDOW_MS = 30 * 60_000L
        const val MAX_QUALITY_HEALS = 3

        fun reasonText(r: Reason): String = when (r) {
            Reason.FRAMES -> t("Bildaussetzer")
            Reason.STALLS -> t("Hänger")
            Reason.DRIFT -> t("Zeitversatz")
            Reason.MEMORY -> t("Speicher knapp")
            Reason.TIMER -> t("nach 3 Stunden")
        }

        private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000f)

        private fun duration(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        }
    }
}
