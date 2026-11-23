package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Jimaku
import io.github.pedrubik2000.kumapie.player.LiveLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core plan 11b (b), Rakugo Shinjuu 1: the English line below the Japanese one. First choice the video's own English
 * track (cues arrive live on the video's clock: [LiveLines.grow], placed by [Jimaku.owner]); else an addon's English
 * file, unless it is the other cut ([Addons.fitEnglish] by duration).
 */
class EmbeddedEnglishTest {
    private class Track {
        var cues = emptyList<Jimaku.Line>()
        var open = emptySet<Int>()
        fun at(now: Double, vararg texts: String) = LiveLines.grow(cues, open, texts.toList(), now).let { cues = it.first; open = it.second }
        fun seek(from: Double) { cues = LiveLines.stop(cues, open, from); open = emptySet() }
        fun sane() = cues.all { it.end > it.start }
    }

    @Test fun aCueOpensWhenItShowsAndClosesWhenItGoes() {
        val t = Track()
        t.at(10.0, "Hello.")
        assertEquals(listOf(Jimaku.Line(10.0, 15.0, "Hello.")), t.cues)
        t.at(12.5)
        assertEquals(listOf(Jimaku.Line(10.0, 12.5, "Hello.")), t.cues)
        t.at(13.0)
        assertEquals(1, t.cues.size)
    }

    @Test fun oneLineReplacingAnotherClosesTheFirst() {
        val t = Track()
        t.at(1.0, "A")
        t.at(3.0, "B")
        assertEquals(listOf(Jimaku.Line(1.0, 3.0, "A"), Jimaku.Line(3.0, 8.0, "B")), t.cues)
    }

    @Test fun anEmptyGroupAtAnEarlierPositionNeverInvertsACue() {
        val t = Track()
        t.at(10.0, "Hello.")
        t.at(9.5)
        assertTrue(t.sane())
        t.at(9.5, "Hello.")
        assertEquals(listOf(Jimaku.Line(9.5, 15.0, "Hello.")), t.cues)
        t.at(12.0, "Other.")
        t.at(13.0)
        assertEquals("only the open cue was closed", listOf("Hello." to 12.0, "Other." to 13.0), t.cues.map { it.text to it.end })
    }

    @Test fun seekingBackFortySecondsAndPlayingAgainAddsNothing() {
        val t = Track()
        t.at(100.0, "A")
        t.at(103.0)
        t.at(105.0, "B")
        t.at(108.0)
        t.at(60.0)
        t.at(100.2, "A")
        t.at(103.0)
        t.at(105.1, "B")
        assertEquals(listOf("A", "B"), t.cues.map { it.text })
        assertTrue(t.sane())
        assertEquals(103.0, t.cues[0].end, 0.0)
        t.seek(106.0)
        t.at(400.0, "A")
        assertEquals("far away is a new line", 3, t.cues.size)
        assertEquals("B closed where the player left", 106.0, t.cues[1].end, 0.0)
        assertTrue(t.sane())
    }

    @Test fun aCueKeepsItsTrueLength() {
        val t = Track()
        t.at(10.0, "Long.")
        t.at(17.0)
        assertEquals(listOf(Jimaku.Line(10.0, 17.0, "Long.")), t.cues)
    }

    @Test fun seekingAwayWhileACueIsOpenLeavesASaneEnd() {
        val t = Track()
        t.at(10.0, "A")
        t.seek(12.0)
        t.at(500.0, "B")
        t.seek(501.0)
        t.at(2.0, "C")
        t.seek(1.0)
        assertTrue(t.sane())
        assertEquals(listOf(12.0, 501.0, 2.1), t.cues.map { it.end })
        assertTrue(t.open.isEmpty() || t.open == setOf(2))
    }

    @Test fun theSameWordTwiceBackToBackStaysTwoCuesUnderTwoScenes() {
        val t = Track()
        t.at(10.0, "Yes.")
        t.at(11.0)
        t.at(11.2, "Yes.")
        assertEquals(2, t.cues.size)
        val scenes = listOf(10.0 to 11.0, 11.2 to 12.0).mapIndexed { i, (a, b) ->
            io.github.pedrubik2000.kumapie.data.Scene("s$i", i, a, b, true, null, false, emptyList(), emptyList(), emptyMap())
        }
        val out = AloneStream.withEnglish(scenes, t.cues, t.open)
        assertEquals(listOf(1, 1), out.map { it.english.size })
    }

    @Test fun replayingALineGivesOneCopyWithASaneSpan() {
        val t = Track()
        t.at(100.0, "Line.")
        t.at(103.0)
        t.seek(103.5)
        t.at(100.1, "Line.")
        t.at(103.0)
        assertEquals(listOf(Jimaku.Line(100.0, 103.0, "Line.")), t.cues)
    }

    @Test fun aCueAtTheTopOfTheScreenIsASignNotDialogue() {
        val b = androidx.media3.common.text.Cue.Builder().setText("NEXT TIME")
        assertTrue(LiveLines.top(b.setLine(0.05f, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION).build()))
        assertFalse(LiveLines.top(b.setLine(0.95f, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION).build()))
        assertFalse(LiveLines.top(androidx.media3.common.text.Cue.Builder().setText("x").build()))
    }

    @Test fun aSecondCueComingAndGoingDuringALineDoesNotRepeatIt() {
        val t = Track()
        t.at(20.0, "Hello.")
        t.at(21.0, "Hello.", "NEXT TIME")
        t.at(22.0, "Hello.")
        t.at(23.0)
        assertEquals(listOf(23.0), t.cues.filter { it.text == "Hello." }.map { it.end })
        assertEquals("Hi.", Jimaku.notes("(sighs) Hi."))
        assertEquals("Hi.", Jimaku.notes("Hi. (laughs)"))
    }

    @Test fun anOpenCueBelongsToTheSceneItStartsIn() {
        val scenes = listOf(10.0 to 11.8, 12.2 to 16.0).mapIndexed { i, (a, b) ->
            io.github.pedrubik2000.kumapie.data.Scene("s$i", i, a, b, true, null, false, emptyList(), emptyList(), emptyMap())
        }
        val cues = listOf(Jimaku.Line(10.1, 15.1, "Hello."))
        assertEquals(listOf("Hello."), AloneStream.withEnglish(scenes, cues, setOf(0))[0].english.map { it.text })
        assertEquals("closed: by overlap", listOf("Hello."), AloneStream.withEnglish(scenes, cues)[1].english.map { it.text })
    }

    @Test fun addonEnglishPlacedWinsEmbeddedFillsIn() {
        val cue = listOf(Jimaku.Line(1.0, 2.0, "x"))
        assertFalse(AloneStream.useEmbedded(true, cue))
        assertTrue(AloneStream.useEmbedded(false, cue))
        assertFalse(AloneStream.useEmbedded(false, emptyList()))
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
