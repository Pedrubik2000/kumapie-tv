// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Tests/RewriteRuleEngineTests.cs
package io.github.pedrubik2000.kumapie.lang.jiten

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Exercises the declarative token-rewrite engine (RewriteRules.kt) through the applyRewriteRulesForTesting hook with
// synthetic rules: no lexicon, no real rule table.
class RewriteRuleEngineTest {
    private fun tok(
        text: String, pos: PartOfSpeech = PartOfSpeech.Noun, dict: String? = null, reading: String? = null,
        start: Int = -1, end: Int = -1, pin: Int? = null,
    ) = WordInfo().apply {
        this.text = text
        partOfSpeech = pos
        dictionaryForm = dict ?: text
        normalizedForm = dict ?: text
        this.reading = reading ?: text
        startOffset = start
        endOffset = end
        preMatchedWordId = pin
    }

    private fun run(rule: RewriteRule, input: List<WordInfo>, phase: RewritePhase = RewritePhase.Early) =
        MorphologicalAnalyser(null).applyRewriteRulesForTesting(input.toMutableList(), arrayOf(rule), phase)

    private fun texts(l: List<WordInfo>) = l.map { it.text }

    @Test fun onePinKeepsSurfaceAndOffsets_SetsDictFormAndPin() {
        val rule = RewriteRule("darou", RewritePhase.Early,
            arrayOf(TokenPattern(text = "だろー")),
            arrayOf(TokenTemplate("", dictForm = "だろう", normalizedForm = "だろう", pin = 1928670)))
        val output = run(rule, listOf(tok("だろー", start = 5, end = 8)))
        assertEquals(1, output.size)
        assertEquals("だろー", output[0].text)
        assertEquals("だろう", output[0].dictionaryForm)
        assertEquals("だろう", output[0].normalizedForm)
        assertEquals(1928670, output[0].preMatchedWordId)
        assertEquals(5, output[0].startOffset)
        assertEquals(8, output[0].endOffset)
    }

    @Test fun onePin_LeavesUnspecifiedFieldsAsSource() {
        // A pin that sets only DictForm must not touch NormalizedForm/Reading/POS (fields are independent).
        val rule = RewriteRule("dictonly", RewritePhase.Early,
            arrayOf(TokenPattern(text = "x", requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "X", pin = 7)))
        val output = run(rule, listOf(tok("x", PartOfSpeech.Adverb, dict = "src", reading = "リーディング", start = 0, end = 1)))
        assertEquals("X", output[0].dictionaryForm)
        assertEquals("NormalizedForm is not coupled to DictForm", "src", output[0].normalizedForm)
        assertEquals("リーディング", output[0].reading)
        assertEquals(PartOfSpeech.Adverb, output[0].partOfSpeech)
        assertEquals(7, output[0].preMatchedWordId)
    }

    @Test fun split_TilesOffsets_TakesReadingsFromTemplates_ResetsPins() {
        // かって → か + って (offsets 10..13 tile as 10..11 / 11..13); requireUnpinned=false so it fires on a pinned source.
        val rule = RewriteRule("katte", RewritePhase.Early,
            arrayOf(TokenPattern(text = "かって", requireUnpinned = false)),
            arrayOf(
                TokenTemplate("か", dictForm = "か", pos = PartOfSpeech.Particle, reading = "カ"),
                TokenTemplate("って", dictForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ"),
            ))
        val output = run(rule, listOf(tok("かって", PartOfSpeech.Adverb, reading = "カッテ", start = 10, end = 13, pin = 999)))
        assertEquals(listOf("か", "って"), texts(output))
        assertEquals("カ", output[0].reading)
        assertEquals("ッテ", output[1].reading)
        assertEquals(10, output[0].startOffset)
        assertEquals(11, output[0].endOffset)
        assertEquals(11, output[1].startOffset)
        assertEquals(13, output[1].endOffset)
        assertTrue("a re-cut clears the source pin", output.all { it.preMatchedWordId == null })
        assertTrue(output.all { it.partOfSpeech == PartOfSpeech.Particle })
    }

    @Test fun merge_CombinesSurface_UsesTemplateReading_SpansOffsets() {
        val rule = RewriteRule("youha", RewritePhase.Early,
            arrayOf(TokenPattern(text = "よう"), TokenPattern(text = "は")),
            arrayOf(TokenTemplate("ようは", dictForm = "ようは", pos = PartOfSpeech.Conjunction, reading = "ヨウハ", pin = 1914670)))
        val output = run(rule, listOf(
            tok("よう", reading = "ヨウ", start = 0, end = 2),
            tok("は", PartOfSpeech.Particle, reading = "ハ", start = 2, end = 3),
        ))
        assertEquals(1, output.size)
        assertEquals("ようは", output[0].text)
        assertEquals("ヨウハ", output[0].reading)
        assertEquals(1914670, output[0].preMatchedWordId)
        assertEquals(0, output[0].startOffset)
        assertEquals(3, output[0].endOffset)
    }

    @Test fun prevContext_GatesTheRule() {
        val rule = RewriteRule("ka-after-nani", RewritePhase.Early,
            arrayOf(TokenPattern(text = "かって")),
            arrayOf(
                TokenTemplate("か", dictForm = "か", pos = PartOfSpeech.Particle, reading = "カ"),
                TokenTemplate("って", dictForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ"),
            ),
            prev = ContextCond(textAnyOf = arrayOf("何", "誰")))
        assertEquals(listOf("何", "か", "って"), texts(run(rule, listOf(tok("何"), tok("かって", start = 1, end = 4)))))
        // No qualifying prev → rule does not fire.
        assertEquals(listOf("彼", "かって"), texts(run(rule, listOf(tok("彼"), tok("かって", start = 1, end = 4)))))
    }

    @Test fun nextContext_Negate_GatesTheRule() {
        // Fires only when the next token is NOT と.
        val rule = RewriteRule("nashi", RewritePhase.Early,
            arrayOf(TokenPattern(text = "ナシ")),
            arrayOf(TokenTemplate("", dictForm = "なし", pin = 1)),
            next = ContextCond(textAnyOf = arrayOf("と"), negate = true))
        assertEquals(1, run(rule, listOf(tok("ナシ", start = 0, end = 2), tok("を", PartOfSpeech.Particle)))[0].preMatchedWordId)
        assertNull("と after ナシ blocks the pin",
            run(rule, listOf(tok("ナシ", start = 0, end = 2), tok("と", PartOfSpeech.Particle)))[0].preMatchedWordId)
    }

    @Test fun lookupGuard_ExpandsPattern_AndGatesOnDelegate() {
        val rule = RewriteRule("kari", RewritePhase.Late,
            arrayOf(TokenPattern(text = "貸"), TokenPattern(text = "り")),
            arrayOf(TokenTemplate("貸り", dictForm = "貸りる", pos = PartOfSpeech.Verb, reading = "カリ")),
            guard = LookupGuard(LookupGuardKind.CompoundExists, "{0}{1}る"))
        val input = listOf(tok("貸", start = 0, end = 1), tok("り", start = 1, end = 2))

        val analyser = MorphologicalAnalyser(null).apply { hasCompoundLookup = { it == "貸りる" } }
        assertEquals(listOf("貸り"),
            texts(analyser.applyRewriteRulesForTesting(input.toMutableList(), arrayOf(rule), RewritePhase.Late)))

        val noGuard = MorphologicalAnalyser(null).apply { hasCompoundLookup = { false } }
        assertEquals(listOf("貸", "り"),
            texts(noGuard.applyRewriteRulesForTesting(input.toMutableList(), arrayOf(rule), RewritePhase.Late)))
    }

    @Test fun requireUnpinned_SkipsAlreadyPinnedToken() {
        val rule = RewriteRule("pin", RewritePhase.Early,
            arrayOf(TokenPattern(text = "だろー")),
            arrayOf(TokenTemplate("", dictForm = "だろう", pin = 1928670)))
        // Token already carries a pin → default requireUnpinned means the rule leaves it alone.
        assertEquals(42, run(rule, listOf(tok("だろー", start = 0, end = 3, pin = 42)))[0].preMatchedWordId)
    }

    @Test fun noMatch_ReturnsSameReference() {
        val rule = RewriteRule("noop", RewritePhase.Early,
            arrayOf(TokenPattern(text = " zzz ")),
            arrayOf(TokenTemplate("", pin = 1)))
        val input = mutableListOf(tok("犬"), tok("猫"))
        assertSame(input, MorphologicalAnalyser(null).applyRewriteRulesForTesting(input, arrayOf(rule), RewritePhase.Early))
    }

    @Test fun phaseIsolation_RuleOnlyRunsInItsPhase() {
        val rule = RewriteRule("late-only", RewritePhase.Late,
            arrayOf(TokenPattern(text = "だろー")),
            arrayOf(TokenTemplate("", dictForm = "だろう", pin = 1)))
        // Asking for the Early phase must not fire a Late rule.
        assertNull(run(rule, listOf(tok("だろー", start = 0, end = 3)), RewritePhase.Early)[0].preMatchedWordId)
        assertEquals(1, run(rule, listOf(tok("だろー", start = 0, end = 3)), RewritePhase.Late)[0].preMatchedWordId)
    }

    @Test fun validation_RejectsDuplicateIds() {
        val a = RewriteRule("dup", RewritePhase.Early, arrayOf(TokenPattern(text = "x")), arrayOf(TokenTemplate("", pin = 1)))
        val e = assertThrows(IllegalStateException::class.java) {
            MorphologicalAnalyser(null).applyRewriteRulesForTesting(mutableListOf(tok("x")), arrayOf(a, a), RewritePhase.Early)
        }
        assertTrue(e.message!!.contains("Duplicate") && e.message!!.contains("dup"))
    }

    @Test fun validation_RejectsNonConservingLiteralSplit() {
        val bad = RewriteRule("bad", RewritePhase.Early,
            arrayOf(TokenPattern(text = "ab")),
            arrayOf(TokenTemplate("a", reading = "A"), TokenTemplate("c", reading = "C")))
        val e = assertThrows(IllegalStateException::class.java) {
            MorphologicalAnalyser(null).applyRewriteRulesForTesting(mutableListOf(tok("ab")), arrayOf(bad), RewritePhase.Early)
        }
        assertTrue(e.message!!.contains("conserve"))
    }

    @Test fun validation_RequiresReadingOnSplitOutputs() {
        val bad = RewriteRule("noreading", RewritePhase.Early,
            arrayOf(TokenPattern(text = "ab")),
            arrayOf(TokenTemplate("a"), TokenTemplate("b")))
        val e = assertThrows(IllegalStateException::class.java) {
            MorphologicalAnalyser(null).applyRewriteRulesForTesting(mutableListOf(tok("ab")), arrayOf(bad), RewritePhase.Early)
        }
        assertTrue(e.message!!.contains("must specify a Reading"))
    }

    // Not in Jiten's tests: the real table passes the load-time validation (C# checks it at type load).
    @Test fun realTableValidates() {
        val words = mutableListOf(tok("犬"))
        assertSame(words, MorphologicalAnalyser(null).applyTokenRewriteRulesEarly(words))
    }
}
