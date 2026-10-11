// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Parser.cs lines 4944-6011
// (compound matching across tokens, form-candidate picking, adjacent-word scoring, margin maps)
//
// Conventions (see JitenParser.kt): `internal fun JitenParser.x(...)`; C#'s Span<long>/Span<int> are LongArray/IntArray,
// `_lookupsAlt.TryGetValue(span)` is lexicon.lookup(substring); C#'s (sentenceIndex, wordIndex) keys are Pair<Int, Int>;
// diagnostics, timing and ParserCounters are left out. ScoredCandidatesKey/ScoredCandidates (C# lines 5422-5432) are
// private types of ApplyAdjacentScoringCore: put them here. BuildUserDictCsv (C# line 5981) isn't ported: it feeds
// Jiten's per-deck Sudachi user dictionary (no Sudachi Java equivalent, decks only).
package io.github.pedrubik2000.kumapie.lang.jiten

/** C#'s (int startIndex, string dictionaryForm, int wordId) tuple. */
internal data class CompoundMatch(val startIndex: Int, val dictionaryForm: String, val wordId: Int)

// TODO(port): TryMatchCompounds (C# line 4944, 219 lines)
internal fun JitenParser.tryMatchCompounds(
    wordInfos: List<WordInfo>,
    wordIndex: Int,
    tokenHashes: LongArray,
    lastConsumedIndex: Int = -1,
    forceExpressionOnly: Boolean = false,
): CompoundMatch? = null

// TODO(port): SortByLengthDescending (C# line 5163, 7 lines)
internal fun JitenParser.sortByLengthDescending(set: Set<String>): List<String> = set.toList()

// TODO(port): SortByLengthAscending (C# line 5170, 11 lines)
internal fun JitenParser.sortByLengthAscending(set: Set<String>): List<String> = set.toList()

// TODO(port): TryMatchCompoundWindow (C# line 5181, 144 lines)
internal fun JitenParser.tryMatchCompoundWindow(
    wordInfos: List<WordInfo>,
    wordIndex: Int,
    lastConsumedIndex: Int,
    dictForm: String,
    forceExpressionOnly: Boolean,
    dictFormHash: Long,
    prefCumHash: LongArray,
    prefCumLen: IntArray,
): CompoundMatch? = null

// TODO(port): FindMatchingHint (C# line 5372, 21 lines)
internal fun JitenParser.findMatchingHint(hints: Array<FuriganaHint>?, token: WordInfo): FuriganaHint? = null

// TODO(port): FindCandidate (C# line 5434, 8 lines)
internal fun JitenParser.findCandidate(candidates: List<FormCandidate>, wordId: Int, readingIndex: Int): FormCandidate? = null

// TODO(port): IsInfinitiveResult (C# line 5442, 3 lines)
internal fun JitenParser.isInfinitiveResult(word: DeckWord?): Boolean = false

// TODO(port): DropImpossibleKanaReadingCandidates (C# line 5466, 11 lines)
internal fun JitenParser.dropImpossibleKanaReadingCandidates(candidates: MutableList<FormCandidate>?, word: WordInfo): MutableList<FormCandidate>? =
    candidates

// TODO(port): ApplyAdjacentScoringCore (C# line 5477, 457 lines). PLACEHOLDER body: the first-pass results unchanged
// (C#'s "else" branch: ResolvedWordId set, non-null results in order).
internal fun JitenParser.applyAdjacentScoringCore(
    sentencePairs: MutableList<MutableList<SentencePair>>,
    candidateLookup: Map<DedupKey, MutableList<FormCandidate>>? = null,
    relocatedHints: Array<FuriganaHint>? = null,
): MutableList<DeckWord> {
    val corrected = ArrayList<DeckWord>()
    for (sentence in sentencePairs) for (p in sentence) {
        val r = p.result ?: continue
        p.word.resolvedWordId = r.wordId
        corrected.add(r)
    }
    return corrected
}

// --- ported (needed by the skeleton) ---

internal fun JitenParser.pickBestFormCandidate(
    allCandidates: MutableList<FormCandidate>,
    surface: String,
    dictionaryForm: String?,
    normalizedForm: String?,
    isNameContext: Boolean,
    sudachiReading: String? = null,
    sudachiPOS: PartOfSpeech = PartOfSpeech.Unknown,
    isSudachiPossibleDependant: Boolean = false,
    isSudachiNameGuess: Boolean = false,
): Pair<FormCandidate?, Int?> {
    val context = FormScoringContext.create(surface, dictionaryForm, normalizedForm, isNameContext, sudachiReading,
        sudachiPOS = sudachiPOS, isSudachiPossibleDependant = isSudachiPossibleDependant, isSudachiNameGuess = isSudachiNameGuess)
    val result = FormCandidateSelector.pickTopCandidates(allCandidates, context, JitenParser.ArchaicPosTypes)
    return result.best to result.marginToSecond
}

/** PreMatchedWordId is keyed: same-surface pins (帽子のツバ→鍔, ツバを飲む→唾) must not share a resolution. */
internal fun getDedupKey(wi: WordInfo) = DedupKey(wi.text, wi.partOfSpeech, wi.dictionaryForm, wi.reading, wi.isPersonNameContext,
    PosMapper.isNameLikeSudachiNoun(wi.partOfSpeech, wi.partOfSpeechSection1, wi.partOfSpeechSection2, wi.partOfSpeechSection3),
    wi.preMatchedWordId)

internal fun lookupResult(wi: WordInfo, resultLookup: Map<DedupKey, Pair<DeckWord?, Int?>>): Pair<DeckWord?, Int?> =
    resultLookup[getDedupKey(wi)] ?: (null to null)

internal fun JitenParser.applyAdjacentScoring(
    sentences: List<SentenceInfo>,
    processedResults: List<ProcessedWord>,
    candidateLookup: Map<DedupKey, MutableList<FormCandidate>>? = null,
    relocatedHints: Array<FuriganaHint>? = null,
): MutableList<DeckWord> {
    var pos = 0
    val sentencePairs = buildSentencePairs(sentences) {
        if (pos >= processedResults.size) null to null
        else processedResults[pos++].let { it.word to it.margin }
    }
    return applyAdjacentScoringCore(sentencePairs, candidateLookup, relocatedHints)
}

internal fun JitenParser.applyAdjacentScoringFromLookup(
    sentences: List<SentenceInfo>,
    resultLookup: Map<DedupKey, Pair<DeckWord?, Int?>>,
    candidateLookup: Map<DedupKey, MutableList<FormCandidate>>? = null,
    relocatedHints: Array<FuriganaHint>? = null,
): MutableList<DeckWord> =
    applyAdjacentScoringCore(buildSentencePairs(sentences) { lookupResult(it, resultLookup) }, candidateLookup, relocatedHints)

internal fun buildSentencePairs(
    sentences: List<SentenceInfo>,
    lookupResult: (WordInfo) -> Pair<DeckWord?, Int?>,
): MutableList<MutableList<SentencePair>> {
    val result = ArrayList<MutableList<SentencePair>>(sentences.size)
    for (sentence in sentences) {
        val pairs = ArrayList<SentencePair>(sentence.words.size)
        for (sw in sentence.words) {
            if (sw.word.partOfSpeech == PartOfSpeech.SupplementarySymbol) continue
            val (word, margin) = lookupResult(sw.word)
            pairs.add(SentencePair(sw.word, word, margin))
        }
        result.add(pairs)
    }
    return result
}

internal fun buildMarginMap(sentences: List<SentenceInfo>, processedWithMargins: List<ProcessedWord>): MutableMap<Pair<Int, Int>, Int?> {
    val map = HashMap<Pair<Int, Int>, Int?>()
    var flatIndex = 0
    for (si in sentences.indices) {
        for (wi in sentences[si].words.indices) {
            if (sentences[si].words[wi].word.partOfSpeech == PartOfSpeech.SupplementarySymbol) continue
            if (flatIndex < processedWithMargins.size) map[si to wi] = processedWithMargins[flatIndex].margin
            flatIndex++
        }
    }
    return map
}

internal fun buildMarginMapFromLookup(
    sentences: List<SentenceInfo>,
    resultLookup: Map<DedupKey, Pair<DeckWord?, Int?>>,
): MutableMap<Pair<Int, Int>, Int?> {
    val map = HashMap<Pair<Int, Int>, Int?>()
    for (si in sentences.indices) {
        for (wi in sentences[si].words.indices) {
            val word = sentences[si].words[wi].word
            if (word.partOfSpeech == PartOfSpeech.SupplementarySymbol) continue
            resultLookup[getDedupKey(word)]?.let { map[si to wi] = it.second }
        }
    }
    return map
}

internal fun isRiskyNameEntry(surface: String) = surface.length <= 2 && WanaKana.isHiragana(surface)
