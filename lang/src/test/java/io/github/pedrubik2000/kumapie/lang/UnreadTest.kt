package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Cue
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Scene
import io.github.pedrubik2000.kumapie.data.Segment
import io.github.pedrubik2000.kumapie.data.Word
import org.junit.Assert.assertEquals
import org.junit.Test

/** Before Anki is read on the device (plan item 7): the PC's "all known" doesn't survive; only marked words are white. */
class UnreadTest {
    @Test fun redUntilRead() {
        val cue = Cue(0.0, 1.0, listOf(Segment("I", "i"), Segment(" ", null), Segment("love", "love"), Segment(" ", null), Segment("you", "you")))
        val scene = Scene("s", 0, 0.0, 1.0, target = true, level = 0, seen = false, cues = listOf(cue), english = emptyList(), meanings = emptyMap())
        val pc = EpisodeDetail("e", "show", "ep", "en", 1.0, "", null, listOf(scene),
            words = mapOf("i" to Word("k", ""), "love" to Word("k", ""), "you" to Word("k", "", card = "k")))
        val out = KnownWords.unread(pc, marked = setOf("i"))
        assertEquals(mapOf("i" to "k", "love" to "u", "you" to "u"), out.words.mapValues { it.value.shown })
        assertEquals(2, out.scenes.single().level)
    }
}
