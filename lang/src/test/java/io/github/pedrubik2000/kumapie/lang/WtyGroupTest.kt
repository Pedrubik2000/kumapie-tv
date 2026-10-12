package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

/** [YomitanDictionaries.wtyGroup]: a wty dictionary's group from its title (item 33). */
class WtyGroupTest {
    @Test fun groupFromTitle() {
        assertEquals("Monolingual", YomitanDictionaries.wtyGroup("wty-en-en"))
        assertEquals("Bilingual", YomitanDictionaries.wtyGroup("wty-de-en"))
        assertEquals("Bilingual", YomitanDictionaries.wtyGroup("wty-en-es-gloss"))
        assertEquals("", YomitanDictionaries.wtyGroup("wty-en-en-ipa"))
        assertEquals("", YomitanDictionaries.wtyGroup("JMdict"))
    }
}
