package io.github.pedrubik2000.kumapie.mobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLinesTest {
    @Test fun sentenceMarksAndLength() {
        val toks = "今日は晴れ。".map { "$it" } + "明日は雨です".map { "$it" } + "、" + "でも大丈夫".map { "$it" }
        val lines = splitLines(toks, toks.indices.map { it * 0.5 }, toks.size * 0.5)
        assertEquals(listOf("今日は晴れ。", "明日は雨です、", "でも大丈夫"), lines.map { it.third })
        assertTrue(splitLines(List(40) { "あ" }, (0 until 40).map { it * 0.3 }, 12.0).all { it.second - it.first <= 8 })
    }
}
