// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.RewriteRules.cs
package io.github.pedrubik2000.kumapie.lang.jiten

// Pipeline stages from this C# file: stubs until ported (Pipeline.kt calls them). Private helpers a stage needs go
// in this file, file-private; helpers other files use are marked "shared" below.

// TODO(port): ApplyTokenRewriteRulesEarly (C# line 1276)
internal fun MorphologicalAnalyser.applyTokenRewriteRulesEarly(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): ApplyTokenRewriteRulesLate (C# line 1279)
internal fun MorphologicalAnalyser.applyTokenRewriteRulesLate(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): ApplyTokenRewriteRulesCleanup (C# line 1282)
internal fun MorphologicalAnalyser.applyTokenRewriteRulesCleanup(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): ApplyTokenRewriteRulesReading (C# line 1285)
internal fun MorphologicalAnalyser.applyTokenRewriteRulesReading(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// Shared (Disambiguation uses it): ported with the stubs.
internal fun isClauseBoundary(neighbour: WordInfo?): Boolean =
    neighbour == null || neighbour.partOfSpeech == PartOfSpeech.Symbol ||
        neighbour.partOfSpeech == PartOfSpeech.SupplementarySymbol || neighbour.partOfSpeech == PartOfSpeech.BlankSpace
