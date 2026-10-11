package io.github.pedrubik2000.kumapie.lang.jiten

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Paths

/** ParserScoring.kt checks; needs KUMAPIE_JITEN_LEXICON and KUMAPIE_SUDACHI_DIC (see JitenParserCasesTest). */
class ParserScoringTest {
    @Test fun compoundsAndHints() {
        val lexiconFile = System.getenv("KUMAPIE_JITEN_LEXICON")?.let(::File)
        val dic = System.getenv("KUMAPIE_SUDACHI_DIC")
        assumeTrue(lexiconFile != null && lexiconFile.exists() && dic != null)
        val lexicon = lexiconFile!!.inputStream().use { JitenLexicon.load(it) }
        val parser = JitenParser(lexicon, MorphologicalAnalyser(lexicon, Paths.get(dic!!), null))

        fun w(text: String, pos: PartOfSpeech, df: String = text, start: Int = -1) =
            WordInfo().apply { this.text = text; partOfSpeech = pos; dictionaryForm = df; startOffset = start }

        val words = listOf(w("役", PartOfSpeech.Noun), w("に", PartOfSpeech.Particle), w("立つ", PartOfSpeech.Verb))
        val hashes = LongArray(words.size) { hiraRollingHash(words[it].text) }
        val m = parser.tryMatchCompounds(words, 2, hashes)
        assertEquals(0, m?.startIndex)
        assertEquals("役に立つ", m?.dictionaryForm)

        // Partial hint ({開'あ}く) gets the okurigana appended.
        val hint = parser.findMatchingHint(arrayOf(FuriganaHint(5, 1, "あ")), w("開く", PartOfSpeech.Verb, start = 5))
        assertEquals(FuriganaHint(5, 2, "あく"), hint)
    }
}
