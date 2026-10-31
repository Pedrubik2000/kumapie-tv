package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang

/**
 * A Japanese card's Definition (bilingual), the same in kumapie, chimahon ({glossary-first}) and kumadeck
 * (glossary_html.py) (Pedro, 2026-10-30): the word's whole entry, Spanish then the Japanese original, from the first
 * of [ORDER] that has it, as Yomitan HTML inside chimahon's `yomitan-glossary` wrapper with that dictionary's CSS.
 */
object CardDefinition {
    /** Daijirin in Spanish, else Sankoku in Spanish, else JMdict Spanish, else Jitendex (words of the title, any case). */
    private val ORDER = listOf(listOf("daijirin", "es-jp"), listOf("sankoku", "es-jp"), listOf("jmdict (spanish)"), listOf("jitendex"))

    fun rank(title: String): Int? = ORDER.indexOfFirst { words -> words.all { title.contains(it, ignoreCase = true) } }.takeIf { it >= 0 }

    /** (dictionary, HTML) for [expression] read [reading] (any reading when blank), or null when none of [ORDER] has it. */
    fun find(y: YomitanDictionaries, expression: String, reading: String?): Pair<String, String>? {
        val ja = Lang.JAPANESE
        val r = reading?.let(JapaneseLookup::hiragana).orEmpty()
        val terms = y.query(ja, expression).filter { t ->
            (t.expression == expression || t.reading == expression) &&
                (r.isEmpty() || JapaneseLookup.hiragana(t.reading.ifEmpty { t.expression }) == r)
        }
        val glossaries = terms.flatMap { it.glossaries }.filter { rank(it.dict) != null }
        val dict = glossaries.minByOrNull { rank(it.dict)!! }?.dict ?: return null
        val parts = glossaries.filter { it.dict == dict }.map { it.raw }.distinct()
            .filterNot(::linksOnly).map { GlossaryHtml.glossary(it, dict, card = true) }.filter { it.isNotBlank() }.distinct()
        if (parts.isEmpty()) return null
        val t = GlossaryHtml.esc(dict).replace("\"", "&quot;")
        val css = y.styles(ja)[dict]?.takeIf { it.isNotBlank() }?.let { "<style>[data-dictionary=\"$t\"] {\n${it.replace("</", "<\\/")}\n}</style>" }.orEmpty()
        return dict to "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol><li data-dictionary=\"$t\">" +
            parts.joinToString("<br>") + "</li></ol></div>" + css
    }

    /** Daijirin's 子 rows: only links to compounds (習得観念, 習得曲線), no definition. */
    private fun linksOnly(raw: String) = runCatching {
        val a = org.json.JSONArray(raw)
        a.length() > 0 && (0 until a.length()).all { a.optJSONObject(it)?.optJSONObject("content")?.optString("tag") == "a" }
    }.getOrDefault(false)
}
