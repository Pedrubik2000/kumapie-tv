package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.ContinueHidden
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Show
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ContinueSoonTest {
    private val now = 1_000_000L
    private fun ep(id: String, n: Int?, at: Long) = Episode(id, "Episode $n", 1, n, 1400.0, "", 0, 0, 0, null, resumedAt = at)
    private fun card(k: String, n: Int, at: Long = 10): Pair<Show, Episode> = ep("kitsu:$k:$n", n, at).let { Show("kitsu:$k", k, "stream", "", listOf(it), "ja") to it }
    private fun mine(k: String, nextEp: Int, nextAt: Long) =
        Mine(k.toInt(), k, k, k, "", "", "TV", null, "CURRENT", 0, 0.0, 0, emptyList(), 2024, nextEp = nextEp, nextAt = nextAt)

    @Test fun aHiddenShowStaysOutUntilItsEpisodeIsPlayedAgain() {
        val hidden = mapOf("kitsu:1" to 10L)
        assertEquals(listOf("kitsu:2"), ContinueHidden.shown(listOf(card("1", 3, at = 10), card("2", 3)), hidden).map { it.first.id })
        assertEquals(listOf("kitsu:1", "kitsu:2"), ContinueHidden.shown(listOf(card("1", 4, at = 11), card("2", 3)), hidden).map { it.first.id })
    }

    @Test fun noHiddenSetLeavesTheRowAsIs() {
        val row = listOf(card("1", 3), card("2", 3))
        assertEquals(row, ContinueHidden.shown(row, emptyMap()))
    }

    @Test fun anEpisodeNotAiredYetMovesAfterThePlayableOnesKeepingOrder() {
        val all = listOf(mine("1", 4, now + 3600), mine("3", 9, now - 5))
        val row = listOf(card("1", 4), card("2", 8), card("3", 9), card("4", 1))
        assertEquals(listOf("kitsu:2", "kitsu:3", "kitsu:4", "kitsu:1"), ContinueHidden.soonLast(row, all.associateBy { it.kitsuId }, now).map { it.first.id })
    }

    @Test fun anEpisodeBeforeTheNextToAirIsPlayable() {
        assertEquals(null, ContinueHidden.airsAt(mine("1", 4, now + 60), card("1", 3).second, now))
        assertEquals(now + 60, ContinueHidden.airsAt(mine("1", 4, now + 60), card("1", 4).second, now))
    }

    @Test fun noAiringInfoKeepsToday() {
        assertEquals(null, ContinueHidden.airsAt(mine("1", 0, 0), card("1", 4).second, now))
        assertEquals(null, ContinueHidden.airsAt(null, card("1", 4).second, now))
    }

    @Test fun theAiringTimeSaysTheDayWithinAWeekAndTheDateAfter() {
        val zone = ZoneId.of("UTC")
        val thu = ZonedDateTime.of(2026, 11, 26, 18, 0, 0, 0, zone).toEpochSecond()
        val mon = thu - 3 * 86400
        assertEquals("Thu 18:00", ContinueHidden.airsText(thu, mon, zone, Locale.ENGLISH))
        assertEquals("26 Nov 18:00", ContinueHidden.airsText(thu, mon - 10 * 86400, zone, Locale.ENGLISH))
    }
}
