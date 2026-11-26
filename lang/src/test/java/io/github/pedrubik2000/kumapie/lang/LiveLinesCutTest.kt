package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.player.LiveLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cyberpunk Edgerunners 1-3 with a 60 s scene cut at 600 s (retime bench, 2026-11-26): the stretch after the cut had 44
 * hits at the right offset against 13 at a chance one, and a flat 4 times the runner-up refused it on 2 of 3 episodes.
 */
class LiveLinesCutTest {
    private val starts = java.util.Random(17).let { r -> (0 until 320).runningFold(5.0) { t, _ -> t + 2.0 + r.nextInt(50) / 10.0 } }
    private val before = starts.filterIndexed { i, s -> s < 600 && i % 4 != 3 }
    private fun after(keepOf: Int) = starts.filterIndexed { i, s -> s >= 660 && i % keepOf != keepOf - 1 }.map { it - 60.0 }

    @Test fun aSixtySecondCutFitsWhenAThirdOfTheLinesAreMissing() {
        val p = requireNotNull(LiveLines.pieces(before + after(3), starts))
        assertEquals(listOf(0.0, -60.0), p.map { requireNotNull(it.second) })
        assertTrue("break ${p[1].first}", p[1].first in 595.0..665.0)
    }

    @Test fun aSixtySecondCutFitsOnTheStretchAfterIt() {
        val refs = after(4).filter { it in 660.0..900.0 }
        assertEquals(-60.0, requireNotNull(LiveLines.fit(refs, starts.filter { it in refs.first()..refs.last() + 400 })), 0.11)
    }

    @Test fun lineStartsOfAnotherPlaceAreNotACut() {
        val lone = starts.filter { it in 1000.0..1240.0 }.mapIndexed { i, s -> s + 0.07 * (i % 5) + 30 * (i % 2) }
        assertEquals(null, LiveLines.fit(lone, starts))
    }

    private fun lines(texts: List<String>) = texts.mapIndexed { i, t -> Triple(i * 4.0, i * 4.0 + 3, t) }

    @Test fun anEnglishOnlyFileHasNoJapaneseLines() =
        assertEquals(emptyList<Any>(), LiveLines.japanese(lines(List(40) { "Eyes, burning a way through me" })))

    @Test fun aJapaneseFileStaysWhole() {
        val ja = lines(List(40) { "何をしている" })
        assertEquals(ja, LiveLines.japanese(ja))
    }

    @Test fun aBilingualFileKeepsItsJapaneseLines() {
        val mixed = lines(List(30) { "何をしている" } + List(10) { "Eyes, burning" })
        assertEquals(30, LiveLines.japanese(mixed).size)
    }

    @Test fun mostlyEnglishIsNotJapanese() = assertEquals(0, LiveLines.japanese(lines(List(35) { "Eyes" } + List(5) { "何をしている" })).size)
}
