// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.RepairStages.cs
package io.github.pedrubik2000.kumapie.lang.jiten

// Pipeline stages from this C# file: stubs until ported (Pipeline.kt calls them). Private helpers a stage needs go
// in this file, file-private; helpers other files use are marked "shared" below.

// TODO(port): RepairNTokenisation (C# line 1523)
internal fun MorphologicalAnalyser.repairNTokenisation(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): RepairVowelElongation (C# line 1145)
internal fun MorphologicalAnalyser.repairVowelElongation(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): RetokeniseOovBlobs (C# line 1909)
internal fun MorphologicalAnalyser.retokeniseOovBlobs(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): RepairColloquialNegativeNee (C# line 1039)
internal fun MorphologicalAnalyser.repairColloquialNegativeNee(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): RepairColloquialRanNai (C# line 1073)
internal fun MorphologicalAnalyser.repairColloquialRanNai(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): RepairIntensifierKaeru (C# line 740)
internal fun MorphologicalAnalyser.repairIntensifierKaeru(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): RepairGeminateSuffixTheft (C# line 23)
internal fun MorphologicalAnalyser.repairGeminateSuffixTheft(words: MutableList<WordInfo>, candidates: List<Int>): MutableList<WordInfo> = words

// TODO(port): RepairKatakanaShreds (C# line 175)
internal fun MorphologicalAnalyser.repairKatakanaShreds(words: MutableList<WordInfo>, candidates: List<Int>): MutableList<WordInfo> = words

// TODO(port): RepairCompoundBoundaryTheft (C# line 517)
internal fun MorphologicalAnalyser.repairCompoundBoundaryTheft(words: MutableList<WordInfo>, candidates: List<Int>): MutableList<WordInfo> = words

// TODO(port): RepairKanjiVerbShred (C# line 357)
internal fun MorphologicalAnalyser.repairKanjiVerbShred(words: MutableList<WordInfo>, candidates: List<Int>): MutableList<WordInfo> = words

// TODO(port): RepairSakkiMoraTheft (C# line 1808)
internal fun MorphologicalAnalyser.repairSakkiMoraTheft(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): CollapseReduplicatedMimetic (C# line 1856)
internal fun MorphologicalAnalyser.collapseReduplicatedMimetic(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): RepairTankaToTaNKa (C# line 838)
internal fun MorphologicalAnalyser.repairTankaToTaNKa(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): RepairDanTobashi (C# line 798)
internal fun MorphologicalAnalyser.repairDanTobashi(words: MutableList<WordInfo>): MutableList<WordInfo> = words
