package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Segment
import io.github.pedrubik2000.kumapie.data.joinCarded
import org.junit.Assert.assertEquals
import org.junit.Test

/** A card the splitter cuts in pieces (怪奇現象 → 怪奇 + 現象) becomes one word again, so it takes its card's colour. */
class CardedRunsTest {
    private fun segs(vararg s: Pair<String, String?>) = s.map { Segment(it.first, it.second) }

    @Test fun aCardCutInPiecesIsOneWord() {
        val line = segs("怪奇" to "怪奇", "現象" to "現象", "だ" to "だ", "。" to null)
        assertEquals(segs("怪奇現象" to "怪奇現象", "だ" to "だ", "。" to null), joinCarded(line) { it == "怪奇現象" })
    }

    @Test fun longestRunFirstAndOnlyTouchingWords() {
        val line = segs("a" to "a", "b" to "b", "c" to "c", " " to null, "d" to "d")
        val carded = setOf("ab", "abc", "c d")
        assertEquals(segs("abc" to "abc", " " to null, "d" to "d"), joinCarded(line) { it in carded })
    }

    @Test fun noCardLeavesTheLineAsItWas() {
        val line = segs("怪奇" to "怪奇", "現象" to "現象")
        assertEquals(line.take(1), joinCarded(line.take(1)) { true })
        assertEquals(line, joinCarded(line) { false })
    }
}
