package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.Glossary
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.Term
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

object Redirects {
    private val href = Regex(""""href"\s*:\s*"\?query=([^"&]+)(?:[^"]*?primary_reading=([^"&]+))?""")

    fun target(raw: String): Pair<String, String?>? {
        if ("\"redirect-glossary\"" !in raw) return null
        val m = href.find(raw) ?: return null
        val dec = { s: String -> URLDecoder.decode(s, "UTF-8") }
        return dec(m.groupValues[1]) to m.groupValues[2].takeIf { it.isNotEmpty() }?.let(dec)
    }

    private val bracket = Regex("【[^】]*】")

    private fun meaningless(g: Glossary) = target(g.raw) != null || "\"path\"" !in g.raw &&
        g.senses.all { s -> (if ('【' in s) s.replace(bracket, "") else s).none { it.isLetterOrDigit() } }

    fun follow(terms: List<Term>, lookup: (String) -> List<Term>): List<Term?> {
        if (terms.none { t -> t.glossaries.any(::meaningless) }) return terms
        val resolved = terms.map { t -> t.copy(glossaries = t.glossaries.flatMap { g -> resolve(g, lookup) ?: listOf(g) }) }
        return resolved.map { t ->
            val gs = t.glossaries.filterNot { g -> meaningless(g) &&
                resolved.any { o -> o.expression == t.expression && o.glossaries.any { it.dict != g.dict && !meaningless(it) } } }
            if (gs.isEmpty() && t.glossaries.isNotEmpty()) null else t.copy(glossaries = gs)
        }
    }

    private fun resolve(g: Glossary, lookup: (String) -> List<Term>): List<Glossary>? {
        val (to, reading) = target(g.raw) ?: return null
        val found = lookup(to).filter { it.expression == to }
        val same = { ts: List<Term> -> ts.flatMap { it.glossaries }.filter { it.dict == g.dict && !meaningless(it) } }
        val hits = same(found.filter { reading == null || it.reading == reading }).ifEmpty { same(found) }
        if (hits.isEmpty()) return null
        val label = JSONObject().put("tag", "div").put("data", JSONObject().put("content", "redirect-label")).put("style", JSONObject().put("fontSize", "0.8em")).put("content", "→ $to")
        return hits.map { h ->
            h.copy(raw = JSONArray().put(JSONObject().put("type", "structured-content")
                .put("content", JSONArray().put(label).put(JSONObject().put("tag", "div").put("content", runCatching { JSONArray(h.raw) }.getOrElse { return@map h })))).toString())
        }
    }
}
