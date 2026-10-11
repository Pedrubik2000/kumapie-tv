// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.Disambiguation.cs
package io.github.pedrubik2000.kumapie.lang.jiten

// Pipeline stages from this C# file: stubs until ported (Pipeline.kt calls them). Private helpers a stage needs go
// in this file, file-private; helpers other files use are marked "shared" below.

// TODO(port): ApplyContextPins (C# line 9)
internal fun MorphologicalAnalyser.applyContextPins(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): FixReadingAmbiguity (C# line 539)
internal fun MorphologicalAnalyser.fixReadingAmbiguity(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// Shared (RepairStages and RewriteRules use it): ported with the stubs.
/** A PreMatchedWordId pin skips deconjugation, so without this chain every pin emits a bare lemma. */
internal fun MorphologicalAnalyser.pinnedConjugationProcess(surface: String, dictionaryForm: String): MutableList<String>? {
    val hiraSurface = normalizeToHiragana(surface)
    val hiraDict = normalizeToHiragana(dictionaryForm)
    if (hiraSurface == hiraDict) return null
    for (form in pipelineCachedDeconjugate(hiraSurface)) if (form.text == hiraDict) return form.process.toMutableList()
    return null
}
