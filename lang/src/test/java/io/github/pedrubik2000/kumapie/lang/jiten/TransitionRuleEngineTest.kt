// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Tests/TransitionRuleEngineTests.cs
// Left out: the diagnostics test (diagnostics aren't ported) and the CandidateSelectionResult tests (Scoring).
package io.github.pedrubik2000.kumapie.lang.jiten

import io.github.pedrubik2000.kumapie.lang.jiten.PartOfSpeech as P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("FunctionName", "UNUSED_VARIABLE")
class TransitionRuleEngineTest {
    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun w(text: String, pos: P, dictForm: String? = null, section1: PartOfSpeechSection = PartOfSpeechSection.None) =
        SentenceWord(WordInfo().apply {
            this.text = text
            dictionaryForm = dictForm ?: text
            normalizedForm = text
            partOfSpeech = pos
            partOfSpeechSection1 = section1
        }, 0, text.length)

    private fun sentence(vararg tokens: SentenceWord): MutableList<SentenceWord> = tokens.toMutableList()

    private fun texts(words: List<SentenceWord>) = words.map { it.word.text }

    // Lookup that always returns false (no merge possible)
    private val NoLookup: (String) -> Boolean = { false }

    // Lookup that returns true for a given merged form
    private fun withLookup(merged: String): (String) -> Boolean = { it == merged }

    /** C#'s FormCandidate(word, form, 0, formText): the window takes its word and form directly. */
    private class Candidate(val word: JmDictWord, val form: JmDictWordForm)

    private fun makeCandidate(formText: String, vararg pos: String) = Candidate(
        JmDictWord(1, pos.toList(), null, WordOrigin.Unknown, emptyList(), emptyList(), false),
        JmDictWordForm(1, 0, formText, JmDictFormType.KanaForm, null, null))

    private fun makeWindow(
        candidate: Candidate,
        prevPOS: List<P>? = null, nextPOS: List<P>? = null,
        prevText: String? = null, nextText: String? = null,
    ) = ScoringWindow(candidate.word, candidate.form, null,
        if (prevPOS != null) PosMask.fromList(prevPOS) else 0, prevPOS != null, prevText,
        if (nextPOS != null) PosMask.fromList(nextPOS) else 0, nextPOS != null, nextText)

    private fun evaluateSoftRules(window: ScoringWindow): Pair<Int, List<String>> {
        val rules = ArrayList<String>()
        val mask = TransitionRuleEngine.computeContextApplicableMask(
            window.prevMask, window.hasPrev, window.prevText,
            window.nextMask, window.hasNext, window.nextText)
        val bonus = TransitionRuleEngine.evaluateSoftRules(window, mask, rules)
        return bonus to rules
    }

    // -----------------------------------------------------------------------
    // Phase 1: leading-aux-strip
    // -----------------------------------------------------------------------

    @Test fun LeadingAuxStrip_RemovesSingleLeadingVerbOnlyAux() {
        val words = sentence(w("られる", P.Auxiliary, "られる"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertTrue(words.isEmpty())
    }

    @Test fun LeadingAuxStrip_RemovesAllLeadingVerbAttachingAux() {
        // られる ます → both stripped
        val words = sentence(
            w("られる", P.Auxiliary, "られる"),
            w("ます", P.Auxiliary, "ます"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertTrue(words.isEmpty())
    }

    @Test fun LeadingAuxStrip_RemovesLeadingVerbOrAdjAux() {
        // た at sentence start → stripped
        val words = sentence(
            w("た", P.Auxiliary, "た"),
            w("食べる", P.Verb, "食べる"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("食べる"), texts(words))
    }

    @Test fun LeadingAuxStrip_DoesNotRemoveNonVerbAttachingAux() {
        // An auxiliary not in the aux dict forms should NOT be stripped
        val words = sentence(w("だ", P.Auxiliary, "だ"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(1, words.size)
    }

    @Test fun LeadingAuxStrip_PreservesNonAuxFirst() {
        val words = sentence(
            w("食べる", P.Verb),
            w("られる", P.Auxiliary, "られる"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("食べる", "られる"), texts(words))
    }

    // -----------------------------------------------------------------------
    // Phase 2a: aux-must-follow-verb (VerbOnlyAuxDictForms)
    // -----------------------------------------------------------------------

    @Test fun AuxMustFollowVerb_RemovesAuxAfterParticle() {
        // 本/Noun + を/Particle + られる/Aux → られる removed
        val words = sentence(
            w("本", P.Noun),
            w("を", P.Particle),
            w("られる", P.Auxiliary, "られる"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("本", "を"), texts(words))
    }

    @Test fun AuxMustFollowVerb_KeepsAuxAfterVerb() {
        val words = sentence(
            w("食べ", P.Verb),
            w("られる", P.Auxiliary, "られる"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("食べ", "られる"), texts(words))
    }

    @Test fun AuxMustFollowVerb_KeepsAuxAfterAux() {
        // Verb + causative-aux + passive-aux — られる follows an Auxiliary, which is valid
        val words = sentence(
            w("食べ", P.Verb),
            w("させ", P.Auxiliary, "させる"),
            w("られる", P.Auxiliary, "られる"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("食べ", "させ", "られる"), texts(words))
    }

    @Test fun AuxMustFollowVerb_MergesWhenLookupSucceeds() {
        // 食べ + られる → 食べられる (valid compound)
        val words = sentence(
            w("食べ", P.Verb),
            w("られる", P.Auxiliary, "られる"))

        // Artificially make the rule fail by giving prev POS = Noun
        words[0] = SentenceWord(WordInfo().apply {
            text = "本"
            dictionaryForm = "本"
            normalizedForm = "本"
            partOfSpeech = P.Noun
        }, 0, 1)

        TransitionRuleEngine.applyHardRules(words, withLookup("本られる"))
        assertEquals(1, words.size)
        assertEquals("本られる", words[0].word.text)
        assertEquals(P.Noun, words[0].word.partOfSpeech)
    }

    // -----------------------------------------------------------------------
    // Phase 2b: verb-or-adj-aux-must-follow-content (VerbOrAdjAuxDictForms)
    // -----------------------------------------------------------------------

    @Test fun VerbOrAdjAuxMustFollowContent_RemovesAfterParticle() {
        // で + た → た removed
        val words = sentence(
            w("で", P.Particle),
            w("た", P.Auxiliary, "た"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("で"), texts(words))
    }

    @Test fun VerbOrAdjAuxMustFollowContent_KeepsAfterIAdj() {
        // 美味しかっ/IAdjective + た/Aux → kept
        val words = sentence(
            w("美味しかっ", P.IAdjective),
            w("た", P.Auxiliary, "た"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("美味しかっ", "た"), texts(words))
    }

    @Test fun VerbOrAdjAuxMustFollowContent_KeepsAfterVerb() {
        val words = sentence(
            w("飲ん", P.Verb),
            w("だ", P.Auxiliary, "た")) // だ is surface form of た
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("飲ん", "だ"), texts(words))
    }

    // -----------------------------------------------------------------------
    // Phase 3: counter-must-follow-numberlike
    // -----------------------------------------------------------------------

    @Test fun Counter_AtSentenceStart_ReclassifiedAsNoun() {
        val words = sentence(w("個", P.Counter, "個"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(1, words.size)
        assertEquals(P.Noun, words[0].word.partOfSpeech)
    }

    @Test fun Counter_AfterNonNumeric_ReclassifiedAsNoun() {
        val words = sentence(
            w("美味しい", P.IAdjective),
            w("個", P.Counter, "個"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(P.Noun, words[1].word.partOfSpeech)
    }

    @Test fun Counter_AfterNumeral_Unchanged() {
        val words = sentence(
            w("三", P.Numeral),
            w("個", P.Counter, "個"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(P.Counter, words[1].word.partOfSpeech)
    }

    @Test fun Counter_AfterNoun_Unchanged() {
        val words = sentence(
            w("リンゴ", P.Noun),
            w("個", P.Counter, "個"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(P.Counter, words[1].word.partOfSpeech)
    }

    @Test fun SuffixWithCounterSection_AtStart_ReclassifiedAsNoun() {
        val words = sentence(w("枚", P.Suffix, "枚", PartOfSpeechSection.Counter))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(P.Noun, words[0].word.partOfSpeech)
    }

    // -----------------------------------------------------------------------
    // Phase 4: sfp-must-be-near-clause-end
    // -----------------------------------------------------------------------

    @Test fun Sfp_FollowedByContentWord_MergedOrRemoved() {
        // 食べる + よ(SFP) + 明日(Noun) → よ should be merged/removed
        val words = sentence(
            w("食べる", P.Verb),
            w("よ", P.Particle, "よ", PartOfSpeechSection.SentenceEndingParticle),
            w("明日", P.Noun))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("食べる", "明日"), texts(words))
    }

    @Test fun Sfp_AtSentenceEnd_Preserved() {
        val words = sentence(
            w("行く", P.Verb),
            w("よ", P.Particle, "よ", PartOfSpeechSection.SentenceEndingParticle))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("行く", "よ"), texts(words))
    }

    @Test fun Sfp_FollowedByAnotherParticle_Preserved() {
        // よ + ね → both are SFPs, valid chain
        val words = sentence(
            w("行く", P.Verb),
            w("よ", P.Particle, "よ", PartOfSpeechSection.SentenceEndingParticle),
            w("ね", P.Particle, "ね", PartOfSpeechSection.SentenceEndingParticle))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("行く", "よ", "ね"), texts(words))
    }

    @Test fun Sfp_FollowedByAuxiliary_Preserved() {
        val words = sentence(
            w("行く", P.Verb),
            w("ぞ", P.Particle, "ぞ", PartOfSpeechSection.SentenceEndingParticle),
            w("た", P.Auxiliary, "た"))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("行く", "ぞ", "た"), texts(words))
    }

    @Test fun Sfp_MergesWithPrevious_WhenLookupSucceeds() {
        // 食べ + よ(SFP) + 明日(Noun) → 食べよ exists in lookups → merged
        val words = sentence(
            w("食べ", P.Verb),
            w("よ", P.Particle, "よ", PartOfSpeechSection.SentenceEndingParticle),
            w("明日", P.Noun))
        TransitionRuleEngine.applyHardRules(words, withLookup("食べよ"))
        assertEquals(2, words.size)
        assertEquals("食べよ", words[0].word.text)
        assertEquals("明日", words[1].word.text)
    }

    @Test fun Sfp_AfterPlainPredicate_BeforeVocative_Preserved() {
        val words = sentence(
            w("やべえ", P.IAdjective),
            w("よ", P.Particle, "よ", PartOfSpeechSection.SentenceEndingParticle),
            w("母ちゃん", P.CommonNoun))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("やべえ", "よ", "母ちゃん"), texts(words))
    }

    @Test fun Sfp_MergesWithNounPrev_PreservesNounPos_NotForcedToVerb() {
        // SFP merge must inherit the predecessor's POS, not force P.Verb.
        // Previous word is a Noun; the merged token must remain a Noun.
        // The noun is not clause-initial: a clause-initial noun + よ is a vocative and stays split.
        val words = sentence(
            w("古い", P.IAdjective),
            w("本", P.Noun),
            w("よ", P.Particle, "よ", PartOfSpeechSection.SentenceEndingParticle),
            w("読む", P.Verb))
        TransitionRuleEngine.applyHardRules(words, withLookup("本よ"))

        assertEquals(3, words.size)
        assertEquals("本よ", words[1].word.text)
        assertEquals(P.Noun, words[1].word.partOfSpeech)
        assertEquals("読む", words[2].word.text)
    }

    @Test fun Sfp_ClauseInitialNounYo_IsVocative_NotMerged() {
        // 主よどうか…: merging would fabricate the imperative of a verb 主る.
        val words = sentence(
            w("主", P.Noun),
            w("よ", P.Particle, "よ", PartOfSpeechSection.SentenceEndingParticle),
            w("導き", P.Verb))
        TransitionRuleEngine.applyHardRules(words, withLookup("主よ"))

        assertEquals(listOf("主", "よ", "導き"), texts(words))
    }

    @Test fun Sfp_AtSentenceStart_FollowedByContent_Removed() {
        // よ(SFP) + 食べる(Verb) at start → leading-aux-strip doesn't apply (not aux), but SFP rule fires
        val words = sentence(
            w("よ", P.Particle, "よ", PartOfSpeechSection.SentenceEndingParticle),
            w("食べる", P.Verb))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("食べる"), texts(words))
    }

    @Test fun Sfp_Ne_FollowedByVerb_Removed() {
        val words = sentence(
            w("本", P.Noun),
            w("ね", P.Particle, "ね", PartOfSpeechSection.SentenceEndingParticle),
            w("読む", P.Verb))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("本", "読む"), texts(words))
    }

    @Test fun Sfp_NonSentenceEndingParticle_MidClause_NotAffected() {
        // Case-marking particle を mid-clause → rule should NOT fire
        val words = sentence(
            w("本", P.Noun),
            w("を", P.Particle, "を", PartOfSpeechSection.CaseMarkingParticle),
            w("読む", P.Verb))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(listOf("本", "を", "読む"), texts(words))
    }

    @Test fun Sfp_AfterAuxiliary_FollowedByContent_Preserved() {
        // だ(Aux) + な(SFP) + 気をつける(Verb) → な must be preserved (valid terminal sub-clause expression)
        // This prevents "だな" from being incorrectly re-merged when Sudachi misparsed だな as 棚
        val words = sentence(
            w("だ", P.Auxiliary),
            w("な", P.Particle, "な", PartOfSpeechSection.SentenceEndingParticle),
            w("気をつける", P.Verb))
        TransitionRuleEngine.applyHardRules(words, withLookup("だな"))
        assertEquals(listOf("だ", "な", "気をつける"), texts(words))
    }

    // -----------------------------------------------------------------------
    // Phase 5: prefix-must-precede-content
    // -----------------------------------------------------------------------

    @Test fun Prefix_AtSentenceEnd_ReclassifiedAsNoun() {
        val words = sentence(w("御", P.Prefix))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(1, words.size)
        assertEquals(P.Noun, words[0].word.partOfSpeech)
    }

    @Test fun Prefix_BeforeParticle_ReclassifiedAsNoun() {
        val words = sentence(
            w("御", P.Prefix),
            w("が", P.Particle))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(P.Noun, words[0].word.partOfSpeech)
    }

    @Test fun Prefix_BeforeNoun_Unchanged() {
        val words = sentence(
            w("御", P.Prefix),
            w("飯", P.Noun))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(P.Prefix, words[0].word.partOfSpeech)
    }

    @Test fun Prefix_BeforeVerb_Unchanged() {
        val words = sentence(
            w("御", P.Prefix),
            w("覧", P.Verb))
        TransitionRuleEngine.applyHardRules(words, NoLookup)
        assertEquals(P.Prefix, words[0].word.partOfSpeech)
    }

    // -----------------------------------------------------------------------
    // Soft rules: synergies
    // -----------------------------------------------------------------------

    @Test fun NounParticleSynergy_FiresWhenNounFollowedByCommonParticle() {
        val candidate = makeCandidate("本", "n")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Particle), nextText = "が")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(40, bonus)
        assertTrue("noun-particle-synergy" in rules)
    }

    @Test fun NounCopulaSynergy_FiresWhenNounFollowedByCopula() {
        val candidate = makeCandidate("学生", "n")
        val window = makeWindow(candidate, nextText = "です")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(65, bonus)
        assertTrue("noun-copula-synergy" in rules)
    }

    @Test fun NaAdjConnectorSynergy_FiresWhenNaAdjFollowedByNa() {
        val candidate = makeCandidate("静か", "adj-na")
        val window = makeWindow(candidate, nextText = "な")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(30, bonus)
        assertTrue("na-adj-connector-synergy" in rules)
    }

    @Test fun AdverbVerbSynergy_FiresWhenAdverbFollowedByVerb() {
        val candidate = makeCandidate("とても", "adv")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Verb), nextText = "食べる")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(20, bonus)
        assertTrue("adverb-verb-synergy" in rules)
    }

    @Test fun VerbAuxSynergy_FiresWhenAuxFollowsVerb() {
        val candidate = makeCandidate("た", "aux")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Verb), prevText = "食べ")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(30, bonus)
        assertTrue("verb-aux-synergy" in rules)
        assertTrue("verb-sentence-final-synergy" in rules)
    }

    // -----------------------------------------------------------------------
    // Soft rules: penalties
    // -----------------------------------------------------------------------

    @Test fun SingleKanaPenalty_FiresIndependentlyForLeftAndRight() {
        val candidate = makeCandidate("あ", "n")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Noun), prevText = "い",
            nextPOS = listOf(P.Noun), nextText = "う")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(-80, bonus)
        assertTrue("single-kana-penalty-left" in rules)
        assertTrue("single-kana-penalty-right" in rules)
    }

    @Test fun SingleKanaPenalty_DoesNotFireForParticles() {
        val candidate = makeCandidate("が", "prt")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Noun), prevText = "あ",
            nextPOS = listOf(P.Noun), nextText = "い")

        val (bonus, rules) = evaluateSoftRules(window)
        assertFalse("single-kana-penalty-left" in rules)
        assertFalse("single-kana-penalty-right" in rules)
    }

    @Test fun ParticleParticlePenalty_FiresForLeftAndRightIndependently() {
        val candidate = makeCandidate("は", "prt")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Particle), prevText = "が",
            nextPOS = listOf(P.Particle), nextText = "も")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(-40, bonus)
        assertTrue("particle-particle-penalty-left" in rules)
        assertTrue("particle-particle-penalty-right" in rules)
    }

    @Test fun ParticleParticlePenalty_OnlyLeftFires() {
        val candidate = makeCandidate("は", "prt")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Particle), prevText = "が",
            nextPOS = listOf(P.Verb), nextText = "食べる")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(-20, bonus)
        assertTrue("particle-particle-penalty-left" in rules)
        assertFalse("particle-particle-penalty-right" in rules)
    }

    // -----------------------------------------------------------------------
    // Soft rules: combined scenarios
    // -----------------------------------------------------------------------

    @Test fun NounWithParticleAndCopula_BothSynergiesFire() {
        // na-adjective followed by な gets both noun-like synergies if context matches
        val candidate = makeCandidate("静か", "adj-na")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Particle), nextText = "な")

        val (bonus, rules) = evaluateSoftRules(window)
        // CandidateIsNounLike matches adj-na, NextIsCommonParticle does NOT match (な not in CommonParticles)
        // But NextIsNaConnector matches
        assertTrue("na-adj-connector-synergy" in rules)
    }

    @Test fun NoDaSynergy_FiresWhenVerbPrecedesNoFollowedByDa() {
        // 行くのだ — verb + の + だ → explanatory pattern
        val candidate = makeCandidate("の", "prt")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Verb), prevText = "行く",
            nextText = "だ")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(25, bonus)
        assertTrue("no-da-synergy" in rules)
    }

    @Test fun NoDaSynergy_FiresWithAuxPrevAndDesuNext() {
        // 食べているのです — auxiliary (いる) + の + です
        val candidate = makeCandidate("の", "prt")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Auxiliary), prevText = "いる",
            nextText = "です")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(25, bonus)
        assertTrue("no-da-synergy" in rules)
    }

    @Test fun NoDaSynergy_FiresWithIAdjPrev() {
        // 寒いのだ — i-adjective + の + だ
        val candidate = makeCandidate("の", "prt")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.IAdjective), prevText = "寒い",
            nextText = "だ")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(25, bonus)
        assertTrue("no-da-synergy" in rules)
    }

    @Test fun NoDaSynergy_DoesNotFireForPossessiveNo() {
        // 私のだ — noun + の + だ (possessive, not explanatory)
        val candidate = makeCandidate("の", "prt")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Noun), prevText = "私",
            nextText = "だ")

        val (bonus, rules) = evaluateSoftRules(window)
        assertFalse("no-da-synergy" in rules)
    }

    @Test fun NoDaSynergy_DoesNotFireWithoutCopulaNext() {
        // 行くのに — の followed by に, not copula
        val candidate = makeCandidate("の", "prt")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Verb), prevText = "行く",
            nextText = "に")

        val (bonus, rules) = evaluateSoftRules(window)
        assertFalse("no-da-synergy" in rules)
    }

    // -----------------------------------------------------------------------
    // Soft rules: predicate-explanatory-n-synergy
    // -----------------------------------------------------------------------

    @Test fun PredicateExplanatoryN_FiresForVerbBeforeNda() {
        val candidate = makeCandidate("できる", "v1")
        val window = makeWindow(candidate, nextText = "んだ")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(25, bonus)
        assertTrue("predicate-explanatory-n-synergy" in rules)
    }

    @Test fun PredicateExplanatoryN_FiresForIAdjBeforeN() {
        val candidate = makeCandidate("高い", "adj-i")
        val window = makeWindow(candidate, nextText = "ん")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(25, bonus)
        assertTrue("predicate-explanatory-n-synergy" in rules)
    }

    @Test fun PredicateExplanatoryN_FiresForAuxBeforeNdesu() {
        val candidate = makeCandidate("ない", "aux")
        val window = makeWindow(candidate, nextText = "んです")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(25, bonus)
        assertTrue("predicate-explanatory-n-synergy" in rules)
    }

    @Test fun PredicateExplanatoryN_DoesNotFireForNounBeforeNda() {
        val candidate = makeCandidate("本", "n")
        val window = makeWindow(candidate, nextText = "んだ")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("predicate-explanatory-n-synergy" in rules)
    }

    @Test fun PredicateExplanatoryN_DoesNotFireForVerbBeforeDa() {
        val candidate = makeCandidate("できる", "v1")
        val window = makeWindow(candidate, nextText = "だ")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("predicate-explanatory-n-synergy" in rules)
    }

    @Test fun PredicateExplanatoryN_FiresForVerbBeforeNja() {
        val candidate = makeCandidate("行く", "v5k")
        val window = makeWindow(candidate, nextText = "んじゃ")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(25, bonus)
        assertTrue("predicate-explanatory-n-synergy" in rules)
    }

    // -----------------------------------------------------------------------
    // Soft rules: conjunctive-particle-verb-link
    // -----------------------------------------------------------------------

    @Test fun ConjunctiveParticleVerbLink_FiresForVerbBeforeTo() {
        val candidate = makeCandidate("行く", "v5k")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Particle), nextText = "と")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(20, bonus)
        assertTrue("conjunctive-particle-verb-link" in rules)
    }

    @Test fun ConjunctiveParticleVerbLink_FiresForIAdjBeforeNara() {
        val candidate = makeCandidate("高い", "adj-i")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Particle), nextText = "なら")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(20, bonus)
        assertTrue("conjunctive-particle-verb-link" in rules)
    }

    @Test fun ConjunctiveParticleVerbLink_FiresForAuxBeforeTo() {
        val candidate = makeCandidate("た", "aux")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Particle), nextText = "と")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(20, bonus)
        assertTrue("conjunctive-particle-verb-link" in rules)
    }

    @Test fun ConjunctiveParticleVerbLink_DoesNotFireForNounBeforeTo() {
        val candidate = makeCandidate("本", "n")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Particle), nextText = "と")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("conjunctive-particle-verb-link" in rules)
    }

    @Test fun ConjunctiveParticleVerbLink_DoesNotFireForNonConditionalParticle() {
        val candidate = makeCandidate("食べる", "v1")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Particle), nextText = "が")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("conjunctive-particle-verb-link" in rules)
    }

    // -----------------------------------------------------------------------
    // Soft rules: na-adj-no-connector-penalty
    // -----------------------------------------------------------------------

    @Test fun NaAdjNoConnectorPenalty_FiresWhenFollowedByVerb() {
        val candidate = makeCandidate("静か", "adj-na")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Verb), nextText = "する")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(-20, bonus)
        assertTrue("na-adj-no-connector-penalty" in rules)
    }

    @Test fun NaAdjNoConnectorPenalty_DoesNotFireWhenFollowedByNa() {
        val candidate = makeCandidate("静か", "adj-na")
        val window = makeWindow(candidate, nextText = "な")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("na-adj-no-connector-penalty" in rules)
    }

    @Test fun NaAdjNoConnectorPenalty_DoesNotFireWhenFollowedByNi() {
        val candidate = makeCandidate("静か", "adj-na")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Particle), nextText = "に")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("na-adj-no-connector-penalty" in rules)
    }

    @Test fun NaAdjNoConnectorPenalty_DoesNotFireWhenFollowedByCopula() {
        val candidate = makeCandidate("元気", "adj-na")
        val window = makeWindow(candidate, nextText = "だ")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("na-adj-no-connector-penalty" in rules)
    }

    @Test fun NaAdjNoConnectorPenalty_DoesNotFireWhenFollowedByParticle() {
        val candidate = makeCandidate("元気", "adj-na")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Particle), nextText = "が")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("na-adj-no-connector-penalty" in rules)
    }

    @Test fun NaAdjNoConnectorPenalty_DoesNotFireAtSentenceEnd() {
        val candidate = makeCandidate("元気", "adj-na")
        val window = makeWindow(candidate)

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("na-adj-no-connector-penalty" in rules)
    }

    @Test fun NaAdjNoConnectorPenalty_CombinesWithConnectorSynergy() {
        // な follows → synergy fires (+30), penalty does NOT fire
        val candidate = makeCandidate("好き", "adj-na")
        val window = makeWindow(candidate, nextText = "な")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(30, bonus)
        assertTrue("na-adj-connector-synergy" in rules)
        assertFalse("na-adj-no-connector-penalty" in rules)
    }

    @Test fun NoRulesMatch_ReturnsZeroBonus() {
        val candidate = makeCandidate("食べる", "v1")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Noun), prevText = "本",
            nextPOS = listOf(P.Noun), nextText = "物")

        val (bonus, rules) = evaluateSoftRules(window)
        assertEquals(0, bonus)
        assertTrue(rules.isEmpty())
    }

    // -----------------------------------------------------------------------
    // HasApplicableSoftRules
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Soft rules: adverb-na-adj-synergy
    // -----------------------------------------------------------------------

    @Test fun AdverbNaAdjSynergy_FiresWhenAdverbFollowedByNaAdj() {
        val candidate = makeCandidate("とても", "adv")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.NaAdjective), nextText = "静か")

        val (bonus, rules) = evaluateSoftRules(window)
        assertTrue("adverb-na-adj-synergy" in rules)
    }

    @Test fun AdverbNaAdjSynergy_DoesNotFireForAdverbBeforeVerb() {
        val candidate = makeCandidate("すぐ", "adv")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.Verb), nextText = "食べる")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("adverb-na-adj-synergy" in rules)
    }

    @Test fun AdverbNaAdjSynergy_DoesNotFireForNounBeforeNaAdj() {
        val candidate = makeCandidate("本", "n")
        val window = makeWindow(candidate,
            nextPOS = listOf(P.NaAdjective), nextText = "静か")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("adverb-na-adj-synergy" in rules)
    }

    // -----------------------------------------------------------------------
    // Soft rules: verb-after-quotation-to-synergy
    // -----------------------------------------------------------------------

    @Test fun VerbAfterQuotationTo_FiresWhenVerbFollowsToParticle() {
        val candidate = makeCandidate("思う", "v5u")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Particle), prevText = "と")

        val (bonus, rules) = evaluateSoftRules(window)
        assertTrue("verb-after-quotation-to-synergy" in rules)
    }

    @Test fun VerbAfterQuotationTo_DoesNotFireForVerbAfterGaParticle() {
        val candidate = makeCandidate("食べる", "v1")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Particle), prevText = "が")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("verb-after-quotation-to-synergy" in rules)
    }

    @Test fun VerbAfterQuotationTo_DoesNotFireForNounAfterTo() {
        val candidate = makeCandidate("本", "n")
        val window = makeWindow(candidate,
            prevPOS = listOf(P.Particle), prevText = "と")

        val (_, rules) = evaluateSoftRules(window)
        assertFalse("verb-after-quotation-to-synergy" in rules)
    }
}
