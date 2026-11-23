package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Jimaku
import io.github.pedrubik2000.kumapie.player.LiveLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core plan 11b (b), Rakugo Shinjuu 1: the English line below the Japanese one. First choice the video's own English
 * track (cues arrive live on the video's clock: [LiveLines.grow], placed by [Jimaku.owner]); else an addon's English
 * file, unless it is the other cut ([Addons.fitEnglish] by duration).
 */
class EmbeddedEnglishTest {
    @Test fun aCueOpensWhenItShowsAndClosesWhenItGoes() {
        var c = LiveLines.grow(emptyList(), "", "Hello.", 10.0)
        assertEquals(listOf(Jimaku.Line(10.0, 15.0, "Hello.")), c)
        c = LiveLines.grow(c, "Hello.", "", 12.5)
        assertEquals(listOf(Jimaku.Line(10.0, 12.5, "Hello.")), c)
        assertEquals("same group again", c, LiveLines.grow(c, "", "", 13.0))
    }

    @Test fun oneLineReplacingAnotherClosesTheFirst() {
        val c = LiveLines.grow(LiveLines.grow(emptyList(), "", "A", 1.0), "A", "B", 3.0)
        assertEquals(listOf(Jimaku.Line(1.0, 3.0, "A"), Jimaku.Line(3.0, 8.0, "B")), c)
    }

    @Test fun seekingBackDoesNotRepeatACue() {
        val seen = LiveLines.grow(emptyList(), "", "Hello.", 10.0)
        assertEquals(1, LiveLines.grow(seen, "", "Hello.", 11.0).size)
        assertEquals(2, LiveLines.grow(seen, "", "Hello.", 400.0).size)
    }

    @Test fun aCueGoesUnderTheLineItOverlapsMost() {
        val spans = listOf(0.0 to 4.0, 5.0 to 9.0, 10.0 to 12.0)
        assertEquals(1, Jimaku.owner(spans, 6.0, 8.0))
        assertEquals(0, Jimaku.owner(spans, 3.0, 5.5))
        assertEquals("between lines: the closer", 2, Jimaku.owner(spans, 9.6, 9.9))
    }

    @Test fun scenesGetTheEnglishOfTheirTimeAndNoOldOne() {
        val old = io.github.pedrubik2000.kumapie.data.Line(0.0, 1.0, "old")
        val scenes = listOf(0.0 to 4.0, 5.0 to 9.0).mapIndexed { i, (a, b) ->
            io.github.pedrubik2000.kumapie.data.Scene("s$i", i, a, b, true, null, false, emptyList(), listOf(old), emptyMap())
        }
        val out = AloneStream.withEnglish(scenes, listOf(Jimaku.Line(6.0, 8.0, "B"), Jimaku.Line(1.0, 3.0, "A")))
        assertEquals(listOf("A"), out[0].english.map { it.text })
        assertEquals(listOf("B"), out[1].english.map { it.text })
    }

    private fun file(last: Double) = java.util.Random(5).let { r -> (0..300).runningFold(5.0) { t, _ -> t + 2.0 + r.nextInt(50) / 10.0 } }
        .plus(last).map { Jimaku.Line(it, it + 2, "x") }

    @Test fun anAddonEnglishOfTheOtherCutIsNeverShown() {
        val japanese = file(4909.0).map { it.start }
        val tvCut = file(2896.2)
        assertTrue(Addons.fitEnglish(listOf(tvCut), japanese, 4920.9).isEmpty())
        assertTrue("the first that passes is used", Addons.fitEnglish(listOf(tvCut, file(4909.0)), japanese, 4920.9).isNotEmpty())
        assertTrue("duration unknown: as before", Addons.fitEnglish(listOf(tvCut), tvCut.map { it.start }, null).isNotEmpty())
    }
}
