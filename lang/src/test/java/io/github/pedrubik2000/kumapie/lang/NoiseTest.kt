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
            "々", "ＫＯ", "ＯＫ", "ｊ", "Haus", "OK", "１０階", "100円", "3匹", "3ヶ月", "3か月", "5分間", "3月3日", "20才").forEach { assertTrue("noise: $it", noise(it)) }
    }

    @Test fun realWords() {
        listOf("腹", "誰", "すじ", "から", "は", "食べる", "ブッ", "Tシャツ", "二人", "𠮟", "〆").forEach { assertFalse("word: $it", noise(it)) }
    }

    @Test fun longDigitRunIsFast() {
        val t = System.nanoTime()
        assertFalse(noise("1".repeat(30) + "円です"))
        assertTrue((System.nanoTime() - t) / 1_000_000 < 100)
    }

    @Test fun whiteKeepsARealCard() {
        val w = io.github.pedrubik2000.kumapie.data.Word(status = "u", meaning = "")
        assertEquals("k", Noise.white(w.copy(card = "u")).shown)
        assertEquals("d", Noise.white(w.copy(card = "d")).shown)
        assertEquals("b", Noise.white(w.copy(card = "b")).shown)
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
