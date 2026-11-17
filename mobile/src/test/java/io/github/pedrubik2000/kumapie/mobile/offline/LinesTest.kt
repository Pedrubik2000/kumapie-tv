package io.github.pedrubik2000.kumapie.mobile.offline

import io.github.pedrubik2000.kumapie.data.Cue
import io.github.pedrubik2000.kumapie.data.Scene
import io.github.pedrubik2000.kumapie.data.Segment
import io.github.pedrubik2000.kumapie.data.Span
import io.github.pedrubik2000.kumapie.data.lines
import org.junit.Assert.assertEquals
import org.junit.Test

/** The player's lines from the PC's scenes: one per subtitle line, pieces shown together as one. */
class LinesTest {
    private fun cue(a: Double, b: Double, t: String) = Cue(a, b, listOf(Segment(t, null)))
    private val scene = Scene("s", 0, 0.7, 6.0, target = true, level = 0, seen = false,
        cues = listOf(cue(1.0, 2.0, "a"), cue(1.0, 2.5, "b"), cue(2.6, 3.0, "c"), cue(5.0, 5.5, "d")), english = emptyList(), meanings = emptyMap())
    private val got = lines(listOf(scene), listOf(Span(2500, 3200)))

    @Test fun piecesShownTogetherAreOneLine() = assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), got.map { l -> l.cues.map { it.text } })
    @Test fun idsAndPlacesInThePcsScene() = assertEquals(listOf("s:0" to 0, "s:2" to 2, "s:3" to 3), got.map { it.id to it.sourceLine })
    @Test fun aLineEndsAfterItsLastPieceNeverInsideTheNext() = assertEquals(listOf(1.0 to 2.6, 2.6 to 3.25, 5.0 to 5.75), got.map { it.start to it.end })
    @Test fun seenByWatchedTime() = assertEquals(listOf(false, true, false), got.map { it.seen })
    @Test fun numbered() = assertEquals(listOf(0, 1, 2), got.map { it.index })
}
