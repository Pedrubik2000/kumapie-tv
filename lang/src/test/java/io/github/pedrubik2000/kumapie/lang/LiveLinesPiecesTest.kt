package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.player.LiveLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core plan 17, bug 1 (Pedro, 2026-11-22: "good until a part, then out of sync by a lot"): one offset can't fit a
 * video whose cut differs partway. Synthetic timings: a Jimaku line every 2-7 s (about 24 min); the video's own
 * track (refs) on the same lines, moved.
 */
class LiveLinesPiecesTest {
    private fun lines(n: Int) = java.util.Random(17).let { r -> (0 until n).runningFold(5.0) { t, _ -> t + 2.0 + r.nextInt(50) / 10.0 } }
    private val starts = lines(320)

    @Test fun aBlockInsertedAtMinuteTwelve() {
        val refs = starts.map { if (it < 720) it + 2.0 else it + 32.0 }
        val p = requireNotNull(LiveLines.pieces(refs, starts))
        assertEquals(2, p.size)
        assertEquals(0.0 to 2.0, p[0])
        assertEquals(32.0, requireNotNull(p[1].second), 0.05)
        assertTrue("break ${p[1].first}", p[1].first in 720.0..755.0)
    }

    @Test fun aBlockRemoved() {
        val refs = starts.filter { it < 720 || it >= 780 }.map { if (it < 720) it + 2.0 else it - 58.0 }
        val p = requireNotNull(LiveLines.pieces(refs, starts))
        assertEquals(listOf(2.0, -58.0), p.map { requireNotNull(it.second) })
        assertTrue("break ${p[1].first}", p[1].first in 715.0..728.0)
    }

    @Test fun oneOffsetStaysOnePiece() = assertEquals(listOf(0.0 to -1.1), LiveLines.pieces(starts.map { it - 1.1 }, starts))

    // Rakugo Shinjuu 1 (core plan 11b): Netflix's TV-cut file on the Blu-ray is +12.4 s, then -352 ... -1968 s.
    @Test fun anotherCutIsNotPieces() {
        val long = lines(900)
        val refs = long.filter { it < 600 }.map { it + 12.4 } + long.filter { it >= 2580 && it < 3780 }.map { it - 1968.0 }
        assertNull(LiveLines.pieces(refs, long))
    }

    @Test fun theStretchAfterTheLastFitIsNotFittedYet() {
        val refs = starts.filter { it < 600 }.map { it + 2.0 } + starts.filter { it in 600.0..840.0 }.map { it * 1.01 }
        val p = requireNotNull(LiveLines.pieces(refs, starts))
        assertEquals(0.0 to 2.0, p.first())
        assertNull(p.last().second)
    }

    // Core plan 11b (a): an English track that splits lines ~2:1 (Dekinai), heard from mid-episode only.
    @Test fun anEnglishTrackOfMoreLinesFitsFromMidEpisode() {
        val mid = starts.filter { it in 900.0..1000.0 }
        val r = java.util.Random(3)
        val refs = mid.flatMap { listOf(it + 1.5, it + 2.3 + r.nextInt(8) / 10.0, it + 3.3 + r.nextInt(8) / 10.0) }
        assertEquals(1.5, requireNotNull(LiveLines.fit(refs, starts)), 0.05)
    }

    @Test fun movesShiftTheLinesAfterEachNewBreak() {
        val calls = ArrayList<Pair<Double, Double>>()
        val done = AloneStream.moves(listOf(0.0 to 2.0, 500.0 to null), 1) { by, from -> calls += by to from }
        assertEquals(1, done)
        assertEquals(2, AloneStream.moves(listOf(0.0 to 2.0, 737.0 to 32.0), done) { by, from -> calls += by to from })
        assertEquals(listOf(30.0 to 707.0), calls)
    }

    // Rakugo Shinjuu 1 (Pedro, 2026-11-25): 8 lines of a monologue fitted 71.3 s, the lines were then off all episode.
    private val monologue = (0 until 8).map { 300.0 + it * 6.1 + (it % 3) * 0.7 }
    private val file = lines(60).filter { it < 280 || it > 420 } + monologue.map { it - 71.3 }

    @Test fun eightLinesDoNotFitASeventyOneSecondShift() = assertNull(LiveLines.fit(monologue, file))

    @Test fun aBigShiftFitsOnceManyLinesAgree() {
        val refs = starts.filter { it in 100.0..400.0 }.map { it + 71.3 }
        assertEquals(71.3, requireNotNull(LiveLines.fit(refs, starts)), 0.11)
    }

    @Test fun aHandShiftIsNeverReplacedByTheFit() {
        assertEquals(2.4, requireNotNull(AloneStream.lateShift(0.0, 2.4, 2.4)), 1e-9)
        assertNull(AloneStream.lateShift(0.0, 2.4, 2.4, hand = true))
    }
}
