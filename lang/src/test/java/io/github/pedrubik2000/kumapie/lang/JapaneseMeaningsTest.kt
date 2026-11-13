package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

/** [JapaneseLookup.meanings]: whole dictionary entries cut into short senses for the summary and the mining choices. */
class JapaneseMeaningsTest {
    @Test fun jmdictEntryIntoSenses() {
        val entry = "ちょっと・ちょと【一寸・〈鳥渡〉・Ⓡ】; 〘adv〙; 1 〘uk〙 a little; a bit; slightly.; 2 〘uk〙 just a minute; for a moment; briefly.; " +
            "3 〘uk〙 somewhat; rather; fairly; pretty; quite.; 〘int〙; 5 〘uk〙 hey!; come on; excuse me."
        assertEquals(listOf("a little; a bit; slightly", "just a minute; for a moment; briefly", "somewhat; rather; fairly; pretty; quite",
            "hey!; come on; excuse me"), JapaneseLookup.meanings(entry))
    }

    @Test fun examplesDropped() {
        val entry = "ちょっと 1 (chotto); 1 〔時間が短いようす〕 (just) a minute; for a moment; a while ►ちょっとのぞく look briefly; " +
            "2 〔量や程度〕 a little; slightly ・1メートルとちょっと slightly over a meter"
        assertEquals(listOf("(chotto)", "〔時間が短いようす〕 (just) a minute; for a moment; a while", "〔量や程度〕 a little; slightly"),
            JapaneseLookup.meanings(entry))
    }

    @Test fun bilingualWithoutExamples() {
        assertEquals(listOf("conclusion; end"), JapaneseLookup.bilingualMeanings("2 conclusion; end.; →結局のところ"))
        assertEquals(emptyList<String>(), JapaneseLookup.bilingualMeanings("この結局はどうなることか"))
        assertEquals(emptyList<String>(), JapaneseLookup.bilingualMeanings("What will be the end of all this?"))
    }

    @Test fun spanishKeepsQuotedJapaneseAndDropsTheOrigin() {
        assertEquals(listOf("Lo mismo que «カカオの木»"),
            JapaneseLookup.bilingualMeanings("カカオ [1] 〖cacao〗; Lo mismo que «カカオの木». カカオの木 に同じ。; 〔「加加阿」とも書く〕"))
        assertEquals(listOf("El hiragana «あ»; el katakana «ア»"), JapaneseLookup.bilingualMeanings("El hiragana «あ»; el katakana «ア»"))
    }

    @Test fun anOriginIsTheMeaningOnlyWithoutAnother() {
        assertEquals(listOf("archives"), JapaneseLookup.bilingualMeanings("アーカイブス [4] 〖archives〗; アーカイブに同じ。"))
        assertEquals(listOf("Chal"), JapaneseLookup.bilingualMeanings("ショール [1] 〖shawl〗; Chal 肩掛け。"))
        assertEquals(emptyList<String>(), JapaneseLookup.bilingualMeanings("ウエスト 〖腰〗; 腰のこと。"))
    }

    @Test fun noPartOfSpeechMarksOrArrows() {
        assertEquals(listOf("Trapos viejos"), JapaneseLookup.bilingualMeanings("ウエスト [2] 〖waste〗; （名 sust.）スル; ① Trapos viejos. ぼろ布。"))
        assertEquals(emptyList<String>(), JapaneseLookup.bilingualMeanings("ああ （副 adv.）; ああ言う。"))
        assertEquals(listOf("Abreviatura de «ソフトボール①»"), JapaneseLookup.bilingualMeanings("Abreviatura de «ソフトボール①». ←"))
        assertEquals(listOf("Nieto de Amaterasu"), JapaneseLookup.bilingualMeanings("◉ Nieto de Amaterasu"))
    }

    @Test fun jitendexSenseMeaningOnly() {
        assertEquals(listOf("to eat"), JapaneseLookup.bilingualMeanings("1-dan transitive; to eat; もっと果物を食べるべきです。; You should eat more fruit."))
        assertEquals(listOf("perfect game"), JapaneseLookup.bilingualMeanings("noun abbr. baseball; perfect game; See also パーフェクトゲーム; perfect game"))
        // Daijirin-style: the Japanese gloss after the English in the same piece.
        assertEquals(listOf("To gather; to collect; to harvest"), JapaneseLookup.bilingualMeanings("To gather; to collect; to harvest. 集める。採集する。収穫する。《取り入れる》"))
        assertEquals(listOf("to eat", "to live on (e.g. a salary); to live off"), JapaneseLookup.bilingualMeanings(
            "1-dan transitive; to eat; もっと果物を食べるべきです。; You should eat more fruit.; to live on (e.g. a salary); to live off; 僕は脚本家で食べていく。; I make a living as a playwright."))
    }

    /** Bee's Character Dictionary entries, as its zip has them (2026-11-02), without the details. */
    private fun bee(name: String, romaji: String, honorific: String? = null) = "[{\"content\":[" +
        (honorific?.let { "{\"content\":[{\"content\":\"$it\",\"tag\":\"span\"},{\"content\":\" — Kana form of 様 — very formal/respectful\",\"tag\":\"span\"}],\"tag\":\"div\"}," } ?: "") +
        "{\"content\":\"$name\",\"data\":{\"id\":\"name\"},\"tag\":\"div\"},{\"content\":\"$romaji\",\"data\":{\"id\":\"name-romaji\"},\"tag\":\"div\"}," +
        "{\"content\":\"From: 薬屋のひとりごと\",\"data\":{\"id\":\"source\"},\"tag\":\"div\"}," +
        "{\"content\":{\"content\":\"Main Character\",\"data\":{\"id\":\"role\"},\"tag\":\"span\"},\"data\":{\"id\":\"role-container\"},\"tag\":\"div\"}],\"type\":\"structured-content\"}]"

    @Test fun characterMeaningIsTheName() {
        // The word card said "Jinshi; From:; Main Character" and, with an honorific, "From:; Main Character".
        for (entry in listOf(bee("壬氏", "Jinshi"), bee("壬氏", "Jinshi", "様")))
            assertEquals(listOf("Jinshi"), YomitanDictionaries.senses(entry).flatMap(JapaneseLookup::bilingualMeanings))
        assertEquals("Jinshi" to null, YomitanDictionaries.character(bee("壬氏", "Jinshi")))
        assertEquals("Jinshi" to "様", YomitanDictionaries.character(bee("壬氏", "Jinshi", "様")))
        // A name that is also a tag.
        assertEquals(listOf("Kana"), YomitanDictionaries.senses(bee("佳奈", "Kana")).flatMap(JapaneseLookup::bilingualMeanings))
        // Any other dictionary's entry.
        assertEquals(null, YomitanDictionaries.character("[\"to eat\"]"))
        assertEquals(listOf("to eat"), YomitanDictionaries.senses("[\"to eat\"]"))
    }

    @Test fun characterFromTheBooksWork() {
        val jinshi = bee("壬氏", "Jinshi")
        assertEquals(true, YomitanDictionaries.fromWork(jinshi, "薬屋のひとりごと 12")) // volume number, space
        assertEquals(true, YomitanDictionaries.fromWork(jinshi, "薬屋のひとりごと～猫猫の後宮謎解き手帳～")) // the manga's subtitle
        assertEquals(false, YomitanDictionaries.fromWork(jinshi, "くま クマ 熊 ベアー17")) // the bear クマ got another show's face
        assertEquals(false, YomitanDictionaries.fromWork(jinshi, "1")) // too short to say
    }

    @Test fun popupTopPicture() {
        val gaiji = "<img class=\"gloss-image\" src=\"kumapie-media://media/d/res/font/a.png\" style=\"width:1.0em;height:1.0em;\">"
        val photo = "<img class=\"gloss-image\" src=\"kumapie-media://media/d/img/asagao.webp\" alt=\"朝顔\">"
        assertEquals("kumapie-media://media/d/img/asagao.webp", GlossaryHtml.firstPicture("【${gaiji}朝顔】$photo"))
        assertEquals(null, GlossaryHtml.firstPicture("【${gaiji}朝顔】 no picture"))
    }

    @Test fun characterNamesJoined() {
        fun seg(vararg s: String) = s.map { io.github.pedrubik2000.kumapie.data.Segment(it, if (it == "、") null else it) }
        val names = mapOf("壬氏様" to "壬氏", "壬氏" to "壬氏", "そうた" to "そうた")
        fun join(vararg s: String) = JapaneseLookup.joinNames(seg(*s)) { names[it] }.map { it.text to it.word }
        // The longest name, keyed without its honorific; the rest of the line as it was.
        assertEquals(listOf("壬氏様" to "壬氏", "の" to "の"), join("壬", "氏", "様", "の"))
        assertEquals(listOf("壬氏" to "壬氏", "は" to "は"), join("壬", "氏", "は"))
        // Not across what isn't a word, not from hiragana, a word alone stays.
        assertEquals(listOf("壬" to "壬", "、" to null, "氏" to "氏"), join("壬", "、", "氏"))
        assertEquals(listOf("そう" to "そう", "た" to "た"), join("そう", "た"))
        assertEquals(listOf("壬" to "壬"), join("壬"))
    }

    @Test fun englishSankokuMeaning() {
        // 湯切り as the English Sankoku's glossary flattens: the mining sheet had no meaning (2026-10-25).
        assertEquals(listOf("The act of thoroughly draining hot water from noodles (such as ramen) using a colander a…"),
            JapaneseLookup.bilingualMeanings("ゆ きり ［湯切 り ］ ｟名 ・他サ ｠; 〘料 〙 The act of thoroughly draining〔＝よけいな水けを取り去る〕 " +
                "hot water from noodles (such as ramen) using a colander and the like after boiling them. ゆぎり。 " +
                "（ラーメンなどの）めんをゆでたあと、ざるなどで湯をしっかりと切る〔＝よけいな水けを取り去る〕 こと。ゆぎり。; 「━ があまいと、スープが うすまる ・カップ焼きそばの━ 口(ぐち) 」"))
    }

    @Test fun englishDaijirinMeaning() {
        // 行軍: "（名 ） スル" before the English was taken for an example, the English for its translation ("a march" only).
        assertEquals(listOf("The movement of an army over a long distance on foot; a march"), JapaneseLookup.bilingualMeanings(
            "こう ぐん (かう—) [0] 【行軍 】; （名 ） スル; The movement of an army over a long distance on foot; a march. 軍隊が徒歩で長距離を移動すること。; 「隊伍を組んで━ する」"))
    }

    @Test fun monolingualFirstRealDefinition() {
        assertEquals("終わり。最後。", JapaneseLookup.monolingualDefinition("㊀ （名）終わり。最後。；㊁ （副）つまるところ。"))
        assertEquals("いろいろな過程を経て、最後にいきつくところ。", JapaneseLookup.monolingualDefinition("いろいろな過程を経て、最後にいきつくところ。「一のところ、会えなかった」"))
        assertEquals(null, JapaneseLookup.monolingualDefinition("（名）；スル"))
    }

    @Test fun shortEntryAsItIs() {
        assertEquals(listOf("adv. just, at this moment"), JapaneseLookup.meanings("adv. just, at this moment"))
    }
}
