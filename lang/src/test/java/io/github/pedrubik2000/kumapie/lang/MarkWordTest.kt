package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

/** [markWord]: the marks left by German and English stay out of Japanese. */
class MarkWordTest {
    @Test fun otherLanguagesMarksStayOut() {
        val keys = listOf("rufe an", "gehst", "en:give up", "気にする", "en:sushi寿司")
        assertEquals(listOf("気にする"), keys.mapNotNull { markWord(it) })
    }

    /** Numbers and signs marked in Japanese have no Japanese script: Japanese keeps them too (they came back red). */
    @Test fun numbersAndSignsStayJapanese() {
        val keys = listOf("3", "～", "ＫＯ", "gehst", "en:give up")
        assertEquals(listOf("3", "～", "ＫＯ"), keys.mapNotNull { markWord(it) })
    }
}
