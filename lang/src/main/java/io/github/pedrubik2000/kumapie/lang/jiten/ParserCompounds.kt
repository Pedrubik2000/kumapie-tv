// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Parser.cs lines 2797-3972
// (CombineCompounds, lexical adverbs, long vowels, orphaned misparses, unknown nouns, prefixes, compound verb tails,
// lookup helpers, trailing particles)
//
// Conventions (see JitenParser.kt): `internal fun JitenParser.x(...)`; C# `out` parameters become a nullable return
// (null = false); diagnostics are left out. Parser.cs lines 3219 (HasLookupForCompound) and 3677-3884 (LookupWithKanaFallback,
// KanaFallback, HasLookupWhere, AnyWithPos, AnyNonNameWithPos, GetNonNameCompoundId, GetBestNonNameFrequencyRank,
// HasNonNameLookup, HasVerbOrAdjectiveLookup, HasExpressionLookup, HasCounterSenseAvailable, IsKanaAppropriateId,
// HasKanaAppropriateLookup, HasSuruVerbLookup, HasPrioritizedNonNameLookup) are already JitenLexicon methods
// (lexicon.hasLookupWhere(text, JitenLexicon.KanaFallback.X) { ids -> ... } with ids an IntArray): call those, don't redefine.
// Static tables only this range uses (BoundPrefixKanji, CompoundVerbHeads, TrailingParticles, ArchaicOnlyPriorityFloor)
// go in this file as private top-level vals; ParticleExpressionOpeners, LexicalizedAdverbPairs and NounCompoundExclusions
// are already in JitenParser's companion (shared with other ranges).
package io.github.pedrubik2000.kumapie.lang.jiten

// TODO(port): CombineCompounds (C# line 2797, 98 lines; uses compoundScratchSet for C#'s [ThreadStatic] _compoundScratchSet)
internal fun JitenParser.combineCompounds(sentences: List<SentenceInfo>) {
}

// TODO(port): CombineLexicalAdverbs (C# line 2901, 88 lines)
internal fun JitenParser.combineLexicalAdverbs(sentences: List<SentenceInfo>) {
}

// TODO(port): HasLexicalAdverbEntry (C# line 2989, 4 lines)
internal fun JitenParser.hasLexicalAdverbEntry(text: String): Boolean = false

// TODO(port): HasConjunctionEntry (C# line 2993, 4 lines)
internal fun JitenParser.hasConjunctionEntry(text: String): Boolean = false

// TODO(port): IsVolitionalSurface (C# line 2997, 6 lines)
internal fun JitenParser.isVolitionalSurface(text: String): Boolean = false

// TODO(port): RepairLongVowels (C# line 3003, 216 lines)
internal fun JitenParser.repairLongVowels(sentences: List<SentenceInfo>) {
}

// TODO(port): TryLongVowelLookup (C# line 3223, 26 lines)
internal fun JitenParser.tryLongVowelLookup(text: String, useKanaNormalizer: Boolean = true): Boolean = false

// TODO(port): TryResolveAdjStem (C# line 3249, 28 lines)
internal fun JitenParser.tryResolveAdjStem(word: WordInfo, stem: String): Boolean = false

// TODO(port): HasIAdjectiveLookup (C# line 3277, 3 lines)
internal fun JitenParser.hasIAdjectiveLookup(text: String): Boolean = false

// TODO(port): TryDeconjugatedLongVowelLookup (C# line 3280, 21 lines)
internal fun JitenParser.tryDeconjugatedLongVowelLookup(candidateKey: String): Boolean = false

// TODO(port): FilterOrphanedMisparses (C# line 3301, 69 lines)
internal fun JitenParser.filterOrphanedMisparses(sentences: List<SentenceInfo>) {
}

// TODO(port): TryJoinShredWithFollowing (C# line 3370, 40 lines); returns C#'s out dictForm, null = false
internal fun JitenParser.tryJoinShredWithFollowing(shred: WordInfo, following: WordInfo): String? = null

// TODO(port): ValidateGrammaticalSequences (C# line 3410, 8 lines)
internal fun JitenParser.validateGrammaticalSequences(sentences: List<SentenceInfo>) {
}

// TODO(port): SplitUnknownNounTokens (C# line 3418, 81 lines)
internal fun JitenParser.splitUnknownNounTokens(sentences: List<SentenceInfo>, protectedSurfaces: Set<String>? = null) {
}

// TODO(port): BoundPrefixKanji (C# line 3499) + ReclassifyLeftoverPrefixChars (C# line 3503, 36 lines)
internal fun JitenParser.reclassifyLeftoverPrefixChars(sentences: List<SentenceInfo>) {
}

// TODO(port): CompoundVerbHeads (C# line 3539) + PinCompoundVerbTails (C# line 3547, 26 lines; VerbStemLookup.find(text, reading, lexicon))
internal fun JitenParser.pinCompoundVerbTails(sentences: List<SentenceInfo>) {
}

// TODO(port): IsBareRenyoukei (C# line 3573, 5 lines)
internal fun JitenParser.isBareRenyoukei(word: WordInfo): Boolean = false

// TODO(port): TrailingParticles (C# line 3578) + HasLookup (C# line 3583, 3 lines)
internal fun JitenParser.hasLookup(text: String): Boolean = false

// TODO(port): GetBestLookupPriority (C# line 3586, 17 lines)
internal fun JitenParser.getBestLookupPriority(text: String): Int = -1

// TODO(port): DeconjBasesAllow (C# line 3603, 14 lines)
internal fun JitenParser.deconjBasesAllow(surface: String, dictForm: String): Boolean = true

// TODO(port): RemoveInternalSokuon (C# line 3617, 13 lines)
internal fun JitenParser.removeInternalSokuon(text: String): String = text

// TODO(port): HasPrioritizedMeta (C# line 3630, 4 lines)
internal fun JitenParser.hasPrioritizedMeta(id: Int): Boolean = false

// TODO(port): ArchaicOnlyPriorityFloor (C# line 3634) + HasUsableCompoundEntry (C# line 3637, 9 lines); ids from lexicon.lookup
internal fun JitenParser.hasUsableCompoundEntry(ids: IntArray): Boolean = false

// TODO(port): PrefixBreaksFollowingCompound (C# line 3646, 10 lines)
internal fun JitenParser.prefixBreaksFollowingCompound(prefix: WordInfo, head: WordInfo, following: WordInfo): Boolean = false

// TODO(port): TryLookupUsableCompound (C# line 3656, 21 lines); returns C#'s out matchedKey, null = false
internal fun JitenParser.tryLookupUsableCompound(text: String): String? = null

// TODO(port): TryRemoveEchoedSmallVowels (C# line 3806, 20 lines); returns C#'s out result, null = false
internal fun JitenParser.tryRemoveEchoedSmallVowels(text: String): String? = null

// TODO(port): SmallVowelKanaVowel (C# line 3826, 10 lines); '\u0000' = none
internal fun JitenParser.smallVowelKanaVowel(c: Char): Char = '\u0000'

// TODO(port): KanaEndsWithVowel (C# line 3836, 7 lines). WanaKana.ToRomaji isn't in the Kotlin WanaKana: add it there
// (or a vowel-row table, cf. vowelRowOf in TextProcessing.kt) with the same results.
internal fun JitenParser.kanaEndsWithVowel(kana: Char, vowel: Char): Boolean = false

// TODO(port): IsHiraganaSurface (C# line 3843, 13 lines)
internal fun JitenParser.isHiraganaSurface(text: String): Boolean = false

// TODO(port): HasCounterLookup (C# line 3856, 6 lines; lexicon.primaryPos(id) == Counter)
internal fun JitenParser.hasCounterLookup(text: String): Boolean = false

// TODO(port): StripTrailingParticles (C# line 3889, 78 lines)
internal fun JitenParser.stripTrailingParticles(sentences: List<SentenceInfo>) {
}

// TODO(port): IsNameConnectorSymbol (C# line 3970, 3 lines)
internal fun JitenParser.isNameConnectorSymbol(w: WordInfo): Boolean = false
