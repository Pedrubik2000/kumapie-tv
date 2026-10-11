package io.github.pedrubik2000.kumapie.lang

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The PC side of [JitenSplitter] (`gradlew :lang:jitenSplit --args="..."`; C:\dojo\code\tools\kumapie\jiten_split.ps1):
 *   files <episode.kumapie.json>...  re-split each in place ([resplitEpisode]; <file>.bak keeps the first original)
 *   lines [in.json [out.json]]        a JSON list of texts (in.json, else stdin) -> per text [[surface, offset, key or
 *                                     null, null]], the parsed_json newepisode.build takes (tools\kumapie\ja_words.py),
 *                                     to out.json, else stdout
 * Dictionaries from KUMAPIE_SUDACHI_DIC, KUMAPIE_SUDACHI_USER and KUMAPIE_JITEN_LEXICON.
 */
object JitenSplitCli {
    @JvmStatic fun main(args: Array<String>) {
        fun env(name: String) = File(System.getenv(name) ?: error("$name isn't set"))
        val splitter = JitenSplitter(env("KUMAPIE_JITEN_LEXICON"), env("KUMAPIE_SUDACHI_DIC"), env("KUMAPIE_SUDACHI_USER"))
        when (args.firstOrNull()) {
            "files" -> for (path in args.drop(1)) {
                val f = File(path)
                val bak = File("$path.bak")
                if (!bak.exists()) f.copyTo(bak)
                val json = JSONObject(f.readText())
                val before = json.optJSONObject("words")?.length() ?: 0
                resplitEpisode(json, splitter)
                File("$path.tmp").apply { writeText(json.toString()) }.copyTo(f, overwrite = true)
                File("$path.tmp").delete()
                println("$path: ${json.getJSONObject("words").length()} words (were $before)")
            }
            "lines" -> {
                val texts = JSONArray(args.getOrNull(1)?.let { File(it).readText() } ?: System.`in`.readBytes().toString(Charsets.UTF_8))
                val out = JSONArray()
                for (i in 0 until texts.length()) out.put(JSONArray().apply {
                    for (t in splitter.split(texts.getString(i)))
                        put(JSONArray().put(t.surface).put(t.begin).put(if (t.isWord) t.base else JSONObject.NULL).put(JSONObject.NULL))
                })
                args.getOrNull(2)?.let { File(it).writeText(out.toString()) } ?: println(out)
            }
            else -> error("usage: files <episode.kumapie.json>... | lines [in.json [out.json]]")
        }
    }
}
