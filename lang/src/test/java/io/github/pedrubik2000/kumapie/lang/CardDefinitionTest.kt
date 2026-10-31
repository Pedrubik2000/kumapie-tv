package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

class CardDefinitionTest {
    @Test fun dictionariesInPedrosOrder() {
        // The titles as the dictionaries have them (Daijirin/Sankoku keep their luna names, Spanish inside).
        assertEquals(listOf(0, 1, 2, 3, null, null), listOf("daijirin4-gpt-6-luna es-jp", "sankoku8-gpt-5.6-luna es-jp",
            "JMdict (Spanish) [2026-10-30]", "Jitendex.org [2026-10-03]", "daijirin4-gpt-6-luna en-jp", "JMdict (English)")
            .map(CardDefinition::rank))
    }
}
