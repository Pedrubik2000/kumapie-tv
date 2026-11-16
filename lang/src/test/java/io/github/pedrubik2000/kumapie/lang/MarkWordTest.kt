package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

/** [markWord]: each word marked known in Progress goes back to exactly one language. */
class MarkWordTest {
    @Test fun eachMarkHasOneLanguage() {
        val keys = listOf("rufe an", "gehst", "en:give up", "気にする", "en:sushi寿司")
        assertEquals(listOf("rufe an", "gehst"), keys.mapNotNull { markWord(it, "") })
        assertEquals(listOf("give up", "sushi寿司"), keys.mapNotNull { markWord(it, "en:") })
        assertEquals(listOf("気にする"), keys.mapNotNull { markWord(it, null) })
    }

    /** Numbers and signs marked in Japanese have no Japanese script: Japanese keeps them too (they came back red). */
    @Test fun numbersAndSignsStayJapanese() {
        val keys = listOf("3", "～", "ＫＯ", "gehst", "en:give up")
        assertEquals(listOf("3", "～", "ＫＯ"), keys.mapNotNull { markWord(it, null) })
    }
}
