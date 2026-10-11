package io.github.pedrubik2000.kumapie.lang.jiten

import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Paths

/**
 * The Kotlin port of Jiten's MorphologicalAnalyser against Jiten's 1 434 gold segmentations (kumapie_plan.md item 26b).
 * A measurement, not a pass/fail test: it runs only when KUMAPIE_JITEN_CASES (cases.json) and KUMAPIE_SUDACHI_DIC are
 * set (KUMAPIE_SUDACHI_USER: the user dictionary; KUMAPIE_JITEN_LEXICON: the lexicon the stages look words up in),
 * prints the score and writes the failures next to the cases (f_analyser.txt, or KUMAPIE_JITEN_OUT).
 * Words = the tokens' Text in order without SupplementarySymbol (Parser.ExtractWordInfos drops them), Symbol and
 * BlankSpace tokens (never JMdict words, so ParseText drops them too) and blank text.
 *   C:\dojo\work\jiten-port\run_analyser.ps1
 */
class JitenAnalyserCasesTest {
    @Test fun passRate() {
        val cases = System.getenv("KUMAPIE_JITEN_CASES")?.let(::File)
        val dic = System.getenv("KUMAPIE_SUDACHI_DIC")
        assumeTrue(cases != null && cases.exists() && dic != null)
        val user = System.getenv("KUMAPIE_SUDACHI_USER")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        val lexicon = System.getenv("KUMAPIE_JITEN_LEXICON")?.let(::File)?.takeIf { it.exists() }
            ?.inputStream()?.use { JitenLexicon.load(it) }
        val analyser = MorphologicalAnalyser(lexicon, Paths.get(dic!!), user)
        val dropped = setOf(PartOfSpeech.SupplementarySymbol, PartOfSpeech.Symbol, PartOfSpeech.BlankSpace)

        val all = JSONArray(cases!!.readText())
        val failures = StringBuilder()
        var passed = 0
        val t = System.nanoTime()
        for (i in 0 until all.length()) {
            val c = all.getJSONObject(i)
            val text = c.getString("text")
            val want = c.getJSONArray("words").let { a -> (0 until a.length()).map { a.getString(it) } }
            val got = analyser.parse(text).flatMap { it.words }.map { it.word }
                .filter { it.partOfSpeech !in dropped && it.text.isNotBlank() }.map { it.text }
            if (got == want) passed++
            else failures.append(text).append("\n  want: ").append(want.joinToString(" | "))
                .append("\n  got:  ").append(got.joinToString(" | ")).append("\n")
        }
        val ms = (System.nanoTime() - t) / 1_000_000
        File(cases.parentFile, System.getenv("KUMAPIE_JITEN_OUT") ?: "f_analyser.txt").writeText(failures.toString())
        println("JITEN ANALYSER: $passed / ${all.length()} (${100 * passed / all.length()}%, $ms ms, lexicon ${lexicon != null})")
    }
}
