package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

/** [wordRanges]: where a Review card marks its word in the sentence. */
class WordRangesTest {
    @Test fun conjugatedJapaneseMarksTheStem() {
        assertEquals(listOf(2..4), wordRanges("必ず仕留め…", "仕留める"))
        assertEquals(listOf(0..1), wordRanges("食べた", "食べる"))
    }

    @Test fun kanaOnlyAndSpacedWordsStayExact() {
        assertEquals(emptyList<IntRange>(), wordRanges("すぐ", "する"))
        assertEquals(listOf(4..7), wordRanges("Ich gehe", "gehe"))
        assertEquals(emptyList<IntRange>(), wordRanges("Ich gehen", "gehe"))
    }
}
