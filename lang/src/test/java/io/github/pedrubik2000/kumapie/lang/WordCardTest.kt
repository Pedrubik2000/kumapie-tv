package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared word card's rules ([WordCard]). */
class WordCardTest {
    /** Pedro, 2026-11-19: the dictionary sense that fits the line comes before the PC's meaning (row 0). */
    @Test fun fittingSenseBeforeThePcsMeaning() {
        assertEquals(3, WordCard.pick(3))
        assertEquals(0, WordCard.pick(-1)) // nothing fits: the first row (the PC's meaning when there is one)
    }

    @Test fun shortDictNames() {
        assertEquals("Sankoku", WordCard.shortDictName("sankoku8-gpt-5.6-luna en-jp"))
        assertEquals("Jitendex", WordCard.shortDictName("Jitendex.org [2026-10-03]"))
        assertEquals("JMdict", WordCard.shortDictName("JMdict"))
        assertEquals("大辞林", WordCard.shortDictName("大辞林"))
    }
}
