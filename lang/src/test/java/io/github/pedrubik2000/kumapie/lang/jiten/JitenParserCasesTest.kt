package io.github.pedrubik2000.kumapie.lang.jiten

import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Paths

/**
 * The Kotlin port of Jiten's Parser.ParseText against Jiten's 1 434 gold segmentations (kumapie_plan.md item 26b),
 * exactly as Jiten.Tests/MorphologicalAnalyserTests.cs compares them: the DeckWords' OriginalText in order.
 * A measurement, not a pass/fail test: it runs only when KUMAPIE_JITEN_CASES (cases.json), KUMAPIE_SUDACHI_DIC and
 * KUMAPIE_JITEN_LEXICON are set (KUMAPIE_SUDACHI_USER: the user dictionary), prints the score and writes the failures
 * next to the cases (f_parser.txt, or KUMAPIE_JITEN_OUT).
 *   C:\dojo\work\jiten-port\run_parser.ps1
 */
class JitenParserCasesTest {
    @Test fun passRate() {
        val cases = System.getenv("KUMAPIE_JITEN_CASES")?.let(::File)
        val dic = System.getenv("KUMAPIE_SUDACHI_DIC")
        val lexiconFile = System.getenv("KUMAPIE_JITEN_LEXICON")?.let(::File)
        assumeTrue(cases != null && cases.exists() && dic != null && lexiconFile != null && lexiconFile.exists())
        val user = System.getenv("KUMAPIE_SUDACHI_USER")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        val lexicon = lexiconFile!!.inputStream().use { JitenLexicon.load(it) }
        val parser = JitenParser(lexicon, MorphologicalAnalyser(lexicon, Paths.get(dic!!), user))

        val all = JSONArray(cases!!.readText())
        val failures = StringBuilder()
        var passed = 0
        val t = System.nanoTime()
        for (i in 0 until all.length()) {
            val c = all.getJSONObject(i)
            val text = c.getString("text")
            val want = c.getJSONArray("words").let { a -> (0 until a.length()).map { a.getString(it) } }
            val got = try {
                parser.parseText(text).map { it.originalText }
            } catch (e: Exception) {
                listOf("EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            }
            if (got == want) passed++
            else failures.append(text).append("\n  want: ").append(want.joinToString(" | "))
                .append("\n  got:  ").append(got.joinToString(" | ")).append("\n")
        }
        val ms = (System.nanoTime() - t) / 1_000_000
        File(cases.parentFile, System.getenv("KUMAPIE_JITEN_OUT") ?: "f_parser.txt").writeText(failures.toString())
        println("JITEN PARSER: $passed / ${all.length()} (${100 * passed / all.length()}%, $ms ms)")
    }
}
