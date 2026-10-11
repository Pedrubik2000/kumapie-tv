// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/MorphologicalAnalyser.cs
// (state and deconjugation caches only; Parse/ParseBatch and the stages come later)
package io.github.pedrubik2000.kumapie.lang.jiten

import com.worksap.nlp.sudachi.Tokenizer

/**
 * Jiten's MorphologicalAnalyser state. The stages are written in other files as extension functions on this class,
 * `internal fun MorphologicalAnalyser.stageName(words: MutableList<WordInfo>): MutableList<WordInfo>`, so they reach
 * the internal members below like C#'s partial-class members.
 *
 * The callbacks are wired to [lexicon] as Parser.cs wires them; with no lexicon they stay null, which the stages treat
 * as "no dictionary" exactly as in C# (`HasCompoundLookup == null || ...`).
 */
class MorphologicalAnalyser(val lexicon: JitenLexicon?) {
    var hasCompoundLookup: ((String) -> Boolean)? = lexicon?.let { it::hasLookupForCompound }
    var hasNonNameCompoundLookup: ((String) -> Boolean)? = lexicon?.let { it::hasNonNameLookup }
    var hasPrioritizedNonNameCompoundLookup: ((String) -> Boolean)? = lexicon?.let { it::hasPrioritizedNonNameLookup }
    var hasKanaAppropriateCompoundLookup: ((String) -> Boolean)? = lexicon?.let { it::hasKanaAppropriateLookup }
    var hasSuruVerbCompoundLookup: ((String) -> Boolean)? = lexicon?.let { it::hasSuruVerbLookup }
    var getNonNameCompoundWordId: ((String) -> Int?)? = lexicon?.let { it::getNonNameCompoundId }
    var getNonNameCompoundFrequencyRank: ((String) -> Int?)? = lexicon?.let { it::getBestNonNameFrequencyRank }
    var hasVerbOrAdjectiveLookup: ((String) -> Boolean)? = lexicon?.let { it::hasVerbOrAdjectiveLookup }
    var hasExpressionLookup: ((String) -> Boolean)? = lexicon?.let { it::hasExpressionLookup }
    var hasCounterSenseLookup: ((String) -> Boolean)? = lexicon?.let { it::hasCounterSenseAvailable }

    /**
     * RetokeniseOovBlobs re-runs Sudachi on an OOV kana blob alone (Sudachi only blobs it because of the context).
     * Replaces C#'s captured _sudachiConfigPath/_sudachiDicPath/_sudachiMode/_sudachiUserDictCsv; set it per parse.
     */
    internal var tokenizer: Tokenizer? = null
    internal var sudachiMode: Tokenizer.SplitMode = Tokenizer.SplitMode.B
    internal var retokeniseOovDisabled: Boolean = false

    /** Request-path parse in Jiten (takes a reserved Sudachi slot); kept for parity. */
    var interactive: Boolean = false

    /** Set for the duration of a pipeline run (RunPipeline creates it and clears it at the end). */
    internal var pipelineDeconjCache: HashMap<String, List<DeconjugationForm>>? = null

    internal fun pipelineCachedDeconjugate(hiragana: String): List<DeconjugationForm> {
        val cache = pipelineDeconjCache
        cache?.get(hiragana)?.let { return it }
        val forms = Deconjugator.instance.deconjugate(hiragana)
        cache?.putIfAbsent(hiragana, forms)
        return forms
    }

    /** Deconjugates part1+part2 with katakana folded to hiragana and ー after kana turned into that kana's vowel. */
    internal fun pipelineCachedDeconjugateConcat(part1: String, part2: String): List<DeconjugationForm> {
        val buf = CharArray(part1.length + part2.length)
        part1.toCharArray(buf, 0)
        part2.toCharArray(buf, part1.length)
        for (i in buf.indices) {
            val c = buf[i]
            if (c in 'ァ'..'ヶ') buf[i] = c - 0x60
        }
        if ('ー' in buf) {
            for (i in 1 until buf.size) {
                if (buf[i] != 'ー') continue
                val prev = buf[i - 1]
                if (prev.code in 0x3040 until 0x3140) buf[i] = KanaVowelMap[prev.code - 0x3040]
            }
        }
        val key = String(buf)
        pipelineDeconjCache!![key]?.let { return it }
        val forms = Deconjugator.instance.deconjugate(key)
        pipelineDeconjCache!!.putIfAbsent(key, forms)
        return forms
    }

    internal companion object {
        /** Hiragana (offset 0x3040) -> the vowel a following ー stands for; '\u0000' for anything else. */
        val KanaVowelMap: CharArray = CharArray(0x100).apply {
            for (c in "おこそとのほもよろをごぞどぼぽょ") this[c.code - 0x3040] = 'う'
            for (c in "うくすつぬふむゆるぐずづぶぷゅ") this[c.code - 0x3040] = 'う'
            for (c in "えけせてねへめれげぜでべぺ") this[c.code - 0x3040] = 'え'
            for (c in "いきしちにひみりぎじぢびぴ") this[c.code - 0x3040] = 'い'
            for (c in "あかさたなはまやらわがざだばぱゃ") this[c.code - 0x3040] = 'あ'
        }
    }
}
