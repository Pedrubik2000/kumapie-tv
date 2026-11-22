package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Jimaku
import io.github.pedrubik2000.kumapie.player.LiveLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shouwa Genroku Rakugo Shinjuu 1 (core plan 11b), real data, timestamps only. The Blu-ray releases on Real-Debrid are
 * the 82-min director's cut (4920.9 s: [CTZ], [sam], [Cleo], [Sokudo]); Jimaku has two files for episode 1: FMA1394's
 * "[J BD]" .ass (last line at 4909.2 s, timed to the Blu-ray) and Netflix's .srt (2896.2 s, the 48-min TV cut). Both
 * fit [Cleo]'s first English lines (-0.1 s and -13.0 s), so the fit can't tell the wrong cut; its length can.
 * Dandadan 12 (the control): seven Jimaku files ending 1399-1407 s, videos of 1407-1420 s: every one keeps fitting.
 */
class JimakuCutTest {
    private val cleoEnglish = listOf(13.58, 13.68, 21.07, 22.01, 23.84, 26.14, 29.57, 34.91, 36.28, 39.65, 41.28, 42.99, 46.74, 47.04)
    private val fmaJapanese = listOf(12.61, 21.12, 23.96, 25.92, 27.54, 29.67, 34.97, 36.26, 38.72, 41.27, 43.02, 46.9, 49.44, 52.94, 55.74, 59.62)
    private val netflixJapanese = listOf(25.57, 34.08, 36.91, 38.87, 40.5, 42.62, 47.92, 49.22, 51.68, 54.22, 55.97, 59.85, 62.4, 65.9, 68.69, 72.57, 74.95)

    private fun file(starts: List<Double>, last: Double) = (starts + last).map { Jimaku.Line(it, it + 2, "") }

    @Test fun blurayFileFitsTheBluray() {
        val fit = LiveLines.fit(cleoEnglish, fmaJapanese)
        assertEquals(0.0, requireNotNull(fit), 0.3)
        assertTrue(Jimaku.fitsLength(file(fmaJapanese, 4909.2), 4920.9))
    }

    @Test fun tvCutIsRejectedThoughItsStartFits() {
        assertNotEquals(null, LiveLines.fit(cleoEnglish, netflixJapanese))
        assertFalse(Jimaku.fitsLength(file(netflixJapanese, 2896.2), 4920.9))
        assertFalse("the Blu-ray file on a TV-cut video", Jimaku.fitsLength(file(fmaJapanese, 4909.2), 2940.0))
    }

    @Test fun dandadanTwelveStillFits() {
        for (last in listOf(1399.0, 1401.0, 1402.0, 1404.0, 1407.0)) for (video in listOf(1407.1, 1409.0, 1420.4))
            assertTrue("$last in $video", Jimaku.fitsLength(file(fmaJapanese, last), video))
    }

    @Test fun unknownLengthKeepsTheFirst() = assertTrue(Jimaku.fitsLength(file(netflixJapanese, 2896.2), null))

    @Test fun shiftIsKeptPerFile() {
        val id = "alone:kitsu:10051:1"
        assertEquals("alone_off_kitsu:10051:1|[Cleo]Shouwa_Genroku_Rakugo_Shinjuu_-_01_(10bit_BD1080p_x265).mkv",
            AloneStream.offKey(id, "[Cleo]Shouwa_Genroku_Rakugo_Shinjuu_-_01_(10bit_BD1080p_x265).mkv"))
        assertNotEquals(AloneStream.offKey(id, "[sam] Shouwa Genroku Rakugo Shinjuu - 01 [BD 1080p FLAC] [BAA0400A].mkv"),
            AloneStream.offKey(id, "[Cleo]Shouwa_Genroku_Rakugo_Shinjuu_-_01_(10bit_BD1080p_x265).mkv"))
    }
}
