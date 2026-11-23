package io.github.pedrubik2000.kumapie

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Two top-level classes with the same name in the same package, one in each of two modules that end in one app, are one
 * class on the device: the dex keeps one and the other's callers crash with NoSuchMethodError (a private `Shown` in
 * :app's and in :core's `ui` package: the TV Library crashed on opening, 2026-11-23). The compiler allows it (each is
 * private to its file) and the unit tests pass (the module's own class comes first on their classpath), so it is
 * checked here in the source.
 */
class ClassNameClashTest {
    private val root = File("..").canonicalFile // Gradle runs a module's tests from the module's folder
    private val declared = Regex("""^(?:(?:private|internal|public|data|enum|sealed|abstract|open|value|annotation|fun)\s+)*(?:class|object|interface)\s+([A-Z]\w*)""", RegexOption.MULTILINE)

    private fun names(module: String): Map<String, String> = File(root, "$module/src/main").walk().filter { it.extension == "kt" }.flatMap { f ->
        val text = f.readText()
        val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1).orEmpty()
        declared.findAll(text).map { "$pkg.${it.groupValues[1]}" to "$module/${f.name}" }
    }.toMap()

    private fun clashes(first: String, second: String): List<String> {
        val theirs = names(second)
        return names(first).filterKeys { it in theirs }.map { (name, file) -> "$name: $file and ${theirs[name]}" }
    }

    @Test fun noClassOfTheTvAppSharesItsNameWithOneOfTheSharedModules() {
        assertEquals(emptyList<String>(), listOf("core", "lang").flatMap { clashes("app", it) })
    }

    @Test fun noClassOfThePhoneAppSharesItsNameWithOneOfTheSharedModules() {
        assertEquals(emptyList<String>(), listOf("core", "lang").flatMap { clashes("mobile", it) } + clashes("core", "lang"))
    }
}
