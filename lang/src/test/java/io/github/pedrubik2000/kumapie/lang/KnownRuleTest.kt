package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One known rule for every card source: reviewed once = known, never reviewed = not (stability does not matter). */
class KnownRuleTest {
    private fun note(id: Long, word: String) = AnkiCards.Note(id, mapOf("Word" to word, "Sentence" to ""), emptySet())

    /** kuma3: FSRS stability (here 2 days, under the old 7-day line); stock AnkiDroid: none; own store: stability only once rated. */
    private val kuma3 = AnkiCards.Card(1, 1, true, 2.0, 0, type = 2)
    private val ankiDroid = AnkiCards.Card(2, 2, true, null, 0, type = 2)
    private val ankiDroidLearning = AnkiCards.Card(3, 3, true, null, 0, type = 1)
    private val never = AnkiCards.Card(4, 4, false, null, 0, type = 0)

    @Test fun parsedWordsFollowTheRule() {
        val morphs = mapOf(1L to setOf("a"), 2L to setOf("b"), 3L to setOf("c"), 4L to setOf("d"))
        val facts = KnownWords.factsOf(morphs, emptySet(), listOf(kuma3, ankiDroid, ankiDroidLearning, never))
        assertTrue(facts.getValue("a").reviewed) // kuma3, 2 days: known now
        assertTrue(facts.getValue("b").reviewed) // AnkiDroid, no stability
        assertTrue(facts.getValue("c").reviewed) // learning, answered once
        assertFalse(facts.getValue("d").reviewed) // new
        assertEquals(2.0, facts.getValue("a").best!!, 0.0) // the stability stays for display
    }

    @Test fun cardIndexFollowsTheRule() {
        val index = CardIndex()
        val notes = listOf(note(1, "kuma"), note(2, "droid"), note(4, "neu"))
        index.build(notes, listOf(kuma3, ankiDroid, never), mapOf(4L to "new"))
        assertEquals(listOf("droid", "kuma"), index.known(emptyList(), emptySet(), { false }, { null }))
    }

    @Test fun aNoteTaggedKnownManuallyIsKnownWithNewCards() {
        val tagged = AnkiCards.Note(5, mapOf("Word" to "tagged", "Sentence" to ""), setOf(KnownWords.KNOWN_MANUALLY))
        val dueTagged = AnkiCards.Note(6, mapOf("Word" to "duetagged", "Sentence" to ""), setOf(KnownWords.KNOWN_MANUALLY))
        val index = CardIndex()
        index.build(listOf(tagged, dueTagged, note(4, "neu")), listOf(AnkiCards.Card(5, 5, false, null, 0, type = 0), AnkiCards.Card(6, 6, false, null, 0, type = 0), never),
            mapOf(6L to "review", 4L to "new"))
        assertEquals("k", index.state("tagged"))
        assertEquals("d", index.state("duetagged")) // due still shows green
        assertEquals("b", index.state("neu")) // mined and not tagged: still blue
        assertEquals(listOf("tagged"), index.known(emptyList(), emptySet(), { false }, { null }).filter { it == "tagged" })
        assertEquals(setOf(5L, 6L), index.manualNotes())
    }
}
