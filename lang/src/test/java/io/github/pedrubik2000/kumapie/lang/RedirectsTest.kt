package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.Glossary
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Redirects] (core plan step 12) on real entries: Jitendex 2026's redirect-glossary entries 勞働相 (old kanji form, with
 * its "redirected from" item and a reading), あっかんべ (kana spelling) and モノづくり (a link only), and JMdict Fanshi's
 * 轉送 ("【轉送】\n.", no meaning, no target). Targets shortened to their first sense (JMdict data, CC BY-SA 4.0, EDRDG).
 */
class RedirectsTest {
    private val jitendex = "Jitendex.org [2026-10-03]"
    private val fanshi = "JMdict Fanshi"

    private fun redirect(to: String, word: String, reading: String? = null, from: Boolean = true): String {
        val enc = { x: String -> java.net.URLEncoder.encode(x, "UTF-8") }
        val q = "?query=" + enc(to) + "&wildcards=off" + (reading?.let { "&primary_reading=" + enc(it) } ?: "")
        val sc = """{"type":"structured-content","content":{"tag":"div","lang":"ja","data":{"content":"redirect-glossary"},"content":["⟶",{"tag":"a","href":"$q","content":"$to"}]}}"""
        return "[" + sc + (if (from) """,["$to",["redirected from $word"]]""" else "") + "]"
    }

    private fun term(word: String, reading: String, vararg gs: Pair<String, String>) =
        Term(word, reading, gs.map { (d, raw) -> Glossary(d, YomitanDictionaries.senses(raw), "", YomitanDictionaries.formOf(raw), raw) }, emptyList(), emptyList())

    private fun meaning(text: String) =
        """[{"type":"structured-content","content":[{"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"$text"}}]}]"""

    private val dict = mapOf(
        "労働相" to listOf(term("労働相", "ろうどうしょう", jitendex to meaning("Minister of Labor (Labour)"))),
        "あかんべえ" to listOf(term("あかんべえ", "あかんべえ", jitendex to meaning("pulling down one's lower eyelid and sticking out one's tongue"))),
        "物作り" to listOf(term("物作り", "ものづくり", jitendex to meaning("manufacturing")), term("物作り", "ものつくり", jitendex to meaning("farming"))),
        "転送" to listOf(term("転送", "てんそう", jitendex to meaning("transfer (of a call, patient, etc.)"))),
    )
    private val lookup = { w: String -> dict[w].orEmpty() }

    @Test fun targets() {
        assertEquals("労働相" to "ろうどうしょう", Redirects.target(redirect("労働相", "勞働相", "ろうどうしょう")))
        assertEquals("あかんべえ" to null, Redirects.target(redirect("あかんべえ", "あっかんべ")))
        assertNull(Redirects.target(meaning("manufacturing")))
    }

    @Test fun followsOneHopInTheSameDictionary() {
        for ((word, to, reading, sense) in listOf(
            listOf("勞働相", "労働相", "ろうどうしょう", "Minister of Labor (Labour)"),
            listOf("あっかんべ", "あかんべえ", null, "pulling down one's lower eyelid and sticking out one's tongue"),
        )) {
            val t = Redirects.follow(listOf(term(word!!, "", jitendex to redirect(to!!, word, reading))), lookup).single()!!
            assertEquals(listOf(sense), t.glossaries.single().senses)
            assertTrue(t.glossaries.single().raw, "→ $to" in GlossaryHtml.glossary(t.glossaries.single().raw, jitendex))
            assertTrue(sense!! in GlossaryHtml.glossary(t.glossaries.single().raw, jitendex).replace("&#39;", "'"))
        }
    }

    @Test fun linkOnlyPicksTheReading() {
        val t = Redirects.follow(listOf(term("モノづくり", "ものづくり", jitendex to redirect("物作り", "モノづくり", "ものづくり", from = false))), lookup).single()!!
        assertEquals(listOf("manufacturing"), t.glossaries.single().senses)
    }

    @Test fun noMeaningIsLeftOutWhenAnotherDictionaryHasOne() {
        val t = Redirects.follow(listOf(term("轉送", "", jitendex to redirect("転送", "轉送", "てんそう"), fanshi to """["【轉送】\n."]""")), lookup).single()!!
        assertEquals(listOf(jitendex), t.glossaries.map { it.dict })
        assertEquals(listOf("transfer (of a call, patient, etc.)"), t.glossaries.single().senses)
    }

    @Test fun noTargetAndNothingElseStays() {
        val alone = term("轉送", "", fanshi to """["【轉送】\n."]""")
        assertEquals(listOf(alone), Redirects.follow(listOf(alone), lookup))
        val nowhere = term("勞働相", "", jitendex to redirect("労働相", "勞働相"))
        assertEquals(listOf(nowhere), Redirects.follow(listOf(nowhere)) { emptyList() })
    }

    @Test fun plainEntriesUntouched() {
        val plain = dict.getValue("労働相")
        assertTrue(Redirects.follow(plain) { error("no lookup") } === plain)
    }
}
