package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

/** [CardIndex]: forms from Word fields, the green/white/red state, and what one rating answers. */
class CardIndexTest {
    private fun note(id: Long, word: String) = AnkiCards.Note(id, mapOf("Word" to word, "Sentence" to ""), emptySet())
    private fun card(id: Long, nid: Long, type: Int, ord: Int = 0) = AnkiCards.Card(id, nid, type != 0, null, 0, ord = ord, type = type)

    @Test fun states() {
        assertEquals("ist", CardIndex.form("<b>ist</b>[→ sein]"))
        assertEquals("rufe an", CardIndex.form("rufe an"))
        val index = CardIndex()
        index.build(
            listOf(note(1, "gehen[geht, ging, ist gegangen]"), note(2, "Tante"), note(3, "schlägt[→ schlagen]"), note(4, "")),
            listOf(card(10, 1, 2), card(20, 2, 2), card(21, 2, 2, ord = 1), card(30, 3, 1), card(40, 4, 2)),
            due = mapOf(20L to "review", 30L to "learn"),
        )
        assertEquals("k", index.state("gehen")) // studied, not due
        assertEquals("u", index.state("gehst")) // the exact form only: gehen's card doesn't count
        assertEquals("d", index.state("Tante")) // one of its cards is due
        assertEquals("n", index.state("schlägt")) // learning, even though it's in today's queue
        assertEquals(listOf(20L), index.toAnswer("tante").map { it.id }) // only the due one
        assertEquals(listOf(10L), index.toAnswer("gehen").map { it.id }) // none due: the first (early review)
        index.update(emptyList(), null)
        assertEquals(null, index.state("gehen")) // kuma3's queue unknown: old colours
    }
}
