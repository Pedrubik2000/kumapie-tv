package io.github.pedrubik2000.kumapie.lang.jiten

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The lexicon from tools/kumapie/jiten_lexicon.py. Runs only when KUMAPIE_JITEN_LEXICON points at jiten-lexicon.tsv.gz
 * (C:\dojo\work\jiten-port\lexicon); prints the load time and the heap it takes.
 *   gradlew :lang:testDebugUnitTest --tests '*JitenLexiconTest*'
 */
class JitenLexiconTest {
    companion object {
        private var lexicon: JitenLexicon? = null

        @BeforeClass @JvmStatic fun load() {
            val file = System.getenv("KUMAPIE_JITEN_LEXICON")?.let(::File)?.takeIf { it.exists() } ?: return
            val rt = Runtime.getRuntime()
            System.gc()
            val before = rt.totalMemory() - rt.freeMemory()
            val t = System.nanoTime()
            val lex = file.inputStream().use { JitenLexicon.load(it) }
            val ms = (System.nanoTime() - t) / 1_000_000
            System.gc(); System.gc()
            val heap = (rt.totalMemory() - rt.freeMemory() - before) / 1_000_000
            println("JITEN LEXICON: ${lex.wordCount} words, ${lex.keyCount} keys, loaded in $ms ms, heap ~$heap MB")
            lexicon = lex
        }
    }

    private val lex: JitenLexicon get() = lexicon.also { assumeTrue(it != null) }!!

    @Test fun nounCompound() {
        val ids = lex.lookup("黒板消し")
        assertNotNull(ids)
        assertTrue(lex.anyWithPos(ids!!, PartOfSpeech.Noun))
        assertTrue(lex.hasLookupForCompound("黒板消し"))
        assertTrue(lex.hasNonNameLookup("黒板消し"))
        assertNotNull(lex.getNonNameCompoundId("黒板消し"))
    }

    @Test fun suruAndExpressions() {
        assertTrue(lex.hasSuruVerbLookup("する"))
        assertTrue(lex.hasVerbOrAdjectiveLookup("食べる"))
        assertTrue(lex.hasExpressionLookup("元も子もない"))
        assertTrue(lex.hasCounterSenseAvailable("本"))
        assertFalse(lex.hasExpressionLookup("黒板"))
    }

    @Test fun coffeeKeysAndForms() {
        // コーヒー (1049180, uk, gai1): katakana key, hiragana keys with and without long-vowel conversion
        for (key in listOf("コーヒー", "こーひー", "こうひい")) assertTrue(key, lex.lookup(key)?.contains(1049180) == true)
        val word = lex.getWord(1049180)!!
        assertEquals(listOf("珈琲", "コーヒー"), word.forms.map { it.text })
        assertEquals(JmDictFormType.KanjiForm, word.forms[0].formType)
        assertEquals(listOf("ateji"), word.forms[0].infoTags)
        assertTrue("uk" in word.partsOfSpeech)
        assertTrue(lex.isKanaAppropriateId(1049180))
        assertTrue(lex.hasKanaAppropriateLookup("コーヒー"))
        assertTrue(lex.hasPrioritizedNonNameLookup("コーヒー"))
        // uk folds +10 into the kana score, -10 into the kanji score
        assertEquals(lex.priorityScore(1049180, true)!! - 20, lex.priorityScore(1049180, false)!!)
        assertEquals(word.getPriorityScore(true), lex.priorityScore(1049180, true))
    }

    @Test fun namesAndFrequency() {
        // ノア (JMnedict 5060001, Jiten custom priority)
        assertTrue(lex.isNameOnly(5060001))
        assertTrue(lex.isTrueName(5060001))
        assertFalse(lex.isKanaAppropriateId(5060001))
        assertTrue("jiten" in lex.getWord(5060001)!!.priorities!!)
        // の (particle, 1469800) is rank 1 in Jiten's list
        assertEquals(1, lex.wordFrequencyRank(1469800))
        assertEquals(1, lex.getBestNonNameFrequencyRank("の"))
        // Jiten's custom words are in
        assertTrue(lex.lookup("でした")?.contains(8000000) == true)
        assertEquals(WordOrigin.Gairaigo, lex.wordMeta(1049180)!!.origin)
    }

    /** Every form's GenerateLookupsForForm keys, computed with the Kotlin WanaKana, exist: Python and Kotlin agree. */
    @Test fun lookupKeysMatchKotlinWanaKana() {
        val ids = (0 until lex.keyCount).asSequence().flatMap { lex.idsAt(it).asSequence() }.toSortedSet()
        var forms = 0
        val missing = ArrayList<String>()
        for (id in ids) {
            for (form in lex.getWord(id)!!.forms) {
                forms++
                val key = WanaKana.toHiragana(form.text, convertLongVowelMark = false)
                val noLvm = try { WanaKana.toHiragana(form.text) } catch (e: WanaKana.NoVowelException) { key }
                val keys = listOfNotNull(key, noLvm, form.text.takeIf(WanaKana::isKatakana))
                for (k in keys) if (lex.lookup(k)?.contains(id) != true && missing.size < 20) missing += "$id $k"
            }
        }
        println("JITEN LEXICON: $forms forms checked")
        assertTrue("keys missing: $missing", missing.isEmpty())
    }
}
