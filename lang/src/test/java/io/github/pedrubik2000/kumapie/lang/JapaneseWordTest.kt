package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [WordCard.headword], one headword for the TV's and the phone's word cards. The words are the ones where the two apps
 * picked different headwords before (Sousou no Frieren episode 3, no-PC emulator, 2026-11-20): what each row and dictionary
 * entry below is, is what the device's Sudachi + Jiten + Yomitan dictionaries gave for that line.
 */
class JapaneseWordTest {
    private fun term(expression: String, reading: String) =
        YomitanDictionaries.Term(expression, reading, listOf(YomitanDictionaries.Glossary("JMdict", listOf("…"), "")), emptyList(), emptyList())
    private fun head(expression: String, reading: String) = Headword(expression, reading, listOf(term(expression, reading)))
    private fun row(written: String, reading: String, head: Headword?) = JapaneseLookup.Form(written, reading, head)
    private fun pick(forms: List<JapaneseLookup.Form>, reading: String = "", vararg entries: YomitanDictionaries.Term) =
        WordCard.headword(forms, reading) { entries.toList() }?.let { "${it.expression}[${it.reading}]" }

    /** The TV scanned from the tap (no lemma in a no-PC episode): conjugated forms, longer phrases or other words. */
    @Test fun theDictionaryFormOfTheWordItself() {
        assertEquals("来る[くる]", pick(listOf(row("来た", "きた", head("来る", "くる"))), "", term("来た", "きた")))   // お前を殺しに来た (TV: 来た)
        assertEquals("する[する]", pick(listOf(row("して", "して", head("する", "する")))))                                 // どうして？ (TV: して)
        assertEquals("強い[つよい]", pick(listOf(row("強い", "つよい", head("強い", "つよい")))))                            // 私 強いよ (TV: 強いる)
        assertEquals("街[まち]", pick(listOf(row("街", "まち", head("街", "まち"))), "", term("街", "がい")))                // いい街なんだ (TV: 街 がい)
        assertEquals("魔法[まほう]", pick(listOf(row("魔法", "まほう", head("魔法", "まほう")))))                            // 並な魔法使い (TV: 魔法使い)
        assertEquals("面会[めんかい]", pick(listOf(row("面会", "めんかい", head("面会", "めんかい")))))                      // 面会時間ではない (TV: 面会時間)
        assertEquals("は[は]", pick(listOf(row("は", "は", head("は", "は")))))                                             // 魔族はダメだね (TV: ハダ)
        assertEquals("アウラ[あうら]", pick(listOf(row("アウラ", "あうら", head("アウラ", "あうら")))))                     // TV: アウラ[アウラ]
    }

    /** No rating row covers Jiten's whole word (でしょ / です joined on): the phone had no headword, the TV the right one. */
    @Test fun theEpisodesDictionaryFormWhenNoRowCoversTheWord() {
        val morau = row("もらえるでしょ", "もらえるでしょ", null)
        assertEquals("貰える[もらえる]", pick(listOf(morau), "", term("貰える", "もらえる")))
        assertEquals("美味しい[おいしい]", pick(emptyList(), "", term("美味しい", "おいしい")))
        assertEquals("the episode's reading first", "私[わたし]", pick(emptyList(), "わたし", term("私", "わたくし"), term("私", "わたし")))
    }

    /** Names and counters no dictionary has whole: no headword (a card without a meaning), never the TV's shorter piece. */
    @Test fun neverAShorterPiece() {
        assertNull(pick(emptyList()))                                         // ドラート (TV: ドラー), グラナト (TV: グラ)
        assertNull(pick(listOf(row("２匹", "にひき", null))))                  // TV: ２ [ツー]
        assertNull(pick(listOf(row("魔力ごと", "まりょくごと", null))))         // TV: 魔力
    }
}
