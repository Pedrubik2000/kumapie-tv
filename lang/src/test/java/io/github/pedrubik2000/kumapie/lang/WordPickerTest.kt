package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Cue
import io.github.pedrubik2000.kumapie.data.Segment
import io.github.pedrubik2000.kumapie.player.WordPicker.Spot
import io.github.pedrubik2000.kumapie.player.wordStops
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the TV's word picker stops (core plan 13b, Pedro 2026-11-21: ハリウッド系だよね, an i+0 line, couldn't be looked up):
 * a line without a red word stops on every word, a line with one only on the coloured words, as before.
 */
class WordPickerTest {
    private fun cue(vararg words: String) = Cue(0.0, 1.0, words.flatMap { listOf(Segment(it, it), Segment(" ", null)) })
    private val scene = listOf(cue("ハリウッド", "系", "だ"), cue("日本人", "なかなか", "いない"))

    private fun stops(colours: Map<String, String>, here: Spot? = null) =
        wordStops(scene, here) { s -> colours[scene[s.line].segments[s.seg].text] ?: "k" }

    @Test fun allKnownEveryWordInOrder() {
        val all = listOf(Spot(0, 0), Spot(0, 2), Spot(0, 4), Spot(1, 0), Spot(1, 2), Spot(1, 4))
        assertEquals(all, stops(emptyMap()))
        // A blue word (mined) doesn't make the line skip its white words: only a red one does.
        assertEquals(all, stops(mapOf("系" to "m")))
    }

    @Test fun oneUnknownOnlyIt() {
        val got = stops(mapOf("系" to "u"))
        assertEquals(listOf(Spot(0, 2)), got.filter { it.line == 0 })
    }

    @Test fun downAndUpReachEveryLineOfAKnownScene() {
        assertEquals(listOf(0, 1), stops(emptyMap()).map { it.line }.distinct())
        // A known line under a line with a new word: ↓ still gets there, and onto all its words.
        val mixed = stops(mapOf("系" to "u"))
        assertEquals(listOf(Spot(0, 2), Spot(1, 0), Spot(1, 2), Spot(1, 4)), mixed)
    }

    @Test fun markingTheLastUnknownKnownKeepsTheCursor() {
        val here = Spot(0, 2)
        val after = stops(emptyMap(), here)
        assertEquals(true, here in after)
        assertEquals(listOf(Spot(0, 4)), after.drop(after.indexOf(here) + 1).take(1))
        // With another red word left in the line, the word just marked stays a stop while the cursor is on it.
        assertEquals(listOf(Spot(0, 2), Spot(0, 4)), stops(mapOf("だ" to "u"), here).filter { it.line == 0 })
    }
}
