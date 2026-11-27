package io.github.phillhf.iptv

import io.github.phillhf.iptv.player.MemoryStats
import io.github.phillhf.iptv.player.PlaybackHealth
import io.github.phillhf.iptv.player.PlaybackHealth.Reason
import io.github.phillhf.iptv.player.PlaybackStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selbstheilung: wann ein Live-Stream neu aufgebaut wird und wann nicht. */
class HealthTest {
    private var now = 1_000_000L
    private val health = PlaybackHealth { now }
    private var position = 0L
    private var dropped = 0L
    private var shown = 0L

    /** [seconds] Sekunden Wiedergabe; [rate]: Position je Sekunde, [drops]: verlorene Bilder je Sekunde. */
    private fun play(seconds: Int, rate: Long = 1000, drops: Long = 0, buffering: Boolean = false, playing: Boolean = true, enabled: Boolean = true): Reason? {
        var last: Reason? = null
        repeat(seconds) {
            now += 1000
            if (playing && !buffering) {
                position += rate
                dropped += drops
                shown += 50 - drops
            }
            last = health.sample(position, playing && !buffering, buffering, 1f, PlaybackStats(dropped, shown), MemoryStats(), enabled)
        }
        return last
    }

    @Test
    fun calmStreamIsLeftAlone() {
        health.start(live = true, fresh = true)
        assertNull(play(2 * 3600))
        // Ein einzelner kurzer Aussetzer reicht nicht
        assertNull(play(1, drops = 30))
        assertNull(play(30))
    }

    @Test
    fun timerAfterThreeHours() {
        health.start(live = true, fresh = true)
        assertNull(play(3 * 3600 - 10))
        assertEquals(Reason.TIMER, play(20))
        health.healed(Reason.TIMER)
        assertNull(play(60))
        // Pausiert: nichts neu aufbauen, auch nicht nach Stunden
        health.start(live = true, fresh = true)
        assertNull(play(4 * 3600, playing = false))
        // Filme nie
        health.start(live = false, fresh = true)
        assertNull(play(4 * 3600, drops = 10))
    }

    @Test
    fun droppedFramesAfterWarmup() {
        health.start(live = true, fresh = true)
        // In der ersten Minute ruckelt es beim Start oft; das zählt nicht
        assertNull(play(50, drops = 5))
        assertNull(play(600))
        assertEquals(Reason.FRAMES, play(60, drops = 5))
        assertNull(play(60, drops = 5, enabled = false))
    }

    @Test
    fun driftWhenPlaybackFallsBehind() {
        health.start(live = true, fresh = true)
        assertNull(play(120))
        // Spielt 2 % zu langsam: nach gut 1000 Sekunden 20 Sekunden hinterher
        assertNull(play(900, rate = 980))
        assertEquals(Reason.DRIFT, play(200, rate = 980))
        assertTrue(health.driftMs >= PlaybackHealth.MAX_DRIFT_MS)
    }

    @Test
    fun repeatedStallsAndGivingUp() {
        health.start(live = true, fresh = true)
        assertNull(play(120))
        var reason: Reason? = null
        repeat(3) {
            play(3, buffering = true)
            reason = play(20)
        }
        assertEquals(Reason.STALLS, reason)
        // Dreimal neu aufgebaut, und es ruckelt trotzdem: dann nicht ewig weiter
        repeat(3) {
            health.healed(Reason.FRAMES)
            play(70, drops = 5)
        }
        assertTrue(health.gaveUp)
        assertNull(play(120, drops = 5))
        // Ein anderer Sender fängt wieder von vorn an
        health.start(live = true, fresh = true)
        assertTrue(!health.gaveUp)
    }
}
