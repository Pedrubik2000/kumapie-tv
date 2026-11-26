package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.player.LiveLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(listOf(0.0, -60.0), p.mapNotNull { it.second })
        assertTrue("break ${p[1].first}", p[1].first in 595.0..665.0)
    }

    @Test fun aSixtySecondCutFitsOnTheStretchAfterIt() {
        val refs = after(4).filter { it in 660.0..900.0 }
        assertEquals(-60.0, requireNotNull(LiveLines.fit(refs, starts.filter { it in refs.first() - 200..refs.last() + 200 })), 0.11)
    }

    private fun falseShift(seed: Long): Pair<List<Double>, List<Double>> {
        val r = java.util.Random(seed)
        val mono = (0 until 23).runningFold(300.0) { t, _ -> t + 4.0 + r.nextInt(40) / 10.0 }
        val file = ((0 until 11).map { 10 + it * 9.3 } + mono.take(12).map { it - 71.3 } + (0 until 100).map { 640 + it * 5.7 }).sorted()
        return mono to file
    }

    @Test fun aCutBlockSeventyOneSecondsAwayIsNotAShift() {
        val gaps = listOf(4.3, 7.8, 5.1, 4.4, 6.9, 7.2, 4.0, 5.6, 7.7, 4.8, 6.3, 5.0, 7.5, 4.6, 6.0, 7.9, 5.4, 4.1, 6.7, 5.8, 7.0, 4.9, 6.4)
        val mono = gaps.runningFold(300.0) { t, g -> t + g }
        val file = ((0 until 11).map { 10 + it * 9.3 } + mono.take(12).map { it - 71.3 } + (0 until 100).map { 640 + it * 5.7 }).sorted()
        assertNull(LiveLines.fit(mono, file))
    }

    @Test fun aSparseLandingNeverMakesAShiftWhateverTheLines() {
        for (seed in 1L..40L) falseShift(seed).let { (mono, file) -> assertNull("seed $seed", LiveLines.fit(mono, file)) }
    }

    @Test fun sixteenOfTwentyFourLinesAreNotAShiftWithFiveAtAnotherOffset() {
        for (seed in 0L..40L) {
            val r = java.util.Random(seed)
            val mono = (0 until 23).runningFold(300.0) { t, _ -> t + 4.0 + r.nextInt(40) / 10.0 }
            val rest = generateSequence { r.nextInt(1600) / 2.0 }.filter { it < 220 || it > mono.last() }.take(120).toList()
            assertNull("seed $seed", LiveLines.fit(mono, (mono.take(16).map { it - 71.3 } + rest).sorted()))
        }
    }

    @Test fun aDenseFileDoesNotFitABigShiftFromTwentyLines() {
        val r = java.util.Random(5)
        val dense = (0 until 1200).runningFold(0.0) { t, _ -> t + 0.5 + r.nextInt(100) / 100.0 }
        assertNull(LiveLines.fit((0 until 20).map { dense[60 + it * 7] + 71.3 }, dense))
    }

    @Test fun lineStartsOfAnotherPlaceAreNotACut() {
        val lone = starts.filter { it in 1000.0..1240.0 }.mapIndexed { i, s -> s + 0.07 * (i % 5) + 30 * (i % 2) }
        assertNull(LiveLines.fit(lone, starts))
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
