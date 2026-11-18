package io.github.pedrubik2000.kumapie.mobile.offline

import io.github.pedrubik2000.kumapie.player.LiveLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Jimaku's timing fitted to the file's other subtitle track (an English fansub's cue times). */
class LiveLinesFitTest {
    private val jimaku = listOf(12.0, 15.5, 19.2, 24.0, 31.7, 33.1, 40.8, 47.3, 52.0, 58.6, 63.0, 70.4)

    @Test fun aShiftedReleaseIsFoundDespiteExtraAndMissingLines() {
        // The video runs 2.4 s later than Jimaku's file; the English track splits some lines, skips some, adds signs.
        val english = jimaku.filterIndexed { i, _ -> i % 4 != 3 }.map { it + 2.4 + (if (it > 40) 0.05 else -0.05) } + listOf(5.0, 27.9, 44.4, 66.6)
        assertEquals(2.4, LiveLines.fit(english.sorted(), jimaku)!!, 0.11)
    }

    @Test fun nothingWhenTheTracksDontAgree() =
        assertNull(LiveLines.fit(listOf(3.3, 9.9, 17.1, 22.6, 28.0, 36.4, 41.9, 55.5), listOf(100.0, 230.0, 400.0)))

    @Test fun anEnglishFileIsPlacedPerPartOfTheEpisode() {
        // AoT 1 (2026-11-17): the English file runs 0.8 s ahead before the opening and 2.8 s after; it splits and skips lines.
        val japanese = (0 until 120).map { 3.0 + it * 5.1 + (it % 3) * 0.7 }
        val english = japanese.filterIndexed { i, _ -> i % 5 != 2 }.map { if (it < 300) it + 0.8 else it + 2.8 } + listOf(200.5, 450.2)
        val placed = LiveLines.place(japanese, english.sorted())!!
        val onJapanese = placed.count { p -> japanese.any { kotlin.math.abs(it - p) <= 0.15 } }
        // The chunk with the opening in it (180-360 s) takes one of the two: its lines past 300 s stay off.
        org.junit.Assert.assertTrue("$onJapanese of ${english.size}", onJapanese >= english.size * 0.8)
    }

    @Test fun anotherEpisodesFileIsNotPlaced() =
        assertNull(LiveLines.place((0 until 120).map { 3.0 + it * 5.1 }, (0 until 100).map { 7.3 + it * it * 0.061 }))
}
