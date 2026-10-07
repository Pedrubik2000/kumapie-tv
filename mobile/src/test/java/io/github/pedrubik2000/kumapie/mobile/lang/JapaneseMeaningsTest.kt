package io.github.pedrubik2000.kumapie.mobile.lang

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

    @Test fun monolingualFirstRealDefinition() {
        assertEquals("終わり。最後。", JapaneseLookup.monolingualDefinition("㊀ （名）終わり。最後。；㊁ （副）つまるところ。"))
        assertEquals("いろいろな過程を経て、最後にいきつくところ。", JapaneseLookup.monolingualDefinition("いろいろな過程を経て、最後にいきつくところ。「一のところ、会えなかった」"))
        assertEquals(null, JapaneseLookup.monolingualDefinition("（名）；スル"))
    }

    @Test fun shortEntryAsItIs() {
        assertEquals(listOf("adv. just, at this moment"), JapaneseLookup.meanings("adv. just, at this moment"))
    }
}
