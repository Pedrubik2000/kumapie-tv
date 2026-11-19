package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Stats from Progress, one implementation for the phone and the TV without a PC ([Progress.statsOf]). */
class StatsTest {
    private val today = LocalDate.parse("2026-11-19")

    private fun view(watch: Map<String, Double>, lookups: Map<String, Int> = emptyMap()) =
        Progress.View(emptyMap(), watch, lookups, setOf("猫"), mapOf("犬" to "dog"))

    @Test fun totalsAndStreak() {
        val s = Progress.statsOf(view(mapOf("2026-11-19" to 120.0, "2026-11-18" to 600.0, "2026-11-17" to 30.0,
            "2026-11-12" to 50.0, "2026-11-01" to 1000.0)), emptyList(), today)
        assertEquals(120.0, s.today, 0.0)
        assertEquals(750.0, s.week, 0.0) // 11-13..11-19: 120 + 600 + 30 (11-12 is 7 days back: outside)
        assertEquals(1800.0, s.total, 0.0)
        assertEquals(2, s.streak) // 11-17 had under a minute
        assertEquals(1, s.markedKnown)
    }

    @Test fun streakCountsToYesterdayBeforeTodayStarts() =
        assertEquals(2, Progress.statsOf(view(mapOf("2026-11-18" to 60.0, "2026-11-17" to 90.0)), emptyList(), today).streak)

    @Test fun topLookupsMostFirstWithMeaning() {
        val s = Progress.statsOf(view(emptyMap(), mapOf("犬" to 3, "猫" to 5, "鳥" to 3)), emptyList(), today)
        assertEquals(listOf("猫", "犬", "鳥"), s.topLookups.map { it.word })
        assertEquals("dog", s.topLookups[1].meaning)
        assertEquals(11, s.lookups)
    }
}
