package io.github.pedrubik2000.kumapie

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The words on screen, checked in the source (idea from HolyArrs' word check), all five modules:
 * - every `tr("...")` text has its Spanish (core/i18n/Es*.kt): a missing one shows in English to Giovanna and Jackson,
 *   and nothing else would tell;
 * - retired words never reach the screen, in English or Spanish ([RETIRED]).
 * Texts built at run time (`tr(x)`, `tr("$a")`) can't be checked here.
 */
class WordCheckTest {
    private val root = File("..").canonicalFile // Gradle runs a module's tests from the module's folder
    private val str = """"(?:[^"\\]|\\.)*"""" // one Kotlin "..." string, escapes as written
    private val joined = """$str(?:\s*\+\s*$str)*""" // "a" + "b" across lines

    private fun literal(s: String) = Regex(str).findAll(s).joinToString("") { it.value.drop(1).dropLast(1) }

    private fun sources() = listOf("app", "mobile", "core", "lang", "desktop").flatMap { m ->
        File(root, "$m/src/main").walk().filter { it.extension == "kt" && "/i18n/" !in it.invariantSeparatorsPath }.toList()
    }

    /** English text -> where it's used, for every tr("literal"). */
    private fun used(): Map<String, List<String>> = sources().flatMap { f ->
        val text = f.readText()
        Regex("""\btr\(\s*($joined)\s*[,)]""").findAll(text).map { m ->
            literal(m.groupValues[1]) to "${f.relativeTo(root).invariantSeparatorsPath}:${text.take(m.range.first).count { it == '\n' } + 1}"
        }.filter { (k, _) -> !Regex("""(?<!\\)\$[{a-zA-Z]""").containsMatchIn(k) }.toList()
    }.groupBy({ it.first }, { it.second })

    /** English -> Spanish, from the tables. */
    private fun spanish(): Map<String, String> = File(root, "core/src/main/java/io/github/pedrubik2000/kumapie/i18n").listFiles()!!
        .filter { it.name.startsWith("Es") }.flatMap { f ->
            Regex("""($joined)\s+to\s+($joined)""").findAll(f.readText()).map { literal(it.groupValues[1]) to literal(it.groupValues[2]) }.toList()
        }.toMap()

    @Test fun everyTextHasItsSpanish() {
        val es = spanish()
        val missing = used().filterKeys { it !in es }
        assertTrue("No Spanish for ${missing.size} text(s) (add them to core/i18n/Es*.kt):\n" +
            missing.entries.joinToString("\n") { (k, at) -> "  \"$k\"  (${at.first()})" }, missing.isEmpty())
    }

    @Test fun noRetiredWordsOnScreen() {
        val es = spanish()
        val hits = (used().keys + es.values).flatMap { t -> RETIRED.filter { (w, _) -> Regex(w, RegexOption.IGNORE_CASE).containsMatchIn(t) }.map { (w, why) -> "  \"$t\": $why" } }
        assertTrue("Retired words on screen:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    private companion object {
        /** Word (regex) -> why it's retired. Add one only with the decision behind it (plans/kumapie_plan.md). */
        val RETIRED = listOf(
            """\bscenes?\b|\bescenas?\b""" to "the player goes line by line since \"no more scenes\" (2026-11-17): say line / línea",
        )
    }
}
