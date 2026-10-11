// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Resegmentation/ResegmentationEngine.cs,
// ResegmentationModels.cs, ResegmentationScorer.cs, UncertaintyDetector.cs
//
// C#'s lookups / frequencyRanks / wordMeta parameters are all [JitenLexicon] (lexicon.lookup, wordFrequencyRank,
// wordMeta/hasPos/primaryPos/isNameOnly); marginMap keys are Pair(sentenceIndex, wordIndex); diagnostics are left out.
// Only Parser.cs calls in (the two entry points below); the scorer and detector are private to these files.
package io.github.pedrubik2000.kumapie.lang.jiten

// ResegmentationModels.cs

internal data class SpanTokenCandidate(val startChar: Int, val length: Int, val wordIds: List<Int>)

internal data class SpanPath(val segments: List<SpanTokenCandidate>) {
    fun isComplete(spanLength: Int): Boolean =
        segments.isNotEmpty() && segments.last().startChar + segments.last().length == spanLength
}

internal class UncertainSpan(
    val wordIndex: Int = 0,
    val text: String = "",
    val position: Int = 0,
    val length: Int = 0,
    /** Span resolves only to JMnedict names (ひとつだけ → 一ッ岳); only a multi-segment path (一つ+だけ) counts. */
    val nameOnly: Boolean = false,
)

/**
 * TODO(port): ResegmentationEngine.cs (438 lines): MinAcceptScore, MinAcceptScoreConfidence, TryImproveUncertainSpans (14),
 * TryResegmentLowConfidenceTokens (102), TrySplitCompoundNumeral (189), NonNameLookupIds (223), HasShortPureNameSegment (235),
 * NameContextSuffixes (261), HasNameSegmentOutsidePersonContext (269), HasSuruConjugationTail (310),
 * IsKatakanaConjugatedWordSpelling (317), IsReduplicatedKanaSplit (337), HasBadSingleKana (357), IsKana/IsHiragana (372),
 * CompoundTailVerbRankAdvantage, SegmentSurfacePins (376-379), ReplaceSpan (381; VerbStemLookup.find(text, reading, lexicon)).
 */
internal object ResegmentationEngine {
    // TODO(port): TryImproveUncertainSpans (ResegmentationEngine.cs line 14, 88 lines)
    fun tryImproveUncertainSpans(sentences: List<SentenceInfo>, lexicon: JitenLexicon, protectedSurfaces: Set<String>? = null) {
    }

    // TODO(port): TryResegmentLowConfidenceTokens (ResegmentationEngine.cs line 102, 87 lines); true = sentences changed
    fun tryResegmentLowConfidenceTokens(
        sentences: List<SentenceInfo>,
        lexicon: JitenLexicon,
        marginMap: Map<Pair<Int, Int>, Int?>,
    ): Boolean = false
}

/**
 * TODO(port): ResegmentationScorer.cs (391 lines): constants (9-13), IsKatakanaParticleChar (16), IsKatakanaRunChar (19),
 * BuildEdges (21), IsPlausibleSegment (64), FindBestPath (91), ScorePath (244), ScorePosTransitions (299; C#'s
 * Dictionary<int, PartOfSpeech> wordPosByWordId stays a Map), ResolveSegmentPos (344), IsNounLike/IsPredicateHost/
 * IsNumericLike (366-373), HasNonTerminalSingleCharKana (378).
 */
internal object ResegmentationScorer

/**
 * TODO(port): UncertaintyDetector.cs (145 lines): SkipPos (10), FindSpans (20), HasMatch (73), IsPureNameOnlyMatch (96),
 * HasGodanDictFormMatch (126), HasIchidanDictFormMatch (132), HasAdjSaNominalizationMatch (139).
 */
internal object UncertaintyDetector
