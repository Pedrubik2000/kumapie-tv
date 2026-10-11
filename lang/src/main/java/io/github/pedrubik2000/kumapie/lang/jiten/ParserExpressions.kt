// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Parser.cs lines 3973-4943
// (CombineNounCompounds, CombineExpressions, CleanSentenceTokens, ApplyMisparseGates, ExcludeFinalMisparses, Concat*)
//
// Conventions (see JitenParser.kt): `internal fun JitenParser.x(...)`; C# `out` parameters become a nullable return;
// C#'s List<(WordInfo word, int position, int length)> is MutableList<SentenceWord>; diagnostics are left out.
// Already in JitenParser.kt (ported): the tables ExpressionExclusions, TwoTokenExpressionWhitelist, TwoTokenNounVerbIdioms,
// ExpressionExclusionHashes, TwoTokenExpressionWhitelistHashes (and NounCompoundExclusions) in its companion, and
// IsHashFoldSafeChar, IsHashFoldSafe, PrefixTailMayMatch (C# lines 4293-4318) plus compoundHashSet, hashBasePowers,
// maxLookupKeyLength and hiraRollingHash.
package io.github.pedrubik2000.kumapie.lang.jiten

// TODO(port): CombineNounCompounds (C# line 3973, 249 lines)
internal fun JitenParser.combineNounCompounds(sentences: List<SentenceInfo>) {
}

// TODO(port): TryCombineNounVerbStemCompound (C# line 4222, 33 lines); returns C#'s out (combinedWord, combinedLength), null = false
internal fun JitenParser.tryCombineNounVerbStemCompound(
    words: MutableList<SentenceWord>,
    i: Int,
    word: WordInfo,
    length: Int,
): Pair<WordInfo, Int>? = null

// TODO(port): CombineExpressions (C# line 4286, 6 lines)
internal fun JitenParser.combineExpressions(sentences: List<SentenceInfo>) {
}

// TODO(port): CombineExpressionsInSentence (C# line 4339, 301 lines)
internal fun JitenParser.combineExpressionsInSentence(sentence: SentenceInfo) {
}

// TODO(port): IsExpressionLookupMatch (C# line 4640, 4 lines)
internal fun JitenParser.isExpressionLookupMatch(text: String): Boolean = false

// TODO(port): IsMultiWordAdverbMatch (C# line 4644, 5 lines)
internal fun JitenParser.isMultiWordAdverbMatch(text: String): Boolean = false

// TODO(port): IsFunctionClusterMatch (C# line 4649, 7 lines)
internal fun JitenParser.isFunctionClusterMatch(text: String): Boolean = false

// TODO(port): IsExpressionBoundaryTheft (C# line 4656, 24 lines)
internal fun JitenParser.isExpressionBoundaryTheft(words: List<SentenceWord>, start: Int, windowSize: Int): Boolean = false

// TODO(port): IsCohesiveFunctionWord (C# line 4680, 8 lines)
internal fun JitenParser.isCohesiveFunctionWord(text: String): Boolean = false

// TODO(port): CleanSentenceTokens (C# line 4688, 19 lines)
internal fun JitenParser.cleanSentenceTokens(sentences: List<SentenceInfo>) {
}

// TODO(port): ApplyMisparseGates (C# line 4707, 192 lines; JmDictCache.GetWordsAsync = getWordsWithCache(ids, null))
internal fun JitenParser.applyMisparseGates(sentences: List<SentenceInfo>, corrected: MutableList<DeckWord>): MutableList<DeckWord> =
    corrected

// --- ported (needed by the skeleton) ---

internal fun JitenParser.excludeFinalMisparses(words: Iterable<DeckWord>): MutableList<DeckWord> {
    if (JitenParser.ExcludedMisparses.isEmpty()) return words.toMutableList()
    return words.filterTo(ArrayList()) { WordForm(it.wordId, it.readingIndex) !in JitenParser.ExcludedMisparses }
}

internal inline fun <T> concatSlice(list: List<T>, start: Int, count: Int, selector: (T) -> String): String {
    if (count == 1) return selector(list[start])
    val sb = StringBuilder()
    for (idx in 0 until count) sb.append(selector(list[start + idx]))
    return sb.toString()
}

internal fun concatTokenTexts(tokens: List<SentenceWord>, start: Int, count: Int) = concatSlice(tokens, start, count) { it.word.text }

internal fun concatTokenReadings(tokens: List<SentenceWord>, start: Int, count: Int) =
    concatSlice(tokens, start, count) { it.word.reading }

internal fun concatWordInfoTexts(wordInfos: List<WordInfo>, start: Int, count: Int) = concatSlice(wordInfos, start, count) { it.text }

internal fun concatWordInfoReadings(wordInfos: List<WordInfo>, start: Int, count: Int) =
    concatSlice(wordInfos, start, count) { it.reading }
