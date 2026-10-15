package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

/** [SensePick.bestMeaning]: the sense of a Japanese word that fits the line's English, or none. */
class SensePickTest {
    private val toru = listOf("to take; to pick up; to grab", "to pass; to hand; to give", "to get; to obtain; to acquire", "to pick; to gather; to harvest")

    @Test fun idiomDoesNotMatch() {
        // "Give me a break!" isn't 取る "to give"; "gathering" is.
        assertEquals(3, SensePick.bestMeaning(toru, "Give me a break! We were out gathering materials."))
        assertEquals(null, SensePick.bestMeaning(toru, "Give me a break!"))
    }

    @Test fun plainMatch() {
        assertEquals(0, SensePick.bestMeaning(listOf("to eat", "to live on (e.g. a salary)"), "I could eat it with mayonnaise."))
    }
}
