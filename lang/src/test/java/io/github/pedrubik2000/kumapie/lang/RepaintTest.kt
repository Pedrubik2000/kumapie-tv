package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Word
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one repaint rule (TV and phone/tablet, [KnownWords.recolour]): PC colours vs this device's own. */
class RepaintTest {
    private fun shown(p: Word, pc: Boolean, own: String?) = KnownWords.recolour(p, pc, own).shown

    @Test fun pcColoursKeepThePcsStatusForWordsWithoutACard() {
        assertEquals("k", shown(Word("k", "", card = "u"), pc = true, own = null)) // せっかく on a PC episode
        assertEquals("d", shown(Word("k", "", card = "d"), pc = true, own = null)) // its own card still wins
    }

    @Test fun ownColoursUseTheDevicesStatus() {
        assertEquals("k", shown(Word("u", "", card = "u"), pc = false, own = "k"))  // built red, marked/imported here
        assertEquals("u", shown(Word("u", "", card = "u"), pc = false, own = null)) // unknown stays red
        assertEquals("b", shown(Word("u", "", card = "b"), pc = false, own = "k"))  // a new card stays blue
        assertEquals("", KnownWords.recolour(Word("u", "", card = "u", forms = mapOf("届いた" to "u")), false, "k").forms!!["届いた"])
    }
}
