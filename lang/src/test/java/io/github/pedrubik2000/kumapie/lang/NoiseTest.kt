package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseTest {
    private fun noise(w: String) = Noise.isNoise(w, Lang.JAPANESE)

    @Test fun noiseWords() {
        listOf("", " ", "ぞ", "ぺ", "ク", "ン", "っ", "ゅ", "ょ", "20", "5日", "８人", "第１", "♪", "~", "➡", "⚟", "\u200E", "-", "...",
            "ＫＯ", "ＯＫ", "ｊ", "Haus", "OK", "１０階").forEach { assertTrue("noise: $it", noise(it)) }
    }

    @Test fun realWords() {
        listOf("腹", "誰", "すじ", "から", "は", "食べる", "ブッ", "Tシャツ", "二人").forEach { assertFalse("word: $it", noise(it)) }
    }

    @Test fun otherLanguagesAreNeverNoise() {
        assertFalse(Noise.isNoise("ぞ", Lang.GERMAN))
        assertFalse(Noise.isNoise("20", Lang.ENGLISH))
    }

    /** A noise word is known for colouring (with or without a list) and is left out of the count. */
    @Test fun noiseIsKnownButNotCounted() {
        val index = CardIndex("ja")
        val extra = { w: String -> noise(w) }
        val st = { w: String -> if (noise(w)) "k" else null }
        assertEquals("k", index.byCard("ぞ", emptySet(), extra, st))
        assertEquals("k", index.byCard("20", emptySet(), extra, st))
        assertEquals(null, index.byCard("犬", emptySet(), extra, st))
        val listed = index.known(listOf("ぞ", "20", "犬"), setOf("♪", "犬"), extra, st)
        assertTrue("ぞ" in listed)
        assertEquals(listOf("犬"), Noise.countable(listed, Lang.JAPANESE))
    }
}
