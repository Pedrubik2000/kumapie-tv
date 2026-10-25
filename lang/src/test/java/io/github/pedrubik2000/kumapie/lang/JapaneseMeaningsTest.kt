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

    @Test fun jitendexSenseMeaningOnly() {
        assertEquals(listOf("to eat"), JapaneseLookup.bilingualMeanings("1-dan transitive; to eat; もっと果物を食べるべきです。; You should eat more fruit."))
        assertEquals(listOf("perfect game"), JapaneseLookup.bilingualMeanings("noun abbr. baseball; perfect game; See also パーフェクトゲーム; perfect game"))
        // Daijirin-style: the Japanese gloss after the English in the same piece.
        assertEquals(listOf("To gather; to collect; to harvest"), JapaneseLookup.bilingualMeanings("To gather; to collect; to harvest. 集める。採集する。収穫する。《取り入れる》"))
        assertEquals(listOf("to eat", "to live on (e.g. a salary); to live off"), JapaneseLookup.bilingualMeanings(
            "1-dan transitive; to eat; もっと果物を食べるべきです。; You should eat more fruit.; to live on (e.g. a salary); to live off; 僕は脚本家で食べていく。; I make a living as a playwright."))
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
