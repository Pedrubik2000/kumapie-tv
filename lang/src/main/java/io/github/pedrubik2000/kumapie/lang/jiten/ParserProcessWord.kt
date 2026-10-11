// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Parser.cs lines 1239-2796
// (ProcessWord: resolving one token to a JMdict word, deconjugation, direct lookups, reading indices)
//
// Conventions for this file (see JitenParser.kt): members are `internal fun JitenParser.x(...)`; C#'s
// `ConcurrentDictionary<int, JmDictWord>? batchWordCache` is `MutableMap<Int, JmDictWord>?`, JmDictCache.GetWordsAsync /
// GetWordsWithCache is getWordsWithCache(ids, batchWordCache); diagnostics, the DeckWordCache (prefetchedCache) and
// the semaphore are left out; C# byte reading indices are Int (255 = none).
package io.github.pedrubik2000.kumapie.lang.jiten

/** C#'s (bool success, DeckWord? word, int? margin, List<FormCandidate>? candidates) tuple. */
internal data class ResolveResult(
    val success: Boolean,
    val word: DeckWord?,
    val margin: Int?,
    val candidates: MutableList<FormCandidate>?,
)

/** CollectVerbAdjectiveCandidates' (deconjugated, candidates, fromReadingChannel) tuple. */
internal data class VerbAdjectiveCandidates(
    val deconjugated: List<DeconjugationForm>,
    val candidates: MutableList<Pair<DeconjugationForm, List<Int>>>,
    val fromReadingChannel: Boolean,
)

// TODO(port): ProcessWord (C# line 1239, 228 lines). The body below is a PLACEHOLDER so parseText runs end to end
// (C#'s first filter, then the first lookup id of the text or its dictionary form, no scoring); replace it entirely.
internal fun JitenParser.processWord(
    wordInfo: WordInfo,
    deconjugator: Deconjugator,
    batchWordCache: MutableMap<Int, JmDictWord>? = null,
): ProcessWordResult {
    val textWithoutBar = wordInfo.text.trimEnd('ー')
    if ((textWithoutBar.isNotEmpty() && textWithoutBar.all(Character::isDigit)) ||
        (textWithoutBar.length == 1 && (textWithoutBar[0] in 'a'..'z' || textWithoutBar[0] in 'A'..'Z' ||
            textWithoutBar[0] in 'ａ'..'ｚ' || textWithoutBar[0] in 'Ａ'..'Ｚ')))
        return ProcessWordResult.FilteredOut
    val ids = wordInfo.preMatchedWordId?.let { intArrayOf(it) }
        ?: LookupCandidateCollector.collectIds(lexicon, wordInfo.text).takeIf { it.isNotEmpty() }?.toIntArray()
        ?: lexicon.lookup(wordInfo.dictionaryForm)
        ?: return ProcessWordResult.Unresolved
    val id = ids.firstOrNull() ?: return ProcessWordResult.Unresolved
    return ProcessWordResult.fromResolved(DeckWord(wordId = id, originalText = wordInfo.text,
        sudachiReading = wordInfo.reading, sudachiPartOfSpeech = wordInfo.partOfSpeech))
}

// TODO(port): TryResolvePreMatched (C# line 1467, 42 lines)
internal fun JitenParser.tryResolvePreMatched(wordInfo: WordInfo, batchWordCache: MutableMap<Int, JmDictWord>?): DeckWord? = null

// TODO(port): PosEscalationLadder (C# line 1509, a static table) + ResolveByPosEscalation (C# line 1517, 32 lines)
internal fun JitenParser.resolveByPosEscalation(
    wordInfo: WordInfo,
    deconjugator: Deconjugator,
    batchWordCache: MutableMap<Int, JmDictWord>?,
): ResolveResult = ResolveResult(false, null, null, null)

// TODO(port): ArbitrateNounVsVerbStem (C# line 1549, 188 lines); returns C#'s (word, margin, candidates) tuple
internal fun JitenParser.arbitrateNounVsVerbStem(
    wordInfo: WordInfo,
    nounResult: ResolveResult,
    isNameLikeSudachiNoun: Boolean,
    deconjugator: Deconjugator,
    batchWordCache: MutableMap<Int, JmDictWord>?,
): ProcessedWord = ProcessedWord(nounResult.word, nounResult.margin, nounResult.candidates)

// TODO(port): IsKanaCollisionResolution (C# line 1737, 27 lines)
internal fun JitenParser.isKanaCollisionResolution(processedWord: DeckWord, wordInfo: WordInfo, baseWord: String): Boolean = false

// TODO(port): NextSurfaceMutation (C# line 1764, 58 lines)
internal fun JitenParser.nextSurfaceMutation(wordInfo: WordInfo, baseWord: String): String? = null

// TODO(port): TryGeminationCollapseRetry (C# line 1822, 28 lines); null = C#'s null, word is never null otherwise
internal fun JitenParser.tryGeminationCollapseRetry(
    wordInfo: WordInfo,
    processedWord: DeckWord,
    baseWord: String,
    deconjugator: Deconjugator,
    batchWordCache: MutableMap<Int, JmDictWord>?,
): ProcessedWord? = null

// TODO(port): DictOrNormalizedFormHasLookup (C# line 1850, 22 lines)
internal fun JitenParser.dictOrNormalizedFormHasLookup(wordInfo: WordInfo): Boolean = false

// TODO(port): NormalizedFormIntroducesKanji (C# line 1872, 14 lines)
internal fun JitenParser.normalizedFormIntroducesKanji(text: String, normalizedForm: String): Boolean = false

// TODO(port): DeconjugateWord (C# line 1886, 307 lines)
internal fun JitenParser.deconjugateWord(
    wordInfo: WordInfo,
    batchWordCache: MutableMap<Int, JmDictWord>? = null,
): ResolveResult = ResolveResult(false, null, null, null)

// TODO(port): DeconjugateVerbOrAdjective (C# line 2193, 87 lines)
internal fun JitenParser.deconjugateVerbOrAdjective(
    wordInfo: WordInfo,
    deconjugator: Deconjugator,
    batchWordCache: MutableMap<Int, JmDictWord>? = null,
): ResolveResult = ResolveResult(false, null, null, null)

// TODO(port): CollectVerbAdjectiveCandidates (C# line 2280, 54 lines)
internal fun JitenParser.collectVerbAdjectiveCandidates(
    wordInfo: WordInfo,
    deconjugator: Deconjugator,
    normalizedText: String,
): VerbAdjectiveCandidates = VerbAdjectiveCandidates(emptyList(), ArrayList(), false)

// TODO(port): PrioritiseAndCollectCandidateIds (C# line 2334, 70 lines); returns (allCandidateIds, directSurfaceIds)
internal fun JitenParser.prioritiseAndCollectCandidateIds(
    wordInfo: WordInfo,
    candidates: List<Pair<DeconjugationForm, List<Int>>>,
    baseDictionaryWord: String,
): Pair<MutableList<Int>, MutableList<Int>> = ArrayList<Int>() to ArrayList()

// TODO(port): FilterAndMatchCandidates (C# line 2404, 73 lines); returns (matches, filteredSuruNounIds)
internal fun JitenParser.filterAndMatchCandidates(
    wordInfo: WordInfo,
    deconjugated: List<DeconjugationForm>,
    candidates: List<Pair<DeconjugationForm, List<Int>>>,
    wordCache: Map<Int, JmDictWord>,
    fromReadingChannel: Boolean,
    normalizedText: String,
    baseDictionaryWord: String,
    batchWordCache: MutableMap<Int, JmDictWord>?,
): Pair<MutableList<Pair<JmDictWord, DeconjugationForm>>, MutableSet<Int>?> = ArrayList<Pair<JmDictWord, DeconjugationForm>>() to null

// TODO(port): EnumerateMatchAndSurfaceCandidates (C# line 2477, 47 lines)
internal fun JitenParser.enumerateMatchAndSurfaceCandidates(
    wordInfo: WordInfo,
    matches: List<Pair<JmDictWord, DeconjugationForm>>,
    directSurfaceIds: List<Int>,
    filteredSuruNounIds: Set<Int>?,
    wordCache: Map<Int, JmDictWord>,
    normalizedText: String,
    baseDictionaryWord: String,
): MutableList<FormCandidate> = ArrayList()

// TODO(port): ApplyConjugationChainOverrides (C# line 2524, 46 lines); returns (bestPair, margin)
internal fun JitenParser.applyConjugationChainOverrides(
    bestPair: FormCandidate?,
    margin: Int?,
    allFormCandidates: List<FormCandidate>,
    wordInfo: WordInfo,
): Pair<FormCandidate?, Int?> = bestPair to margin

// TODO(port): IsGodanClassTag (C# line 2570, 1 line)
internal fun JitenParser.isGodanClassTag(tag: String): Boolean = false

// TODO(port): IsIchidanOnlyVerb (C# line 2572, 5 lines)
internal fun JitenParser.isIchidanOnlyVerb(partsOfSpeech: List<String>): Boolean = false

// TODO(port): TryFallbackLookup (C# line 2577, 54 lines); appends to matches
internal fun JitenParser.tryFallbackLookup(
    formText: String,
    wordInfo: WordInfo,
    deconjugated: List<DeconjugationForm>,
    matches: MutableList<Pair<JmDictWord, DeconjugationForm>>,
    batchWordCache: MutableMap<Int, JmDictWord>?,
    extraProcessPrefix: String? = null,
) {
}

// TODO(port): GetWordsDirectLookup (C# line 2631, 19 lines; public API, not used by parseText)
fun JitenParser.getWordsDirectLookup(words: List<String>): List<DeckWord> = emptyList()

// TODO(port): GetWordsDirectLookupByReading (C# line 2650, 26 lines; public API, not used by parseText). Key = (word, reading ?: "").
fun JitenParser.getWordsDirectLookupByReading(
    pairs: List<Pair<String, String?>>,
    surfacesInDictionary: MutableSet<String>? = null,
): Map<Pair<String, String>, DeckWord> = emptyMap()

/**
 * TODO(port): ResolveDirectLookupCandidates (C# line 2676, 51 lines); returns (candidates: surface -> (word, readingIndex)
 * list, formFrequencies). Jiten's WordFormFrequencies table (per-form frequency ranks) isn't in the lexicon: the
 * second map is (wordId, readingIndex) -> FrequencyRank and stays empty, so PickBestDirectLookupMatch keeps list order.
 */
internal fun JitenParser.resolveDirectLookupCandidates(
    surfaces: Iterable<String>,
): Pair<Map<String, List<Pair<JmDictWord, Int>>>, Map<WordForm, Int>> = emptyMap<String, List<Pair<JmDictWord, Int>>>() to emptyMap()

// TODO(port): PickBestDirectLookupMatch (C# line 2727, 29 lines) + ReadingHintSeparators (C# line 2756)
internal fun JitenParser.pickBestDirectLookupMatch(
    matches: List<Pair<JmDictWord, Int>>,
    formFrequencies: Map<WordForm, Int>,
    readingHint: String?,
    requireReadingMatch: Boolean = false,
): Pair<JmDictWord, Int>? = null

// TODO(port): WordHasKanaReading (C# line 2758, 4 lines)
internal fun JitenParser.wordHasKanaReading(word: JmDictWord, hintHira: String): Boolean = false

// TODO(port): BuildDirectLookupWord (C# line 2762, 7 lines)
internal fun JitenParser.buildDirectLookupWord(word: String, best: Pair<JmDictWord, Int>): DeckWord =
    DeckWord(wordId = best.first.wordId, originalText = word, readingIndex = best.second)

// TODO(port): GetKatakanaReading (C# line 2769, 6 lines)
internal fun JitenParser.getKatakanaReading(word: JmDictWord, readingIndex: Int): String = ""

// TODO(port): GetBestReadingIndex (C# line 2775, 20 lines); 255 = no candidate
internal fun JitenParser.getBestReadingIndex(word: JmDictWord, originalText: String, sudachiReading: String? = null): Int = 0
