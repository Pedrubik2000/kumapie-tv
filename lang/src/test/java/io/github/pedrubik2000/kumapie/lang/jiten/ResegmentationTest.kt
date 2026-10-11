// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Tests/ResegmentationTests.cs
//
// C# builds lookups / frequencyRanks / wordMeta dictionaries; here they are one tiny in-memory JitenLexicon. A word with
// a frequency rank needs a W line, so it also has (empty-POS) meta where C# had none: that only adds noun-like POS
// bonuses, which none of these cases depend on.
package io.github.pedrubik2000.kumapie.lang.jiten

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResegmentationTest {
    // ─── BuildEdges ───

    @Test fun buildEdgesReturnsLongestMatchFirst() {
        val lex = lex("大学" to 1, "大" to 2, "学" to 3)
        val edges = ResegmentationScorer.buildEdges("大学生", 0, lex)
        assertTrue(edges.isNotEmpty())
        assertEquals(2, edges[0].length)
        assertTrue(1 in edges[0].wordIds)
    }

    @Test fun buildEdgesNormalizesToHiragana() {
        val edges = ResegmentationScorer.buildEdges("ガクセイ", 0, lex("がくせい" to 99))
        assertEquals(1, edges.count { 99 in it.wordIds })
    }

    // ─── FindBestPath ───

    @Test fun findBestPathGreedySucceedsTwoSegments() {
        val path = ResegmentationScorer.findBestPath("大学生", lex("大学" to 1, "生" to 2))
        assertNotNull(path)
        assertTrue(path!!.isComplete(3))
        assertEquals(listOf(2, 1), path.segments.map { it.length })
    }

    // C#'s case (あい/あ/いう over あいう) fails at 44b48a4 too: BuildEdges now drops single-kana edges like あ, so this
    // uses kanji: greedy 東京 dead-ends at 都, 東 + 京都 covers the span.
    @Test fun findBestPathGreedyDeadEndDpFindsPath() {
        val path = ResegmentationScorer.findBestPath("東京都", lex("東京" to 1, "東" to 2, "京都" to 3))
        assertNotNull(path)
        assertTrue(path!!.isComplete(3))
        assertTrue(path.segments.size >= 2)
    }

    @Test fun findBestPathNoValidDecompositionReturnsNull() {
        assertNull(ResegmentationScorer.findBestPath("あいう", lex()))
    }

    @Test fun findBestPathPrefersFewerSegments() {
        val path = ResegmentationScorer.findBestPath("あいうえ", lex("あ" to 1, "いう" to 2, "え" to 3, "あい" to 4, "うえ" to 5))
        assertNotNull(path)
        assertTrue(path!!.isComplete(4))
        assertEquals(2, path.segments.size)
    }

    @Test fun findBestPathLargeSpanTerminatesQuickly() {
        val text = "あいうえおかきくけこさしすせ"
        val keys = ArrayList<Pair<String, Int>>()
        for (start in text.indices) for (len in 1..3) if (start + len <= text.length)
            keys.add(text.substring(start, start + len) to start * 100 + len)
        val lex = lex(*keys.toTypedArray())
        val t = System.nanoTime()
        ResegmentationScorer.findBestPath(text, lex)
        assertTrue((System.nanoTime() - t) / 1_000_000 < 500)
    }

    // ─── UncertaintyDetector ───

    @Test fun uncertaintyDetectorSkipsParticlesAndVerbs() {
        val sentence = sentence("から" to PartOfSpeech.Particle, "食べる" to PartOfSpeech.Verb, "とても" to PartOfSpeech.Auxiliary)
        assertTrue(UncertaintyDetector.findSpans(sentence, lex()).isEmpty())
    }

    @Test fun uncertaintyDetectorSkipsPreMatchedAndLookupHits() {
        val sentence = SentenceInfo("学校生活大学生")
        sentence.words.add(SentenceWord(word("学校生活").apply { preMatchedWordId = 99 }, 0, 4))
        sentence.words.add(SentenceWord(word("大学生"), 4, 3))
        assertTrue(UncertaintyDetector.findSpans(sentence, lex("大学生" to 42)).isEmpty())
    }

    // ─── ResegmentationEngine ───

    @Test fun replacesTokenWithHighFrequencyPath() {
        val s = single("あいうえ")
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s), lex("あい" to 1, "うえ" to 2, ranks = mapOf(1 to 100, 2 to 200)))
        assertEquals(listOf("あい", "うえ"), texts(s))
    }

    @Test fun rejectsLowFrequencyPath() {
        val s = single("あいうえ")
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s), lex("あい" to 1, "うえ" to 2))
        assertEquals(1, s.words.size)
    }

    @Test fun rejectsSingleCharHiraganaSegment() {
        val s = single("あいう")
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s), lex("あ" to 1, "いう" to 2, ranks = mapOf(1 to 100, 2 to 200)))
        assertEquals(1, s.words.size)
    }

    @Test fun allowsSingleCharKatakanaSegment() {
        val s = single("ダッタラオマエガ")
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s),
            lex("だったら" to 1, "おまえ" to 2, "が" to 3, ranks = mapOf(1 to 100, 2 to 200, 3 to 50)))
        assertEquals(listOf("ダッタラ", "オマエ", "ガ"), texts(s))
    }

    @Test fun allowsSingleCharKanjiSegment() {
        val s = single("大学生")
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s), lex("大学" to 1, "生" to 2, ranks = mapOf(1 to 500, 2 to 300)))
        assertEquals(listOf("大学", "生"), texts(s))
    }

    @Test fun rejectsTooManySegments() {
        val s = single("あいうえ")
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s),
            lex("あ" to 1, "い" to 2, "う" to 3, "え" to 4, ranks = mapOf(1 to 100, 2 to 200, 3 to 300, 4 to 400)))
        assertEquals(1, s.words.size)
    }

    @Test fun replacedTokensHavePreMatchedWordIdAndDictionaryFormEqualToText() {
        val s = single("あいうえ")
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s), lex("あい" to 1, "うえ" to 2, ranks = mapOf(1 to 100, 2 to 200)))
        assertEquals(listOf(1, 2), s.words.map { it.word.preMatchedWordId })
        for (w in s.words) assertEquals(w.word.text, w.word.dictionaryForm)
    }

    @Test fun resegmentedTokenPreMatchedIdPreservedOnSecondPass() {
        val lex = lex("あい" to 1, "うえ" to 2, ranks = mapOf(1 to 100, 2 to 200))
        val s = single("あいうえ")
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s), lex)
        assertEquals(2, s.words.size)
        val ids = s.words.map { it.word.preMatchedWordId }
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s), lex)
        assertEquals(2, s.words.size)
        assertEquals(ids, s.words.map { it.word.preMatchedWordId })
    }

    @Test fun tokenFoundInLookupsIsNotResegmented() {
        val s = single("あいうえ")
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s),
            lex("あいうえ" to 99, "あい" to 1, "うえ" to 2, ranks = mapOf(1 to 100, 2 to 200, 99 to 50)))
        assertEquals(1, s.words.size)
    }

    @Test fun tokenWithDictionaryFormInLookupsIsNotResegmented() {
        val s = single("あいうえ").apply { words[0].word.dictionaryForm = "あいうえる" }
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s),
            lex("あいうえる" to 99, "あい" to 1, "うえ" to 2, ranks = mapOf(1 to 100, 2 to 200)))
        assertEquals(1, s.words.size)
    }

    @Test fun atMinAcceptScoreAcceptsPath() {
        // -15*2 + 5 (rank 25000) + 0 + 10 (length 3) + 50 = 35 >= 25 (C#'s comment forgets the length bonus)
        val s = single("あいうえお")
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s), lex("あいう" to 1, "えお" to 2, ranks = mapOf(1 to 25000)))
        assertEquals(2, s.words.size)
    }

    @Test fun posScoreSavesLowFreqPathNounBeforeParticle() {
        val s = sentence("あいうえ" to PartOfSpeech.Noun, "が" to PartOfSpeech.Particle)
        ResegmentationEngine.tryImproveUncertainSpans(listOf(s), lex("あい" to 1, "うえ" to 2, pos = mapOf(1 to "n", 2 to "n")))
        assertEquals(listOf("あい", "うえ", "が"), texts(s))
    }

    // ─── ScorePath ───

    @Test fun scorePathHighFrequencyTwoSegmentsScoresHigh() {
        val path = path(Triple(0, 2, listOf(1)), Triple(2, 2, listOf(2)))
        assertTrue(ResegmentationScorer.scorePath(path, lex(ranks = mapOf(1 to 100, 2 to 200))) >= 20)
    }

    @Test fun scorePathNoFrequencyDataScoresLow() {
        val path = path(Triple(0, 2, listOf(1)), Triple(2, 2, listOf(2)))
        assertTrue(ResegmentationScorer.scorePath(path, lex()) < 25)
    }

    @Test fun scorePathManySmallSegmentsNoFrequencyScoresVeryLow() {
        val path = path(*(0..3).map { Triple(it, 1, listOf(it + 1)) }.toTypedArray())
        assertTrue(ResegmentationScorer.scorePath(path, lex()) < 0)
    }

    // ─── TryResegmentLowConfidenceTokens ───

    private fun lowConfidence(text: String, lex: JitenLexicon, margin: Int?, setup: WordInfo.() -> Unit = {}): Pair<Boolean, SentenceInfo> {
        val s = single(text).apply { words[0].word.setup() }
        return ResegmentationEngine.tryResegmentLowConfidenceTokens(listOf(s), lex, mapOf((0 to 0) to margin)) to s
    }

    private val highFreq get() = lex("あい" to 1, "うえ" to 2, ranks = mapOf(1 to 100, 2 to 200))

    @Test fun confidenceResegCandidateSetConstrainedDuringAdjacentRescore() {
        val (changed, s) = lowConfidence("あい", lex("あ" to 99, "い" to 88, ranks = mapOf(99 to 1, 88 to 2)), 5) { preMatchedWordId = 1 }
        assertFalse(changed)
        assertEquals(1, s.words.size)
        assertEquals(1, s.words[0].word.preMatchedWordId)
    }

    @Test fun confidenceResegLowMarginHighScoringPathResegments() {
        val (changed, s) = lowConfidence("あいうえ", highFreq, 5)
        assertTrue(changed)
        assertEquals(listOf("あい", "うえ"), texts(s))
    }

    @Test fun confidenceResegLowMarginMediumScoringPathDoesNotResegment() {
        val (changed, s) = lowConfidence("あいうえお", lex("あいう" to 1, "えお" to 2, ranks = mapOf(1 to 25000)), 5)
        assertFalse(changed)
        assertEquals(1, s.words.size)
    }

    @Test fun confidenceResegMarginAboveThresholdSkipped() {
        assertFalse(lowConfidence("あいうえ", highFreq, 15).first)
    }

    @Test fun confidenceResegPreMatchedWordIdSkipped() {
        assertFalse(lowConfidence("あいうえ", highFreq, 5) { preMatchedWordId = 99 }.first)
    }

    @Test fun confidenceResegVerbPosSkipped() {
        assertFalse(lowConfidence("あいうえ", highFreq, 5) { partOfSpeech = PartOfSpeech.Verb }.first)
    }

    @Test fun confidenceResegNullMarginSkipped() {
        assertFalse(lowConfidence("あいうえ", highFreq, null).first)
    }

    // ─── ScorePosTransitions ───

    @Test fun scorePosTransitionsNounLikeLastBeforeParticleGetsBonus() {
        val path = path(Triple(0, 2, listOf(1)), Triple(2, 2, listOf(2)))
        val pos = mapOf(1 to PartOfSpeech.Noun, 2 to PartOfSpeech.Noun)
        assertEquals(30, ResegmentationScorer.scorePosTransitions(path, pos, null, PartOfSpeech.Particle))
    }

    @Test fun scorePosTransitionsNumeralNeighborBeforeCounterGetsBonus() {
        val path = path(Triple(0, 3, listOf(1)))
        assertEquals(30, ResegmentationScorer.scorePosTransitions(path, mapOf(1 to PartOfSpeech.Counter), PartOfSpeech.Numeral, null))
    }

    @Test fun scorePosTransitionsParticleNeighborBeforeNonNounLikeGetsPenalty() {
        val path = path(Triple(0, 2, listOf(1)))
        assertEquals(-15, ResegmentationScorer.scorePosTransitions(path, mapOf(1 to PartOfSpeech.Verb), PartOfSpeech.Particle, null))
    }

    @Test fun scorePosTransitionsInternalParticleParticleTransitionGetsPenalty() {
        val path = path(Triple(0, 2, listOf(1)), Triple(2, 2, listOf(2)))
        val pos = mapOf(1 to PartOfSpeech.Particle, 2 to PartOfSpeech.Particle)
        assertEquals(-20, ResegmentationScorer.scorePosTransitions(path, pos, null, null))
    }

    @Test fun scorePosTransitionsEmptyPosDictReturnsZero() {
        val path = path(Triple(0, 2, listOf(1)), Triple(2, 2, listOf(2)))
        assertEquals(0, ResegmentationScorer.scorePosTransitions(path, emptyMap(), null, null))
    }

    @Test fun scorePosTransitionsFrequencyRankBreaksTie() {
        val path = path(Triple(0, 2, listOf(1, 2)))
        val pos = mapOf(1 to PartOfSpeech.Particle, 2 to PartOfSpeech.Noun)
        val freq = lex(ranks = mapOf(1 to 1000, 2 to 100))
        assertEquals(20, ResegmentationScorer.scorePosTransitions(path, pos, null, PartOfSpeech.Particle, freq))
    }

    // ─── DotNetSort ───

    @Test fun dotNetSortSortsLikeAComparator() {
        val rnd = java.util.Random(7)
        for (n in listOf(0, 1, 2, 3, 16, 17, 40, 200)) {
            val list = MutableList(n) { rnd.nextInt(10) }
            DotNetSort.sort(list, naturalOrder())
            assertEquals(list.sorted(), list)
        }
    }

    // ─── Helpers ───

    /** Lookup keys to word ids, plus frequency ranks and JMdict POS tags; every id with a rank or POS gets a W line. */
    private fun lex(vararg keys: Pair<String, Int>, ranks: Map<Int, Int> = emptyMap(), pos: Map<Int, String> = emptyMap()): JitenLexicon {
        val words = (ranks.keys + pos.keys).toSortedSet().ifEmpty { sortedSetOf(Int.MAX_VALUE) } // the loader needs one word
        val lookups = keys.groupBy({ it.first }, { it.second }).toSortedMap()
        val tsv = buildString {
            append("#words ${words.size} keys ${lookups.size}\n")
            for (id in words) append("W\t$id\t${pos[id] ?: ""}\t\t\t${ranks[id] ?: ""}\t\t\n")
            for ((k, ids) in lookups) append("L\t$k\t${ids.joinToString(" ")}\n")
        }
        return JitenLexicon.load(tsv.byteInputStream(Charsets.UTF_8))
    }

    private fun word(text: String, pos: PartOfSpeech = PartOfSpeech.Noun) = WordInfo().apply { this.text = text; partOfSpeech = pos }

    private fun single(text: String) = SentenceInfo(text).apply { words.add(SentenceWord(word(text), 0, text.length)) }

    private fun sentence(vararg words: Pair<String, PartOfSpeech>): SentenceInfo {
        val s = SentenceInfo(words.joinToString("") { it.first })
        var p = 0
        for ((text, pos) in words) {
            s.words.add(SentenceWord(word(text, pos), p, text.length))
            p += text.length
        }
        return s
    }

    private fun texts(s: SentenceInfo) = s.words.map { it.word.text }

    private fun path(vararg segs: Triple<Int, Int, List<Int>>) = SpanPath(segs.map { SpanTokenCandidate(it.first, it.second, it.third) })
}
