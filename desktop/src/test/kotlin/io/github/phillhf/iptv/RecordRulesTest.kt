package io.github.phillhf.iptv

import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.EpgImport
import io.github.phillhf.iptv.data.RecordRule
import io.github.phillhf.iptv.data.RecordRules
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.data.shifted
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Serienaufnahmen, verschobene Sendezeiten und mehrere Programmführer im Konto. */
class RecordRulesTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun rule(type: RecordRule.Type, title: String = "Tatort", days: Int = 0, start: Int = 0, minutes: Int = 0, planned: Set<Long> = emptySet()) =
        RecordRule("r", "acc", "LIVE:1", "Das Erste", null, type, title, days, start, minutes, planned)

    // 23.11.2026 ist ein Montag
    private val guide = listOf(
        EpgEntry("Tatort", "", at(23, 20, 15), at(23, 21, 45)),
        EpgEntry("Tagesthemen", "", at(23, 21, 45), at(23, 22, 15)),
        EpgEntry("Tatort (12)", "", at(25, 20, 15), at(25, 21, 45)),
        EpgEntry("tatort", "", at(22, 20, 15), at(22, 21, 45)),
    )

    @Test
    fun titleRuleFindsEveryEpisode() {
        val now = at(23, 12)
        val plan = RecordRules.plan(rule(RecordRule.Type.TITLE), now, { from, to -> guide.filter { it.end > from && it.start < to } }, zone)
        // Gestern ist vorbei; Montag und Mittwoch, auch mit „(12)“ hinten dran
        assertEquals(listOf(at(23, 20, 15), at(25, 20, 15)), plan.map { it.programmeStart })
        assertEquals(at(23, 20, 15) - RecordRules.BEFORE, plan.first().start)
        assertEquals(at(23, 21, 45) + RecordRules.AFTER, plan.first().end)
        // Schon einmal eingeplant: nicht noch einmal, auch wenn der Nutzer sie gelöscht hat
        val again = RecordRules.plan(rule(RecordRule.Type.TITLE, planned = setOf(at(23, 20, 15))), now, { _, _ -> guide }, zone)
        assertEquals(listOf(at(25, 20, 15)), again.map { it.programmeStart })
    }

    @Test
    fun slotRuleWorksWithoutGuide() {
        val now = at(23, 12)
        val monday = RecordRule.dayBit(at(23, 12), zone)
        val weekly = RecordRules.plan(rule(RecordRule.Type.SLOT, "Sendung", monday, 20 * 60 + 15, 90), now, { _, _ -> emptyList() }, zone)
        // Heute und nächste Woche Montag, innerhalb von acht Tagen
        assertEquals(listOf(at(23, 20, 15), at(30, 20, 15)), weekly.map { it.programmeStart })
        assertEquals("Sendung", weekly.first().title)
        assertEquals(at(23, 21, 45) + RecordRules.AFTER, weekly.first().end)
        // Titel aus dem Programm, wenn dort um diese Zeit etwas anfängt
        val named = RecordRules.plan(rule(RecordRule.Type.SLOT, "Sendung", monday, 20 * 60 + 15, 90), now, { _, _ -> guide }, zone)
        assertEquals("Tatort", named.first().title)
        val daily = RecordRules.plan(rule(RecordRule.Type.SLOT, "Sendung", RecordRule.ALL_DAYS, 20 * 60 + 15, 90), now, { _, _ -> emptyList() }, zone)
        assertEquals(8, daily.size)
        assertTrue(daily.zipWithNext().all { (a, b) -> b.programmeStart > a.programmeStart })
    }

    @Test
    fun slotRuleKeepsLocalTimeOverDaylightSaving() {
        // In der Nacht zum 25.10.2026 endet die Sommerzeit
        val now = LocalDateTime.of(2026, 10, 23, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val plan = RecordRules.plan(rule(RecordRule.Type.SLOT, "Sendung", RecordRule.ALL_DAYS, 20 * 60, 60), now, { _, _ -> emptyList() }, zone)
        assertTrue(plan.all { RecordRule.minuteOfDay(it.programmeStart, zone) == 20 * 60 })
    }

    @Test
    fun matchingAndSameRules() {
        RecordRules.list.clear()
        val title = rule(RecordRule.Type.TITLE)
        RecordRules.list.add(title)
        assertEquals(title, RecordRules.matching("acc", "LIVE:1", guide[2], zone))
        assertEquals(null, RecordRules.matching("acc", "LIVE:2", guide[2], zone))
        assertEquals(title, RecordRules.same(title.copy(id = "x", title = "TATORT")))
        RecordRules.list.clear()
    }

    @Test
    fun shiftMovesAllTimes() {
        val shifted = guide.shifted(-3_600_000L)
        assertEquals(at(23, 19, 15), shifted.first().start)
        assertEquals(guide, guide.shifted(0))
    }

    @Test
    fun accountKeepsOldEpgSetting() {
        // Bis 0.9: eine eigene Adresse ersetzte die des Anbieters
        val old = Account.fromJson(JSONObject().put("id", "a").put("type", "XTREAM").put("epgUrl", "http://x/guide.xml"))
        assertEquals(listOf("http://x/guide.xml"), old.epgUrls)
        assertFalse(old.epgProvider)
        val none = Account.fromJson(JSONObject().put("id", "a").put("type", "XTREAM").put("epgUrl", ""))
        assertTrue(none.epgProvider)
        val a = Account("a", "A", SourceType.XTREAM, epgUrls = listOf("http://1", "http://2"), epgProvider = true, epgShift = 90)
        val back = Account.fromJson(a.toJson())
        assertEquals(a, back)
        assertEquals("http://1", a.toJson().getString("epgUrl"))
        assertEquals(listOf("http://1", "http://2", "http://p"), EpgImport.urls(a) { "http://p" })
        assertEquals(listOf("http://1", "http://2"), EpgImport.urls(a.copy(epgProvider = false)) { "http://p" })
        assertEquals(listOf("http://a", "http://b"), Account.parseUrls(" http://a\n\nhttp://b  http://a "))
    }
}
