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
        assertEquals("wenn's", CardIndex.form("Wenn&#39;s[→ wenn]")) // mined words are HTML-escaped
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

    @Test fun japanese() {
        fun keys(word: String, reading: String? = null) = CardIndex.japaneseKeys(AnkiCards.Note(1,
            mapOf("Word" to word) + (reading?.let { mapOf("Reading" to it) } ?: emptyMap()), emptySet()))
        assertEquals(listOf("私\tわたし", "私"), keys("私[わたし]:0-"))
        assertEquals(listOf("日本語\tにほんご", "日本語"), keys("日本[にほん] 語[ご]:0-"))
        assertEquals(listOf("人\tひと", "人"), keys("人[*ひと]:2-"))
        assertEquals(listOf("告白\tこくはく", "告白"), keys("告[こ*く] 白[はく]:0-"))
        assertEquals(listOf("拾う\tひろう", "拾う"), keys("拾[ひろ]\\う:0"))
        assertEquals(listOf("新しい\tあたらしい", "新しい"), keys("新[あたら]し\\い:k4"))
        assertEquals(listOf("気に入る\tきにいる", "気に入る"), keys("気[き]:0 に / 入[い]る:0"))
        assertEquals(listOf("ほぼ\tほぼ", "ほぼ"), keys("ほぼ:1 / -"))
        assertEquals(listOf("届く\tとどく", "届く"), keys("届く[とどく]:0-")) // kumapie's mined words
        assertEquals(listOf("気にしない\tきにしない", "気にしない"), keys("気にしない[きにしない]"))
        assertEquals(listOf("テレビ\tてれび", "テレビ"), keys("テレビ"))
        assertEquals(listOf("船\tふね", "船"), keys("船", "ふね")) // JPDB: Word + Reading
        assertEquals(emptyList<String>(), keys(""))

        val index = CardIndex()
        index.build(listOf(AnkiCards.Note(1, mapOf("Word" to "上手[じょうず]"), emptySet()), AnkiCards.Note(2, mapOf("Word" to "上手[うわて]"), emptySet())),
            listOf(card(10, 1, 2), card(20, 2, 0)), due = mapOf(10L to "review"), keys = CardIndex::japaneseKeys)
        assertEquals("d", index.state("上手\tじょうず"))
        assertEquals("n", index.state("上手\tうわて")) // another word: same spelling, other reading
        assertEquals(listOf(20L), index.toAnswer("上手\tうわて").map { it.id })
        assertEquals("d", index.state("上手")) // the subtitle colour: any reading
        assertEquals("u", index.state(CardIndex.japaneseKey("上手い", "うまい")))
    }
}
