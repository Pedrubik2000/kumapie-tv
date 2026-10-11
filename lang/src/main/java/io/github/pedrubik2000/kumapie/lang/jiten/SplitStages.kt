// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.SplitStages.cs
package io.github.pedrubik2000.kumapie.lang.jiten

// Pipeline stages from this C# file: stubs until ported (Pipeline.kt calls them). Private helpers a stage needs go
// in this file, file-private; helpers other files use are marked "shared" below.

// TODO(port): SplitOovGarbageTokens (C# line 983)
internal fun MorphologicalAnalyser.splitOovGarbageTokens(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitCompoundAuxiliaryVerbs (C# line 10)
internal fun MorphologicalAnalyser.splitCompoundAuxiliaryVerbs(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitUnresolvableCompoundVerbs (C# line 118)
internal fun MorphologicalAnalyser.splitUnresolvableCompoundVerbs(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitUnresolvableRenyokeiNounCompounds (C# line 239)
internal fun MorphologicalAnalyser.splitUnresolvableRenyokeiNounCompounds(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitUnresolvablePrefixedAdjectives (C# line 306)
internal fun MorphologicalAnalyser.splitUnresolvablePrefixedAdjectives(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitTatteParticle (C# line 512)
internal fun MorphologicalAnalyser.splitTatteParticle(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitUnattestedToAdverbs (C# line 645)
internal fun MorphologicalAnalyser.splitUnattestedToAdverbs(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitTanSuffix (C# line 453)
internal fun MorphologicalAnalyser.splitTanSuffix(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitTawakeNoun (C# line 689)
internal fun MorphologicalAnalyser.splitTawakeNoun(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitLexicalisedKaratte (C# line 1185)
internal fun MorphologicalAnalyser.splitLexicalisedKaratte(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitDoushiteContraction (C# line 731)
internal fun MorphologicalAnalyser.splitDoushiteContraction(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitEmphaticMoSuru (C# line 774)
internal fun MorphologicalAnalyser.splitEmphaticMoSuru(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// TODO(port): SplitUnresolvableSuruCompounds (C# line 371)
internal fun MorphologicalAnalyser.splitUnresolvableSuruCompounds(words: MutableList<WordInfo>): MutableList<WordInfo> = words

// Shared (RepairStages and Disambiguation use them): ported with the stubs.

internal val RenyokeiToGodanBase: Map<Char, Char> = hashMapOf(
    'き' to 'く', 'ぎ' to 'ぐ', 'し' to 'す', 'ち' to 'つ', 'に' to 'ぬ',
    'び' to 'ぶ', 'み' to 'む', 'り' to 'る', 'い' to 'う',
)

internal fun MorphologicalAnalyser.renyokeiSurfaceToVerb(s: String): String? {
    val lookup = hasNonNameCompoundLookup
    if (s.length < 2 || lookup == null) return null
    val ichidan = s + 'る'
    if (lookup(ichidan)) return ichidan
    RenyokeiToGodanBase[s.last()]?.let { baseEnd ->
        val godan = s.dropLast(1) + baseEnd
        if (lookup(godan)) return godan
    }
    return null
}

internal data class GrammarToken(val text: String, val reading: String, val pos: PartOfSpeech, val sec: PartOfSpeechSection)

internal val GrammarTokenTable: List<GrammarToken> = listOf(
    GrammarToken("って", "ッテ", PartOfSpeech.Particle, PartOfSpeechSection.AdverbialParticle),
    GrammarToken("った", "ッタ", PartOfSpeech.Auxiliary, PartOfSpeechSection.None),
    GrammarToken("わけ", "ワケ", PartOfSpeech.Noun, PartOfSpeechSection.CommonNoun),
    GrammarToken("こと", "コト", PartOfSpeech.Noun, PartOfSpeechSection.CommonNoun),
    // Pronoun, not CommonNoun, so これ after って doesn't trip the hasLeftoverNoun abort (るってこれ).
    GrammarToken("これ", "コレ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("それ", "ソレ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("あれ", "アレ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("どれ", "ドレ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("ここ", "ココ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("そこ", "ソコ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("どこ", "ドコ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("ない", "ナイ", PartOfSpeech.IAdjective, PartOfSpeechSection.PossibleDependant),
    GrammarToken("から", "カラ", PartOfSpeech.Particle, PartOfSpeechSection.ConjunctionParticle),
    GrammarToken("けど", "ケド", PartOfSpeech.Particle, PartOfSpeechSection.ConjunctionParticle),
    GrammarToken("だけ", "ダケ", PartOfSpeech.Particle, PartOfSpeechSection.AdverbialParticle),
    GrammarToken("でも", "デモ", PartOfSpeech.Particle, PartOfSpeechSection.AdverbialParticle),
    GrammarToken("の", "ノ", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("は", "ハ", PartOfSpeech.Particle, PartOfSpeechSection.BindingParticle),
    GrammarToken("が", "ガ", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("も", "モ", PartOfSpeech.Particle, PartOfSpeechSection.BindingParticle),
    GrammarToken("で", "デ", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("に", "ニ", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("を", "ヲ", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("と", "ト", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("か", "カ", PartOfSpeech.Particle, PartOfSpeechSection.AdverbialParticle),
    // Longest match keeps だろ whole so ろ isn't stranded as a leftover noun (…ってことだろ).
    GrammarToken("だろう", "ダロウ", PartOfSpeech.Auxiliary, PartOfSpeechSection.None),
    GrammarToken("だろ", "ダロ", PartOfSpeech.Auxiliary, PartOfSpeechSection.None),
    GrammarToken("だ", "ダ", PartOfSpeech.Auxiliary, PartOfSpeechSection.None),
    // Keeps …るって|なれば from leaving a ば-final leftover noun that aborts the split.
    GrammarToken("なれば", "ナレバ", PartOfSpeech.Conjunction, PartOfSpeechSection.None),
    GrammarToken("ならば", "ナラバ", PartOfSpeech.Conjunction, PartOfSpeechSection.None),
    GrammarToken("な", "ナ", PartOfSpeech.Particle, PartOfSpeechSection.SentenceEndingParticle),
    GrammarToken("ね", "ネ", PartOfSpeech.Particle, PartOfSpeechSection.SentenceEndingParticle),
    GrammarToken("よ", "ヨ", PartOfSpeech.Particle, PartOfSpeechSection.SentenceEndingParticle),
    GrammarToken("さ", "サ", PartOfSpeech.Particle, PartOfSpeechSection.SentenceEndingParticle),
    GrammarToken("わ", "ワ", PartOfSpeech.Particle, PartOfSpeechSection.SentenceEndingParticle),
    GrammarToken("ん", "ン", PartOfSpeech.Particle, PartOfSpeechSection.Juntaijoushi),
    GrammarToken("た", "タ", PartOfSpeech.Auxiliary, PartOfSpeechSection.None),
)

internal fun deconjugatesToVerb(hiragana: String): Boolean =
    Deconjugator.instance.deconjugate(hiragana).any { f -> f.tags.any { it.startsWith("v") } }

internal fun MorphologicalAnalyser.tokenizeGrammarRemainder(text: String, startOffset: Int): MutableList<WordInfo> {
    val tokens = ArrayList<WordInfo>()
    var i = 0
    while (i < text.length) {
        var markerLen = 0
        var marker: GrammarToken? = null
        for (g in GrammarTokenTable)
            if (g.text.length > markerLen && text.startsWith(g.text, i)) { markerLen = g.text.length; marker = g }

        // う-row dictionary-form verbs only (ってのは+わかる); looser tails mis-cut clusters (のはだな → の|肌|な, んだ).
        var verbLen = 0
        var tailPos = PartOfSpeech.Verb
        val lookup = hasNonNameCompoundLookup
        if (lookup != null) {
            var len = minOf(text.length - i, 6)
            while (len > markerLen && len >= 2) {
                val cand = text.substring(i, i + len)
                if (cand.last() in "うくぐすつぬぶむる" && lookup(cand) && deconjugatesToVerb(cand)) { verbLen = len; break }
                len--
            }
        }

        // Keeps a conjugated i-adjective (よかった) from shredding into よ|か|った; window 8 fits ありがたかった.
        if (verbLen == 0 && lookup != null) {
            var len = minOf(text.length - i, 8)
            while (len > markerLen && len >= 3 && verbLen == 0) {
                val cand = text.substring(i, i + len)
                if (cand.last() == 'た' || cand.last() == 'ば') {
                    for (f in Deconjugator.instance.deconjugate(cand)) {
                        if (f.text == cand || !lookup(f.text)) continue
                        if (f.tags.none { it.startsWith("adj") }) continue
                        verbLen = len
                        tailPos = PartOfSpeech.IAdjective
                        break
                    }
                }
                len--
            }
        }

        if (verbLen > 0) {
            val w = text.substring(i, i + verbLen)
            tokens.add(WordInfo().apply {
                this.text = w; dictionaryForm = w; normalizedForm = w
                reading = WanaKana.toKatakana(normalizeToHiragana(w))
                partOfSpeech = tailPos
                this.startOffset = if (startOffset >= 0) startOffset + i else -1
                endOffset = if (startOffset >= 0) startOffset + i + verbLen else -1
            })
            i += verbLen
            continue
        }

        val m = marker ?: break

        val gramText = text.substring(i, i + markerLen)
        tokens.add(WordInfo().apply {
            this.text = gramText; dictionaryForm = gramText; normalizedForm = gramText; reading = m.reading
            partOfSpeech = m.pos; partOfSpeechSection1 = m.sec
            this.startOffset = if (startOffset >= 0) startOffset + i else -1
            endOffset = if (startOffset >= 0) startOffset + i + markerLen else -1
        })
        i += markerLen
    }

    if (i < text.length) {
        val leftover = text.substring(i)
        tokens.add(WordInfo().apply {
            this.text = leftover; dictionaryForm = leftover; normalizedForm = leftover; reading = leftover
            partOfSpeech = PartOfSpeech.Noun; partOfSpeechSection1 = PartOfSpeechSection.CommonNoun
            this.startOffset = if (startOffset >= 0) startOffset + i else -1
            endOffset = if (startOffset >= 0) startOffset + text.length else -1
        })
    }

    return tokens
}
