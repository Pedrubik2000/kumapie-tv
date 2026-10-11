package io.github.pedrubik2000.kumapie.lang

import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.Tokenizer
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Paths

/**
 * kumapie's Japanese words against Jiten's 1 434 gold segmentations (kumapie_plan.md item 26b; Jiten is Apache-2.0,
 * github.com/Sirush/Jiten, its cases partly from ichiran). A measurement, not a pass/fail test: it runs only when
 * KUMAPIE_JITEN_CASES (cases.json from C:\dojo\work\jiten-port\extract_cases.py) and KUMAPIE_SUDACHI_DIC are set
 * (KUMAPIE_SUDACHI_USER: a user dictionary, e.g. Jiten's user_dic.dic),
 * prints the pass rate and writes the failures next to the cases (failures.txt).
 *   gradlew :lang:testDebugUnitTest --tests '*JitenCasesTest*'
 */
class JitenCasesTest {
    @Test fun passRate() {
        val cases = System.getenv("KUMAPIE_JITEN_CASES")?.let(::File)
        val dic = System.getenv("KUMAPIE_SUDACHI_DIC")
        assumeTrue(cases != null && cases.exists() && dic != null)
        val config = Config.defaultConfig().systemDictionary(Paths.get(dic))
        System.getenv("KUMAPIE_SUDACHI_USER")?.takeIf { it.isNotBlank() }?.let { config.addUserDictionary(Paths.get(it)) }
        val tok = DictionaryFactory().create(config).create()
        val all = JSONArray(cases!!.readText())
        val failures = StringBuilder()
        var passed = 0
        for (i in 0 until all.length()) {
            val c = all.getJSONObject(i)
            val text = c.getString("text")
            val want = c.getJSONArray("words").let { a -> (0 until a.length()).map { a.getString(it) } }
            val tokens = tok.tokenize(Tokenizer.SplitMode.B, text).map {
                JapaneseModel.Token(it.surface(), it.dictionaryForm(), it.readingForm(), it.partOfSpeech()[0], it.begin(), it.partOfSpeech()[1])
            }
            val got = JapaneseModel.join(tokens).filter { it.isWord }.map { it.surface }.filter { it.isNotBlank() }
            if (got == want) passed++
            else failures.append(text).append("\n  want: ").append(want.joinToString(" | "))
                .append("\n  got:  ").append(got.joinToString(" | ")).append("\n")
        }
        File(cases.parentFile, System.getenv("KUMAPIE_JITEN_OUT") ?: "failures.txt").writeText(failures.toString())
        println("JITEN CASES: $passed / ${all.length()} passed (${100 * passed / all.length()}%)")
    }
}
