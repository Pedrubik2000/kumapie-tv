package io.github.pedrubik2000.kumapie.mobile.offline

import io.github.pedrubik2000.kumapie.data.Cue
import io.github.pedrubik2000.kumapie.data.Ranges
import io.github.pedrubik2000.kumapie.data.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the PC's `ranges.py` self-check. */
class RangesTest {
    private fun s(vararg ms: Long) = ms.toList().chunked(2).map { Span(it[0], it[1]) }
    private val need = Ranges.spoken(listOf(90.0 to 95.0, 100.0 to 130.0, 135.0 to 600.0, 700.0 to 720.0).map { Cue(it.first, it.second, emptyList()) })

    @Test fun unionJoinsAndSorts() = assertEquals(s(0, 9000, 20000, 30000), Ranges.union(s(5000, 9000, 0, 4500, 20000, 30000, 3, 3)))
    @Test fun missingTime() = assertEquals(7000L, Ranges.missing(s(0, 10000), s(2000, 4000, 9000, 20000)))
    @Test fun nothingWatched() = assertEquals(10000L, Ranges.missing(s(0, 10000), emptyList()))
    @Test fun spokenIsTheLines() = assertEquals(s(90000, 95000, 100000, 130000, 135000, 600000, 700000, 720000), need)
    @Test fun skippingSilenceAndTheOpeningIsWatched() = assertTrue(Ranges.watched(need, s(90000, 96000, 99000, 131000, 134000, 600000, 700000, 720000)))
    @Test fun aFewSecondsMissingIsWatched() = assertTrue(Ranges.watched(need, s(95000, 600000, 700000, 720000)))
    @Test fun theLastLineNeverPlayed() = assertFalse(Ranges.watched(need, s(90000, 600000)))
    @Test fun noLinesIsNeverWatched() = assertFalse(Ranges.watched(emptyList(), s(0, 1000)))

    /** "Mark as not watched" is a time: a device's ranges started before it stop counting. */
    @Test fun rangesFromBeforeAMarkDontCount() {
        val parts = listOf(mapOf("ep" to (100L to s(0, 5000))), mapOf("ep" to (300L to s(9000, 12000))))
        assertEquals(mapOf("ep" to s(9000, 12000)), Progress.stillWatched(parts, mapOf("ep" to 200L)))
        assertEquals(mapOf("ep" to s(0, 5000, 9000, 12000)), Progress.stillWatched(parts, emptyMap()))
    }
}
