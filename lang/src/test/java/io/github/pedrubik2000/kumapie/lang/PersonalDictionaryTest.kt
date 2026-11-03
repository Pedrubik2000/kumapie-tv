package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Segment
import io.github.pedrubik2000.kumapie.lang.PersonalDictionary.Companion.join
import io.github.pedrubik2000.kumapie.lang.PersonalDictionary.Companion.merge
import io.github.pedrubik2000.kumapie.lang.PersonalDictionary.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalDictionaryTest {
    private val cacao = Entry("ja", "可可阿", target = "カカオ")
    private fun seg(vararg s: String) = s.map { Segment(it, it.takeIf { w -> w != "。" }) }
    private fun ja(vararg e: Entry, s: List<Segment>) = join(e.toList(), s, false).map { it.text to it.word }

    @Test fun piecesBecomeOneWordKeyedByTheTarget() =
        assertEquals(listOf("可可阿" to "カカオ", "です" to "です", "。" to null), ja(cacao, s = seg("可", "可", "阿", "です", "。")))

    @Test fun anEntryCanCutAPiece() =
        assertEquals(listOf("X" to "X可", "可可阿" to "カカオ", "Y" to "阿Y"), ja(cacao, s = seg("X可", "可", "阿Y")))

    @Test fun insideOnePiece() =
        assertEquals(listOf("A" to "A可可阿B", "可可阿" to "カカオ", "B" to "A可可阿B"), ja(cacao, s = seg("A可可阿B")))

    @Test fun everyPlaceInALine() =
        assertEquals(listOf("可可阿" to "カカオ", "と" to "と", "可可阿" to "カカオ"), ja(cacao, s = seg("可", "可阿", "と", "可可阿")))

    @Test fun ownWordKeyedByItself() =
        assertEquals(listOf("壬氏" to "壬氏"), ja(Entry("ja", "壬氏", reading = "じんし", meaning = "Jinshi"), s = seg("壬", "氏")))

    @Test fun theLongerEntryWins() =
        assertEquals(listOf("可可阿" to "カカオ"), ja(Entry("ja", "可可", meaning = "x"), cacao, s = seg("可可阿")))

    @Test fun nothingToJoinLeavesTheLine() = seg("これ", "は").let { assertEquals(it, join(listOf(cacao), it, false)) }

    @Test fun spacedMatchesWholeWordsInAnyCase() {
        val e = Entry("de", "ab und zu", meaning = "now and then")
        val line = listOf(Segment("Ab", "ab"), Segment(" ", null), Segment("und", "und"), Segment(" ", null), Segment("zu", "zu"), Segment(" hab", "hab"))
        assertEquals(listOf("Ab und zu" to "ab und zu", " hab" to "hab"), join(listOf(e), line, true).map { it.text to it.word })
        assertEquals(listOf("Mann" to "mann"), join(listOf(Entry("de", "an", meaning = "at")), listOf(Segment("Mann", "mann")), true).map { it.text to it.word })
    }

    @Test fun newestWinsAndDeletionsStay() {
        val merged = merge(listOf(cacao.copy(time = 2)), listOf(cacao.copy(target = "old", time = 1), Entry("ja", "x", deleted = true, time = 5)))
        assertEquals(listOf("カカオ", ""), merged.map { it.target })
        assertEquals(listOf(false, true), merged.map { it.deleted })
    }
}
