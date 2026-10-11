// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.CombineStages.cs
package io.github.pedrubik2000.kumapie.lang.jiten

// Pipeline stages from this C# file: stubs until ported (Pipeline.kt calls them). Private helpers a stage needs go
// in this file, file-private; helpers other files use are marked "shared" below.

// TODO(port): CombinePrefixes (C# line 410)
internal fun MorphologicalAnalyser.combinePrefixes(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineInflections (C# line 10)
internal fun MorphologicalAnalyser.combineInflections(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineCompletionAuxVerb (C# line 855)
internal fun MorphologicalAnalyser.combineCompletionAuxVerb(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineAmounts (C# line 571)
internal fun MorphologicalAnalyser.combineAmounts(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineTte (C# line 608)
internal fun MorphologicalAnalyser.combineTte(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineAuxiliaryVerbStem (C# line 893)
internal fun MorphologicalAnalyser.combineAuxiliaryVerbStem(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineSuffix (C# line 940)
internal fun MorphologicalAnalyser.combineSuffix(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): ReclassifyOrphanedSuffixes (C# line 1018)
internal fun MorphologicalAnalyser.reclassifyOrphanedSuffixes(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineConjunctiveParticle (C# line 701)
internal fun MorphologicalAnalyser.combineConjunctiveParticle(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineAuxiliary (C# line 738)
internal fun MorphologicalAnalyser.combineAuxiliary(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineToNaru (C# line 1330)
internal fun MorphologicalAnalyser.combineToNaru(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineAdverbialParticle (C# line 695)
internal fun MorphologicalAnalyser.combineAdverbialParticle(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineVerbDependant (C# line 682)
internal fun MorphologicalAnalyser.combineVerbDependant(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineParticles (C# line 1076)
internal fun MorphologicalAnalyser.combineParticles(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineQuotativeToIu (C# line 613)
internal fun MorphologicalAnalyser.combineQuotativeToIu(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CombineFinal (C# line 1234)
internal fun MorphologicalAnalyser.combineFinal(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// Shared (RepairStages uses them): ported with the stubs.

internal fun containsText(forms: List<DeconjugationForm>, target: String): Boolean {
    for (i in forms.indices) if (forms[i].text == target) return true
    return false
}

/** SpecialCases2 grouped by first token text, in RuleData order (C#'s List<(string Second, PartOfSpeech? Pos)>). */
internal val SpecialCases2Dict: Map<String, List<SpecialCase2>> = SpecialCases2.groupBy { it.first }

/** SpecialCases3 grouped by first token text, in RuleData order (C#'s List<(string Second, string Third, PartOfSpeech? Pos)>). */
internal val SpecialCases3Dict: Map<String, List<SpecialCase3>> = SpecialCases3.groupBy { it.first }
