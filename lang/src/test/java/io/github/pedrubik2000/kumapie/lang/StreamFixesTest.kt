package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Jimaku
import io.github.pedrubik2000.kumapie.data.Playable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** core's no-PC streaming bits: the next episode's id, VTT times without hours, 8-bit HEVC. */
class StreamFixesTest {
    @Test fun movieHasNoNext() = assertEquals(null, Addons.nextId("movie", "kitsu:12345"))
    @Test fun wholeTitleHasNoNext() = assertEquals(null, Addons.nextId("series", "kitsu:12345"))
    @Test fun nextEpisode() = assertEquals("kitsu:1:6", Addons.nextId("series", "kitsu:1:5"))
    @Test fun nextImdb() = assertEquals("tt1:1:3", Addons.nextId("series", "tt1:1:2"))

    @Test fun vttWithoutHours() {
        assertTrue(Jimaku.ARROW.containsMatchIn("00:05.000 --> 00:07.000"))
        val got = Jimaku.cues("en.vtt", "WEBVTT\n\n00:05.000 --> 00:07.000 align:start position:10%\nHello there\n".toByteArray(), Jimaku::plain)
        assertEquals(listOf(Jimaku.Line(5.0, 7.0, "Hello there")), got)
    }

    @Test fun eightBitHevcPlays() = assertEquals(emptySet<String>(), Playable.needs("[Grp] Show - 01 [1080p HEVC 8bit].mkv"))
    @Test fun plainHevcIsTenBit() = assertEquals(setOf("hevc10"), Playable.needs("[Grp] Show - 01 [1080p HEVC].mkv"))
}
