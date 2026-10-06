package io.github.pedrubik2000.kumapie.mobile.lang

import io.github.pedrubik2000.kumapie.data.Lang

/**
 * What a tap on Japanese text finds (kumapie_languages_plan.md step 3): Sudachi's word at the tap (its dictionary
 * form, matched to its reading) first, then what Yomitan's scan from there finds (longer phrases like 本当に, other
 * spellings). Names fall to JMnedict through the scan only, so フリーレン never shows as フリー.
 */
class JapaneseLookup(private val model: JapaneseModel, private val dicts: YomitanDictionaries) {

    /** One headword: a word with one reading, and every dictionary's entries for it. */
    data class Headword(val expression: String, val reading: String, val terms: List<YomitanDictionaries.Term>) {
        val frequencies get() = terms.flatMap { it.frequencies }.distinctBy { it.first }
        val pitches get() = terms.flatMap { it.pitches }.distinctBy { it.first }
    }

    /** The word under [offset] (a char index in [text]) as Sudachi splits it, or null on punctuation. */
    fun token(text: String, offset: Int): JapaneseModel.Token? =
        model.parse(listOf(text)).first().lastOrNull { it.begin <= offset }?.takeIf { it.isWord }

    fun lookup(text: String, offset: Int): List<Headword> {
        val ja = Lang.JAPANESE
        val tok = token(text, offset)
        val found = ArrayList<YomitanDictionaries.Term>()
        if (tok != null) {
            // The dictionary form first (教えて -> 教える, not the noun 教え); then the word as written.
            // Sudachi's reading of the written word picks among same spellings (行く いく / ゆく).
            val surfaceReading = hiragana(tok.reading)
            val base = dicts.query(ja, tok.base)
            val asWritten = if (tok.surface != tok.base) dicts.query(ja, tok.surface) else emptyList()
            found += base + asWritten.sortedBy { if (readingOf(it).let { r -> r.isEmpty() || r == surfaceReading }) 0 else 1 }
        }
        val from = tok?.begin ?: offset
        // The scan's longer phrases (本当に, 本当にありがとう) and other spellings; not pieces shorter than the word (本).
        found += dicts.scan(ja, text.substring(from.coerceIn(0, text.length)))
            .filter { (matched, _) -> tok == null || matched.length >= tok.surface.length }.map { it.second }
        // One headword per word and reading (hiragana); entries without a reading join the first with that spelling.
        val out = LinkedHashMap<Pair<String, String>, MutableList<YomitanDictionaries.Term>>()
        for (t in found) if (readingOf(t).isNotEmpty()) out.getOrPut(t.expression to readingOf(t)) { ArrayList() } += t
        for (t in found) if (readingOf(t).isEmpty()) {
            val key = out.keys.firstOrNull { it.first == t.expression } ?: (t.expression to "")
            out.getOrPut(key) { ArrayList() } += t
        }
        // Back in the order they were found (Sudachi's word first).
        val order = found.map { it.expression }.distinct()
        return out.entries.sortedBy { order.indexOf(it.key.first) }.map { (k, v) -> Headword(k.first, k.second, v) }
    }

    /** The term's reading in hiragana; "" when it has none or just repeats the spelling (some dictionaries do). */
    private fun readingOf(t: YomitanDictionaries.Term): String =
        if (t.reading.isEmpty() || (t.reading == t.expression && t.expression.any { it.code in 0x4E00..0x9FFF })) "" else hiragana(t.reading)

    companion object {
        /** Katakana -> hiragana (Sudachi gives readings in katakana, dictionaries in hiragana). */
        fun hiragana(s: String): String = buildString { for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }
    }
}
