package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang

/**
 * What a tap on Japanese text finds (kumapie_languages_plan.md step 3): Sudachi's word at the tap (its dictionary
 * form, matched to its reading) first, then what Yomitan's scan from there finds (longer phrases like 本当に, other
 * spellings). Names fall to JMnedict through the scan only, so フリーレン never shows as フリー.
 */
class JapaneseLookup(private val model: JapaneseModel, private val dicts: YomitanDictionaries) {

    /** The word under [offset] (a char index in [text]) as Sudachi splits it, or null on punctuation. */
    fun token(text: String, offset: Int): JapaneseModel.Token? =
        model.parse(listOf(text)).first().lastOrNull { it.begin <= offset }?.takeIf { it.isWord }

    /**
     * The headwords for a tap at [offset] in [text]. [length]: the tapped word's length in the player (a joined word like
     * 届いた or すいません): what the scan finds for exactly that word comes first, as in Yomitan.
     */
    fun lookup(text: String, offset: Int, length: Int = 0): List<Headword> {
        val ja = Lang.JAPANESE
        val tok = token(text, offset)
        val found = ArrayList<YomitanDictionaries.Term>()
        val from = tok?.begin ?: offset
        val scanned = dicts.scan(ja, text.substring(from.coerceIn(0, text.length)))
        if (length > 0) found += scanned.filter { (matched, _) -> matched.length == length }.map { it.second }
        if (tok != null) {
            // The dictionary form first (教えて -> 教える, not the noun 教え); then the word as written.
            // Sudachi's reading of the written word picks among same spellings (行く いく / ゆく).
            val surfaceReading = hiragana(tok.reading)
            val base = dicts.query(ja, tok.base)
            val asWritten = if (tok.surface != tok.base) dicts.query(ja, tok.surface) else emptyList()
            found += base + asWritten.sortedBy { if (readingOf(it).let { r -> r.isEmpty() || r == surfaceReading }) 0 else 1 }
        }
        // The scan's longer phrases (本当に, 本当にありがとう) and other spellings; not pieces shorter than the word (本).
        found += scanned.filter { (matched, _) -> tok == null || matched.length >= tok.surface.length }.map { it.second }
        val unique = found.distinct()
        found.clear(); found += unique
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
        /**
         * A dictionary entry's text as its senses, each short: split at the sense numbers ("…slightly.; 2 just a minute",
         * "①…"), examples (after ► or ・) dropped, headword and tags out ([meaning]), at most 90 characters.
         */
        /** English meanings only: no Japanese example lines, no English example sentences ("What will be the end of all this?"). */
        fun bilingualMeanings(entry: String): List<String> = meanings(entry).filter { m ->
            m.any { it in 'a'..'z' } && !(m.first().isUpperCase() && m.count { it == ' ' } >= 4 && m.trimEnd().last() in ".?!")
        }

        /**
         * The first real definition of a monolingual entry: grammar tags (（名）《副》〔文〕①) and symbols left out, at least
         * 4 Japanese characters, cut before quoted examples (「…」).
         */
        fun monolingualDefinition(entry: String): String? = meanings(entry).asSequence()
            .map { it.replace(Regex("""（[^）]*）|《[^》]*》|〔[^〕]*〕|\([^)]*\)|〈[^〉]*〉|［[^］]*］|[■□＊*◆◇⦅⦆①-⑳㊀-㊉]"""), "")
                .substringBefore('「').substringBefore('；').trim(' ', ';', '：', ':', '・') }
            .firstOrNull { d -> d.count { it.code in 0x3040..0x30FF || it.code in 0x3400..0x9FFF } >= 4 }

        fun meanings(entry: String): List<String> =
            entry.split(Regex("""(?:^|[;；。\s])\s*(?:\d{1,2}|[①-⑳])\s+(?=\S)"""))
                // Before the first sense number: the headword ("ちょっと"), unless it is a meaning in English.
                .let { parts -> if (parts.size > 1 && parts[0].none { it in 'a'..'z' } && parts[0].length <= 16) parts.drop(1) else parts }
                .map { meaning(it.substringBefore('►').substringBefore(" ・").substringBefore("　・")).trimEnd(';', '；', ' ', '.') }
                .filter { it.length > 1 }
                .map { if (it.length > 90) it.take(88).trimEnd() + "…" else it }
                .distinct()

        /** A sense as a short meaning: "よばれる【呼ばれる】; 〘v1・vi〙; 1 to be called out." → "to be called out." */
        fun meaning(s: String) = s.substringAfterLast('】').replace(Regex("""〘[^〙]*〙|［[^］]*］|\[[^\]]*]|→\S+"""), "")
            .replace(Regex("""^[\s;；:・.\d①-⑳]+"""), "").trim()

        /** Katakana -> hiragana (Sudachi gives readings in katakana, dictionaries in hiragana). */
        fun hiragana(s: String): String = buildString { for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }
    }
}
