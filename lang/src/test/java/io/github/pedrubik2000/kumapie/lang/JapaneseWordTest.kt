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
    private fun term(expression: String, reading: String, tags: String = "") =
        YomitanDictionaries.Term(expression, reading, listOf(YomitanDictionaries.Glossary("JMdict", listOf("…"), tags)), emptyList(), emptyList())
    private fun head(expression: String, reading: String) = Headword(expression, reading, listOf(term(expression, reading)))
    private fun row(written: String, reading: String, head: Headword?) = JapaneseLookup.Form(written, reading, head)
    private fun pick(forms: List<JapaneseLookup.Form>, reading: String = "", vararg entries: YomitanDictionaries.Term) =
        WordCard.headword(forms, reading, forms.firstOrNull()?.written ?: "", "") { entries.toList() }?.let { "${it.expression}[${it.reading}]" }

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

    /**
     * Core plan 5e: the JMdict entries the no-PC emulator's dictionaries gave (JMdict + JMnedict, 2026-11-21) for a word
     * with お/ご or an honorific, and the words without them. Words not listed: none.
     */
    private val jmdict = mapOf(
        "ご" to listOf(term("ご", "ご"), term("語", "ご"), term("碁", "ご"), term("五", "ご")),
        "お" to listOf(term("お", "お"), term("尾", "お"), term("緒", "お"), term("御", "お")),
        "よ" to listOf(term("よ", "よ"), term("世", "よ"), term("予", "よ")),
        "ひいき" to listOf(term("贔屓", "ひいき", "⭐ ichi"), term("贔負", "ひいき")),
        "弟子" to listOf(term("弟子", "ていし"), term("弟子", "でし", "⭐ ichi news5k")),
        "ご飯" to listOf(term("ご飯", "ごはん", "⭐ ichi")), "ごめん" to listOf(term("御免", "ごめん", "⭐ ichi")),
        "ご苦労さま" to listOf(term("ご苦労さま", "ごくろうさま")), "お疲れ様" to listOf(term("お疲れ様", "おつかれさま")),
        "お客さん" to listOf(term("お客さん", "おきゃくさん")), "ご存じ" to listOf(term("ご存じ", "ごぞんじ")),
        "ご覧" to listOf(term("ご覧", "ごらん")), "お休み" to listOf(term("お休み", "おやすみ")), "休み" to listOf(term("休み", "やすみ")),
        "おつもり" to listOf(term("お積もり", "おつもり"), term("お積り", "おつもり")), "つもり" to listOf(term("心算", "つもり")),
        "待ち" to listOf(term("待ち", "まち")), "納め" to listOf(term("納め", "おさめ")),
    )
    private fun ja(word: String) = jmdict[word].orEmpty()

    /** The word card's headword for [written] as the line has it, [word] its dictionary form, the rows [forms]. */
    private fun card(written: String, word: String, forms: List<JapaneseLookup.Form> = emptyList()) =
        WordCard.headword(forms, "", written, word, ::ja)?.let { "${it.expression}[${it.reading}]" }

    /**
     * The three real cases: the TV's picked word ごひいきさん / お弟子 showed the kana ご / お's entry (its dictionary form was
     * the one kana a scan from the tap finds), the phone's word card フリー for フリーレン where Mine had none.
     */
    @Test fun theRealCases() {
        assertEquals("贔屓[ひいき]", card("ごひいきさん", "ご"))                    // 何でしょう？ごひいきさん？切符なら表ですよ (TV: ご)
        assertEquals("弟子[でし]", card("お弟子", "お"))                            // 八雲師匠はお弟子さんは一切… (TV: お)
        assertEquals("贔屓[ひいき]", card("ごひいきさん", "ひいき", listOf(row("ひいきさん", "ひいきさん", null)))) // the rows' word, Jiten's key
        assertNull(card("フリーレン", "フリーレン", listOf(row("フリーレン", "ふりーれん", null))))          // Sousou no Frieren 5 line 1 (phone: フリー)
    }

    /** Without the honorific, without お/ご, without both; words that are entries with their ご/お stay whole. */
    @Test fun goAndOWords() {
        assertEquals("弟子[でし]", card("お弟子さん", "弟子"))
        for ((word, want) in listOf("ご飯" to "ご飯[ごはん]", "ごめん" to "御免[ごめん]", "ご苦労さま" to "ご苦労さま[ごくろうさま]",
                "お疲れ様" to "お疲れ様[おつかれさま]", "お客さん" to "お客さん[おきゃくさん]", "ご存じ" to "ご存じ[ごぞんじ]", "ご覧" to "ご覧[ごらん]"))
            assertEquals(word, want, card(word, word))
        assertEquals(listOf("ごひいきさん", "ごひいき", "ひいきさん", "ひいき"), JapaneseLookup.tries("ごひいきさん"))
        assertEquals(listOf("フリーレン"), JapaneseLookup.tries("フリーレン"))
    }

    /** "Sometimes": the Apothecary Diaries 1-12's お words where the rows (Jiten's 休む, つもり, お) missed (emulator, 2026-11-21). */
    @Test fun theSometimesCases() {
        assertEquals("お休み[おやすみ]", card("お休み", "休む"))          // 少しお休みください (was 休む: お休み is an entry with its お)
        assertEquals("お積もり[おつもり]", card("おつもり", "つもり"))     // どういう おつもりですか? (was 心算)
        assertNull(card("おぉ", "お"))                                     // ん？ おぉ 嬢ちゃん。 (was お: one kana of a longer word)
        assertNull(card("およ", "よ"))                                     // およよよ… (was よ)
        // A conjugated verb with お stays with the rows (お待ちください: 待つ, お納め: 収める), not the noun 待ち / 納め.
        assertNull(JapaneseLookup.whole("お待ち", "待つ", ::ja))
        assertNull(JapaneseLookup.whole("お納め", "収める", ::ja))
    }

    /** Core plan 5b: the readings the emulator's JMdict has (2026-11-21); [freq]: JPDB's ranks, as a frequency dictionary gives them. */
    private val readings = mapOf(
        "私" to "あたい あたくし あたし あっし あて し わい わし わたい わたくし わたし わちき わっし わっち わて わらわ", "明日" to "あした あす みょうにち",
        "今日" to "きょう こんじつ こんち こんにち", "何" to "なに なん", "日本" to "にっぽん にほん", "一日" to "いちじつ いちにち いっぴ ついたち つきたち ひとひ",
        "館" to "かん たち やかた", "方" to "かた がた ほう", "翡翠" to "かわせみ ひすい", "呪い" to "のろい まじない", "上" to "うえ うわ かみ じょう",
    )
    private fun terms(word: String, freq: Map<String, String> = emptyMap()) = readings.getValue(word).split(' ').map { r ->
        YomitanDictionaries.Term(word, r, listOf(YomitanDictionaries.Glossary("JMdict", listOf("…"), "")), listOfNotNull(freq[r]?.let { "JPDB" to it }), emptyList())
    } + term(word, word) // JMnedict's, its reading the spelling
    /** The reading of [word] in [line] (its first place there), Sudachi read it [sudachi]. */
    private fun read(line: String, word: String, sudachi: String, freq: Map<String, String> = emptyMap()) =
        JapaneseLookup.reading(line, line.indexOf(word), word, sudachi, terms(word, freq))

    /** Sudachi's reading is one per spelling (私 always わたくし): the line's, else the common one. Real lines (Apothecary Diaries, Frieren). */
    @Test fun watashiReadsWatashi() {
        assertEquals("わたし", read("私は 薬屋です", "私", "わたくし"))
        assertEquals("あした", read("明日は雨", "明日", "あす"))
        assertEquals("きょう", read("今日は何のご用で？", "今日", "きょう"))
        assertEquals("なん", read("何でしょう？ごひいきさん？", "何", "なん"))
        assertEquals("なん", read("今日は何のご用で？", "何", "なん"))
        assertEquals("なに", read("私は何をすればいいの？", "何", "なん"))
        assertEquals("なに", read("何か書けば", "何", "なん"))
        assertEquals("にほん", read("日本に行く", "日本", "にっぽん"))
        assertEquals("いちにち", read("一日休む", "一日", "ついたち"))
        assertEquals("ついたち", read("四月一日に", "一日", "ついたち"))
    }

    /** A frequency dictionary on the device (JPDB's ranks, ㋕ = written in kana): its common reading, Sudachi's unless far rarer. */
    @Test fun theFrequencyDictionarysReading() {
        assertEquals("わたし", read("私は 薬屋です", "私", "わたくし", mapOf("わたし" to "32, 1308㋕", "わたくし" to "28801, 3050㋕")))
        assertEquals("かた", read("高貴な方と", "方", "かた", mapOf("ほう" to "117, 530㋕", "かた" to "304, 4189㋕")))
        assertEquals("unranked: Sudachi's", "ひすい", read("翡翠宮の侍女", "翡翠", "ひすい", mapOf("かわせみ" to "19555")))
        assertEquals("unranked, a common word", "あした", read("明日は雨", "明日", "あす", mapOf("あした" to "547, 8592㋕")))
        assertEquals("inside a word: Sudachi's", "かん", read("緑青館に薬を", "館", "かん", mapOf("やかた" to "4908", "かん" to "5903")))
    }

    /** Core plan 5f: with JPDB v2.2 installed (its ranks for these readings, as hoshidicts gives them) the 5b list holds. */
    @Test fun withJpdbTheFiveBListHolds() {
        val jpdb = mapOf("私" to mapOf("わたし" to "32, 1308㋕", "わたくし" to "3050㋕, 28801", "し" to "26101", "わし" to "3093㋕, 175701"),
            "明日" to mapOf("あした" to "547, 8592㋕"), "今日" to mapOf("きょう" to "122, 6099㋕"), "何" to mapOf("なに" to "72, 493㋕", "なん" to "101, 151㋕"),
            "日本" to mapOf("にほん" to "803, 49084㋕"), "一日" to mapOf("いちにち" to "1098, 76696㋕", "ついたち" to "25701, 93300㋕"))
        fun jp(line: String, word: String, sudachi: String) = read(line, word, sudachi, jpdb.getValue(word))
        assertEquals("わたし", jp("私は 薬屋です", "私", "わたくし"))
        assertEquals("あした", jp("明日は雨", "明日", "あす"))
        assertEquals("きょう", jp("今日は何のご用で？", "今日", "きょう"))
        assertEquals("なん", jp("何でしょう？ごひいきさん？", "何", "なん"))
        assertEquals("なに", jp("私は何をすればいいの？", "何", "なん"))
        assertEquals("にほん", jp("日本に行く", "日本", "にっぽん"))
        assertEquals("いちにち", jp("一日休む", "一日", "ついたち"))
        assertEquals("ついたち", jp("四月一日に", "一日", "ついたち"))
    }

    /** Cards mined as 私[わたくし] move to 私[わたし], once: their key is the rows' again, the card id (its ratings) the same. */
    @Test fun theWatashiCardsMove() {
        assertEquals("私[わたし]:0-", CardIndex.moved("私[わたくし]:0-"))
        assertNull(CardIndex.moved("私[わたし]:0-"))
        assertNull(CardIndex.moved("私室[ししつ]"))
        val note = AnkiCards.Note(1, mapOf("Word" to "私[わたくし]:0-"), emptySet())
        assertEquals(listOf(CardIndex.japaneseKey("私", "わたし"), "私"), CardIndex.japaneseKeys(note))
    }

    private val jpdb = mapOf("のろい" to "3165, 60685㋕", "まじない" to "52702, 25411㋕", "うえ" to "142, 2329㋕", "うわ" to "94801, 53102㋕",
        "なに" to "72, 493㋕", "なん" to "101, 151㋕")

    /**
     * The three holes Pedro's real cards showed (core plan 5b): 呪い[まじない] from 「まじないを唱える声」 (Kusuriya 3, 5) and
     * 上[うわ]の 空[そら] from 「…肝心のデートも上の空である」 stay as they are, with a frequency dictionary (JPDB) on the device.
     */
    @Test fun pedrosCardsKeepTheirReading() {
        // (c) the line writes the word in kana: that is its reading, not the kanji spelling's common のろい.
        assertEquals("まじない", JapaneseLookup.reading("まじないを唱える声", 0, "呪い", "まじない", terms("呪い", jpdb)))
        assertEquals("written in kanji: the common one", "のろい", read("呪いをかける", "呪い", "まじない", jpdb))
        // (a) the expression the line has (上の空, an entry) is the first row, before 上 gets its own reading (うえ).
        val line = "現にここまで失敗続きで肝心のデートも上の空である"
        assertEquals("うえ", read(line, "上", "うえ", jpdb))
        val sora = row("上の空", "うわのそら", head("上の空", "うわのそら"))
        val rows = listOf(row("上", "うえ", head("上", "うえ")), sora)
        assertEquals(sora, JapaneseLookup.entryFirst(rows, "上の空", sora, 1).first())
        assertEquals("気にしない is 気にする conjugated: rows as they were", rows,
            JapaneseLookup.entryFirst(rows, "気にしない", row("気にする", "きにする", null), 1))
        // (b) a word with a card of its own keeps the card's reading when the line doesn't decide; the line rules still win.
        val cards = setOf(CardIndex.japaneseKey("呪い", "まじない"), CardIndex.japaneseKey("上", "うわ"), CardIndex.japaneseKey("何", "なに"))
        assertEquals("まじない", JapaneseLookup.reading("呪いをかける", 0, "呪い", "のろい", terms("呪い", jpdb)) { it in cards })
        assertEquals("うわ", JapaneseLookup.reading(line, line.indexOf("上"), "上", "うえ", terms("上", jpdb)) { it in cards })
        assertEquals("the table loses too", "あす", JapaneseLookup.reading("明日は雨", 0, "明日", "あした", terms("明日")) { it == CardIndex.japaneseKey("明日", "あす") })
        assertEquals("the line decides: なん", "なん", JapaneseLookup.reading("何でしょう？", 0, "何", "なん", terms("何", jpdb)) { it in cards })
    }

    /** Way 3 (Pedro, 2026-11-21): the reading the person picked wins over the line, a card and the frequency; hold OK / a tap goes round them. */
    @Test fun aPickedReadingWins() {
        assertEquals("なに", JapaneseLookup.reading("何でしょう？", 0, "何", "なん", terms("何", jpdb), chosen = "なに"))
        assertEquals("わたくし", JapaneseLookup.reading("私は 薬屋です", 0, "私", "わたし", terms("私"), "わたくし") { it == CardIndex.japaneseKey("私", "わたし") })
        assertEquals("not one of its readings: ignored", "わたし", JapaneseLookup.reading("私は 薬屋です", 0, "私", "わたくし", terms("私"), chosen = "ぼく"))
        assertEquals("one reading: nothing to pick (no label; hold OK replays the line)", 1,
            JapaneseLookup.readings("上の空", listOf(term("上の空", "うわのそら"), term("上の空", "上の空"))).size)
        val asu = JapaneseLookup.readings("明日", terms("明日"))
        assertEquals(listOf("あした", "あす", "みょうにち"), asu)
        assertEquals("あす", JapaneseLookup.next(asu, "あした"))
        assertEquals("wraps round", "あした", JapaneseLookup.next(asu, "みょうにち"))
        assertEquals("あした", JapaneseLookup.next(asu, ""))
    }
}
