package io.github.pedrubik2000.kumapie.lang

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [JitenSplitter] on real lines: whole words (Jiten's), keys by dictionary form, the text kept whole. Runs only with
 * KUMAPIE_SUDACHI_DIC and KUMAPIE_JITEN_LEXICON set (KUMAPIE_SUDACHI_USER: Jiten's user dictionary), as
 * jiten/JitenParserCasesTest.
 */
class JitenSplitterTest {
    private val splitter: JitenSplitter? by lazy {
        val dic = System.getenv("KUMAPIE_SUDACHI_DIC")?.let(::File)
        val lexicon = System.getenv("KUMAPIE_JITEN_LEXICON")?.let(::File)
        if (dic == null || !dic.exists() || lexicon == null || !lexicon.exists()) null
        else JitenSplitter(lexicon, dic, System.getenv("KUMAPIE_SUDACHI_USER")?.takeIf { it.isNotBlank() }?.let(::File))
    }

    /** "surface→key" per word ("surface" when they are the same), non-words as [text]. */
    private fun show(text: String): String {
        val tokens = splitter!!.split(text)
        assertEquals(text, tokens.joinToString("") { it.surface }) // nothing dropped, in order
        tokens.forEach { assertEquals(it.surface, text.substring(it.begin, it.begin + it.surface.length)) }
        return tokens.joinToString(" | ") { t ->
            if (!t.isWord) "[${t.surface}]" else if (t.surface == t.base) t.surface else "${t.surface}→${t.base}"
        }.also { println("$text  =>  $it  (${tokens.filter { it.isWord }.joinToString(" ") { it.reading }})") }
    }

    @Test fun lines() {
        assumeTrue(splitter != null)
        assertEquals("黒板消し | は | 普通 | [。]", show("黒板消しは普通。"))
        assertEquals("それじゃ | 元も子もなくなる→元も子もない | ぞ", show("それじゃ元も子もなくなるぞ"))
        assertEquals("雨が降りそう→雨が降る | な | 気がします→気がする", show("雨が降りそうな気がします"))
        assertEquals("呼ばれちゃった→呼ぶ | [！]", show("呼ばれちゃった！"))
    }

    @Test fun resplit() {
        assumeTrue(splitter != null)
        val ep = JSONObject("""{"scenes": [{"german": true, "cues": [{"seg": [["黒板", "黒板"], ["消し", "消す"], ["は", "は"], ["普通", "普通"], ["。", null]]}]}],
            "words": {"黒板": {"s": "u", "g": ""}, "普通": {"s": "k", "g": "normal", "lemma": "x"}}}""")
        resplitEpisode(ep, splitter!!)
        assertEquals("""[["黒板消し","黒板消し"],["は","は"],["普通","普通"],["。",null]]""",
            ep.getJSONArray("scenes").getJSONObject(0).getJSONArray("cues").getJSONObject(0).getJSONArray("seg").toString())
        val words = ep.getJSONObject("words")
        assertEquals(setOf("黒板消し", "は", "普通"), words.keys().asSequence().toSet())
        assertEquals("""{"s":"u","g":""}""", words.getJSONObject("黒板消し").toString())
        assertEquals("normal", words.getJSONObject("普通").getString("g")) // a surviving key keeps its entry
        assertEquals("x", words.getJSONObject("普通").getString("lemma"))
        assertEquals(JitenSplitter.ID, ep.getString("splitter"))
    }
}
