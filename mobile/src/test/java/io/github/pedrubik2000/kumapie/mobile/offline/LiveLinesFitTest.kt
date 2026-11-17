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
}
