package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** kumapie_plan.md "Words and colors": the subtitle and definition words' colours ([wordColor], [Word.shownFor]). */
class WordColourTest {
    @Test fun noOrangeAnywhere() {
        val states = listOf(null, "u", "l", "d", "b", "k", "w", "")
        states.forEach { assertNotEquals("state $it", Colors.learning, wordColor(it)) }
        assertEquals(setOf(Colors.unknown, Colors.due, Colors.mined, Colors.text), states.map { wordColor(it) }.toSet())
    }

    @Test fun japaneseColoursByDictionaryForm() {
        val hiku = Word("u", "", card = "d", forms = mapOf("引いて" to "u", "引く" to "d"))
        assertEquals("引いて takes 引く's card", "d", hiku.shownFor("引いて"))
        val kawasu = Word("u", "", card = "u", forms = mapOf("躱した" to "k", "躱し" to "u"))
        assertEquals("no card for 躱す: 躱した's own counts", "k", kawasu.shownFor("躱した"))
        assertEquals("u", kawasu.shownFor("躱し"))
    }
}
