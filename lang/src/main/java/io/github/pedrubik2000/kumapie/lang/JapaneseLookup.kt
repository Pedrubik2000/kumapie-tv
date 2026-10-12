package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang

/**
 * What a tap on Japanese text finds (kumapie_languages_plan.md step 3): Sudachi's word at the tap (its dictionary
 * form, matched to its reading) first, then what Yomitan's scan from there finds (longer phrases like 本当に, other
 * spellings). Names fall to JMnedict through the scan only, so フリーレン never shows as フリー.
 */
class JapaneseLookup(private val model: JapaneseModel, private val dicts: YomitanDictionaries) {

    /** The word under [offset] (a char index in [text]) as the episode splits it ([JapaneseModel.words]), or null on punctuation. */
    fun token(text: String, offset: Int): JapaneseModel.Token? =
        model.words(listOf(text)).first().lastOrNull { it.begin <= offset }?.takeIf { it.isWord }

    /**
     * The headwords for a tap at [offset] in [text], longest match first ([length], the player's word, is kept for the
     * callers; the order no longer depends on it).
     */
    fun lookup(text: String, offset: Int, length: Int = 0): List<Headword> {
        val ja = Lang.JAPANESE
        val tok = token(text, offset)
        val found = ArrayList<YomitanDictionaries.Term>()
        val from = tok?.begin ?: offset
        // hoshidicts already orders the scan like Yomitan (and Hachidori, same engine): longest match first, then fewer
        // text changes and conjugation steps, an exact spelling, the frequency lists, the dictionary's score (user,
        // 2026-10-11: the longest first, then frequency). 黒板消し comes before 黒板 even when Sudachi split it.
        val scanned = dicts.scan(ja, text.substring(from.coerceIn(0, text.length)))
        // Only change: among matches of the same length, Sudachi's dictionary form first (行って: 行く, not 行う), with
        // its reading when the word isn't conjugated (先生 せんせい, not Pixiv's あにめせんせい).
        val sudachi = tok?.let { t -> if (t.surface == t.base) hiragana(t.reading) else if (prefixed(t) && t.surface.drop(1) == t.base) hiragana(t.reading).drop(1) else null }
        val bySudachi = { t: YomitanDictionaries.Term ->
            when {
                tok == null || t.expression != tok.base -> 2
                sudachi != null && readingOf(t).let { r -> r.isNotEmpty() && r != sudachi } -> 1
                else -> 0
            } }
        val sorted = scanned.sortedWith(compareBy({ -it.first.length }, { bySudachi(it.second) }))
        // お料理 (item 31): the scan from お finds only お; 料理 comes before matches shorter than the word.
        val (first, later) = if (tok != null && prefixed(tok)) sorted.partition { it.first.length >= tok.surface.length } else sorted to emptyList()
        found += first.map { it.second }
        // The dictionary form or the word as written when the scan didn't reach them (they come after).
        if (tok != null) found += (dicts.query(ja, tok.base) + if (tok.surface != tok.base) dicts.query(ja, tok.surface) else emptyList())
            .sortedBy(bySudachi)
        found += later.map { it.second }
        val unique = found.distinct()
        found.clear(); found += unique
        // One headword per word and reading (hiragana); entries without a reading join the first with that spelling.
        val out = LinkedHashMap<Pair<String, String>, MutableList<YomitanDictionaries.Term>>()
        for (t in found) if (readingOf(t).isNotEmpty()) out.getOrPut(t.expression to readingOf(t)) { ArrayList() } += t
        for (t in found) if (readingOf(t).isEmpty()) {
            val key = out.keys.firstOrNull { it.first == t.expression } ?: (t.expression to "")
            out.getOrPut(key) { ArrayList() } += t
        }
        // Back in the order they were found (the longest match first).
        val order = found.map { it.expression }.distinct()
        return out.entries.sortedBy { order.indexOf(it.key.first) }.map { (k, v) -> Headword(k.first, k.second, v) }
    }

    /**
     * What a tap can rate (kumapie_anki_review_plan.md, Japanese): Sudachi's word as written (気), the text Yomitan's
     * longest match covers (気にしない), that match's headword (気にする); one row per word + reading.
     */
    fun forms(text: String, offset: Int): List<Form> {
        val w = model.words(listOf(text)).first().lastOrNull { it.begin <= offset }?.takeIf { it.isWord } ?: return emptyList()
        val heads = lookup(text, w.begin, w.surface.length)
        // お料理 rates as 料理 and すごーい as すごい (item 31): never a card of their own; お's scan starts after it.
        val skip = if (prefixed(w)) 1 else 0
        val (written, reading) = when {
            skip == 1 -> w.surface.drop(1) to hiragana(w.reading).drop(1)
            stretched(w) -> w.surface.replace("ー", "") to hiragana(w.reading).replace("ー", "")
            else -> w.surface to hiragana(w.reading)
        }
        val scanned = dicts.scan(Lang.JAPANESE, text.substring(w.begin + skip))
        // Its headword covers the whole word (item 26): its dictionary form or spelling, or a scan match at least as long.
        // A shorter piece never is (ホント is not ホン "book", カスミ not カス): no headword then, the card gets no meaning.
        val whole = scanned.filter { it.first.length >= w.surface.length - skip }.map { it.second.expression }.toSet() + w.base + w.surface
        val covering = heads.filter { it.expression in whole }
        // Among those, preferring one with an English meaning for its card (がんばります: 頑張る, not a monolingual-only 頑張ります).
        val meant = covering.filter { h -> h.terms.any(::bilingual) }
        val own = meant.firstOrNull { it.expression == w.base } ?: meant.firstOrNull() ?: covering.firstOrNull { it.expression == w.base } ?: covering.firstOrNull()
        val out = mutableListOf(Form(written, reading, own))
        // The longest match, only when it covers the word (気にしない from 気); among same-length ones a bilingual
        // dictionary's (しましょう: する, not Pixiv's しましょう). A shorter piece is no row (user, 2026-10-11).
        val longest = scanned.filter { it.first.length == scanned.maxOfOrNull { s -> s.first.length } && it.first.length >= w.surface.length - skip }
        val (matched, term) = longest.firstOrNull { it.second.expression == w.base && bilingual(it.second) }
            ?: longest.firstOrNull { bilingual(it.second) } ?: longest.firstOrNull() ?: return out
        val head = heads.firstOrNull { it.expression == term.expression && readingOf(term).let { r -> r.isEmpty() || r == it.reading } }
            ?: Headword(term.expression, readingOf(term), listOf(term))
        out += Form(matched, model.parse(listOf(matched)).first().joinToString("") { hiragana(it.reading) }, head)
        out += Form(term.expression, head.reading, head)
        return out.filterNot { written != w.surface && it.written == w.surface }.distinctBy { it.key }
    }

    /** The term has a bilingual dictionary's entry (an English meaning for a card). */
    private fun bilingual(t: YomitanDictionaries.Term) = t.glossaries.any { dicts.groupOf(Lang.JAPANESE, it.dict).contains("bilingual", ignoreCase = true) }

    /** A form to rate: as written, its reading, and the headword whose meaning its new card gets (null: none found). */
    data class Form(val written: String, val reading: String, val head: Headword?) {
        val key get() = CardIndex.japaneseKey(written, reading)
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

        /** A word お/ご joined to the word after it, keyed by that word (お料理 → 料理, お待たせ → 待たせる; JitenSplitter.joinPrefix). */
        fun prefixed(t: JapaneseModel.Token) =
            t.surface.length > 1 && t.surface[0] in "おご" && t.base.firstOrNull() != t.surface[0] && t.surface[1] == t.base.firstOrNull()

        /** A hiragana word with a long mark that isn't its own (すごーい → すごい, keyed 凄い or すごい). */
        fun stretched(t: JapaneseModel.Token) = 'ー' in t.surface && t.surface.replace("ー", "").length > 1 &&
            t.surface.all { it == 'ー' || it in 'ぁ'..'ゖ' }

        /** Katakana -> hiragana (Sudachi gives readings in katakana, dictionaries in hiragana). */
        fun hiragana(s: String): String = buildString { for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }
    }
}
