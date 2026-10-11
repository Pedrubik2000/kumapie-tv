// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Tests/DeconjugatorTests.cs
package io.github.pedrubik2000.kumapie.lang.jiten

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeconjugatorTest {
    @Test fun containsExpectedDictionaryForm() {
        val cases = listOf(
            "終わってしまった" to "終わる", "わからない" to "わかる", "みて" to "みる", "作る" to "作る", "なかった" to "ない",
            "選びださねば" to "選びだす",
            // てしまう → ちゃう/ちまう/じゃう/じまう
            "食べちゃった" to "食べる", "飲んじゃった" to "飲む", "食べちまった" to "食べる", "飲んじまった" to "飲む",
            // ていく → てく, ておく → とく
            "食べてく" to "食べる", "なってく" to "なる", "飲んでく" to "飲む", "食べとく" to "食べる", "飲んどく" to "飲む",
            // conditional けりゃ/きゃ, ては → ちゃ
            "食べなくちゃ" to "食べる", "食べなけりゃ" to "食べる", "行かなきゃ" to "行く",
            // slang negative んない, adjective ねえ
            "わかんない" to "わかる", "つまんない" to "つまる", "すごくねえ" to "すごい",
            "でしょ" to "です", "でしょう" to "です", "行きまひょ" to "行く",
            // na-adjective adnominal な
            "和やかな" to "和やか", "大切な" to "大切", "静かな" to "静か",
        )
        val deconjugator = Deconjugator()
        val missing = cases.filter { (text, base) -> deconjugator.deconjugate(text).none { it.text == base } }
        assertTrue("missing dictionary forms: $missing", missing.isEmpty())
    }

    @Test fun deterministicForSameInput() {
        val deconjugator = Deconjugator()
        val first = deconjugator.deconjugate("終わってしまった").map(::format)
        val second = deconjugator.deconjugate("終わってしまった").map(::format)
        assertEquals(first, second)
        // and across instances (the cache is per instance)
        assertEquals(first, Deconjugator().deconjugate("終わってしまった").map(::format))
    }

    @Test fun noDuplicateForms() {
        val keys = Deconjugator().deconjugate("終わってしまった").map(::format)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun boundedForComplexInputs() {
        val deconjugator = Deconjugator()
        for ((input, max) in listOf("終わってしまった" to 200, "食べさせられなかった" to 250, "読ませられなかった" to 250)) {
            val n = deconjugator.deconjugate(input).size
            assertTrue("$input: $n forms > $max", n <= max)
        }
    }

    @Test fun cacheEvictsWhenCapacityIsExceeded() {
        val deconjugator = Deconjugator(maxCacheEntries = 3)
        deconjugator.clearCacheForTesting()
        for (t in listOf("わからない", "みて", "なかった", "終わってしまった", "食べる", "飲んだ", "走った", "書いた"))
            deconjugator.deconjugate(t)
        val stats = deconjugator.getCacheStats()
        assertTrue(stats.evictions > 0)
        assertTrue(stats.stores > 0)
    }

    @Test fun cacheTracksHitsAndMisses() {
        val deconjugator = Deconjugator(maxCacheEntries = 10)
        deconjugator.clearCacheForTesting()
        deconjugator.deconjugate("わからない")
        deconjugator.deconjugate("わからない")
        val stats = deconjugator.getCacheStats()
        assertTrue(stats.hits > 0)
        assertTrue(stats.misses > 0)
    }

    /** Not in Jiten's tests: the results come sorted by length descending, then ordinal. */
    @Test fun sortedByLengthThenText() {
        val forms = Deconjugator().deconjugate("食べさせられなかった")
        for (i in 1 until forms.size) {
            val a = forms[i - 1].text
            val b = forms[i].text
            assertTrue("$a before $b", a.length > b.length || (a.length == b.length && a <= b))
        }
    }

    private fun format(form: DeconjugationForm) =
        "${form.text}|${form.originalText}|${form.tags.joinToString(",")}|${form.process.joinToString(",")}|" +
            form.seenText.sorted().joinToString(",")
}
