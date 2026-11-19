package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.hoursMinutes
import io.github.pedrubik2000.kumapie.data.libraryRows
import io.github.pedrubik2000.kumapie.data.resumeAt
import org.junit.Assert.assertEquals
import org.junit.Test

/** Small helpers the TV and the phone/tablet now share (kumapie_plan.md 4e). */
class SharedHelpersTest {
    private fun ep(easy: Int, watched: Boolean = false) = Episode("e", "e", 1, 1, 1380.0, "", 10, 0, easy, null, watchedFlag = watched)
    private fun show(id: String, kind: String, vararg eps: Episode) = Show(id, id, kind, "", eps.toList())

    @Test fun libraryRowsEasiestFirstFinishedLastReelsOut() {
        val rows = libraryRows(listOf(show("hard", "anime", ep(1)), show("done", "anime", ep(9, watched = true)),
            show("easy", "anime", ep(8)), show("short", "reels", ep(5)), show("odd", "music", ep(5))))
        assertEquals(listOf("Anime", "Shows", "Movies", "YouTube", "Visual novels", "Other"), rows.map { it.first })
        assertEquals(listOf("easy", "hard", "done"), rows[0].second.map { it.id })
        assertEquals(listOf("odd"), rows.last().second.map { it.id }) // reels are feed-only
    }

    @Test fun hoursAndMinutes() {
        assertEquals("45 min", hoursMinutes(45 * 60.0))
        assertEquals("2 h", hoursMinutes(120 * 60.0))
        assertEquals("11 h 36 min", hoursMinutes(696 * 60.0))
    }

    @Test fun pitchMorae() {
        assertEquals(listOf("きょ", "う"), JapaneseLookup.morae("きょう"))
        assertEquals(listOf(false, true, true), (0..2).map { JapaneseLookup.high(it, 0) }) // heiban
        assertEquals(listOf(true, false, false), (0..2).map { JapaneseLookup.high(it, 1) }) // atamadaka
    }

    @Test fun resumeWhereThePlainVideoGot() {
        assertEquals(300.0, resumeAt(300.0, 120.0)!!, 0.0)
        assertEquals(120.0, resumeAt(0.5, 120.0)!!, 0.0) // barely started: the saved place
    }
}
