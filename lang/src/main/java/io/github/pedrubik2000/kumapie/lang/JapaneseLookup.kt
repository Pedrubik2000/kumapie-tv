package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Segment

/**
 * What a tap on Japanese text finds (kumapie_languages_plan.md step 3): Sudachi's word at the tap (its dictionary
 * form, matched to its reading) first, then what Yomitan's scan from there finds (longer phrases like 本当に, other
 * spellings). Names fall to JMnedict through the scan only, so フリーレン never shows as フリー. [hasCard]: a word key
 * ([CardIndex.japaneseKey]) has a card of its own ([reading] keeps that card's reading).
 */
class JapaneseLookup(private val model: JapaneseModel, private val dicts: YomitanDictionaries, private val hasCard: (String) -> Boolean = { false }) {

    /** The word under [offset] (a char index in [text]) as the episode splits it ([JapaneseModel.words]), or null on punctuation. */
    fun token(text: String, offset: Int): JapaneseModel.Token? =
        model.words(listOf(text)).first().lastOrNull { it.begin <= offset }?.takeIf { it.isWord }

    /**
     * The headwords for a tap at [offset] in [text], longest match first ([length], the player's word, is kept for the
     * callers; the order no longer depends on it).
     */
    fun lookup(text: String, offset: Int, length: Int = 0): List<Headword> {
        val ja = Lang.JAPANESE
        dicts.personal.at(ja.code, text, offset)?.let { return headwords(dicts.query(ja, it.text)) }
        val tok = token(text, offset)
        val found = ArrayList<YomitanDictionaries.Term>()
        val from = tok?.begin ?: offset
        // hoshidicts already orders the scan like Yomitan (and Hachidori, same engine): longest match first, then fewer
        // text changes and conjugation steps, an exact spelling, the frequency lists, the dictionary's score (user, 2026-10-11: the longest first, then frequency). 黒板消し comes before 黒板 even when Sudachi split it.
        val scanned = dicts.scan(ja, text.substring(from.coerceIn(0, text.length)))
        // Only change: among matches of the same length, Sudachi's dictionary form first (行って: 行く, not 行う), with its reading when the word isn't conjugated (先生 せんせい, not Pixiv's あにめせんせい; 私 わたし: [reading]). A word the line writes in kana: the spellings with that reading next (まじない: 呪い まじない, not のろい; 5b).
        val sudachi = tok?.let { t -> if (t.surface == t.base) readingIn(text, t) else if (prefixed(t) && t.surface.drop(1) == t.base) hiragana(t.reading).drop(1) else null }
        val bySudachi = { t: YomitanDictionaries.Term ->
            when {
                tok != null && t.expression != tok.base && kana(tok.surface) && readingOf(t) == hiragana(tok.surface) -> 1
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
        return headwords(found.distinct())
    }

    private fun headwords(found: List<YomitanDictionaries.Term>): List<Headword> {
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

    private fun readingIn(text: String, t: JapaneseModel.Token) = reading(text, t.begin, t.base, hiragana(t.reading), dicts.query(Lang.JAPANESE, t.base), dicts.personal.reading(t.base), hasCard)

    /**
     * What a tap can rate (kumapie_anki_review_plan.md, Japanese): Sudachi's word as written (気), the text Yomitan's
     * longest match covers (気にしない), that match's headword (気にする); one row per word + reading.
     */
    fun forms(text: String, offset: Int): List<Form> {
        // Your own word (user, 2026-11-03): one row, 可可阿 rates as the カカオ it is the same as.
        dicts.personal.at(Lang.JAPANESE.code, text, offset)?.let { e ->
            val written = e.target.ifEmpty { e.text }
            val heads = headwords(dicts.query(Lang.JAPANESE, e.text))
            val head = heads.firstOrNull { it.expression == written } ?: heads.firstOrNull()
            val reading = head?.reading?.takeIf { it.isNotBlank() } ?: hiragana(e.reading).ifBlank {
                model.parse(listOf(written)).first().joinToString("") { hiragana(it.reading) } }
            return listOf(Form(written, reading, head, surface = e.text))
        }
        val w = model.words(listOf(text)).first().lastOrNull { it.begin <= offset }?.takeIf { it.isWord } ?: return emptyList()
        val heads = lookup(text, w.begin, w.surface.length)
        // お料理 rates as 料理 and すごーい as すごい (item 31): never a card of their own; お's scan starts after it. お休み is an entry with its お, ごひいきさん is ひいき and およ nothing ([whole], 5e), whatever a scan from お finds.
        if (prefixed(w)) whole(w.surface, w.base) { dicts.query(Lang.JAPANESE, it) }?.let { terms ->
            val r = hiragana(w.reading)
            val found = headwords(terms).let { hs -> hs.firstOrNull { it.reading.isNotEmpty() && it.reading in r } ?: hs.firstOrNull() }
            return listOf(found?.let { Form(it.expression, it.reading, it, surface = w.surface) } ?: Form(w.surface, r, null))
        }
        val skip = if (prefixed(w)) 1 else 0
        val (written, reading) = when {
            skip == 1 -> w.surface.drop(1) to hiragana(w.reading).drop(1)
            stretched(w) -> w.surface.replace("ー", "") to hiragana(w.reading).replace("ー", "")
            else -> w.surface to if (w.surface == w.base) readingIn(text, w) else hiragana(w.reading)
        }
        val scanned = dicts.scan(Lang.JAPANESE, text.substring(w.begin + skip))
        // Its headword covers the whole word (item 26): its dictionary form or spelling, or a scan match at least as long. A shorter piece never is (ホント is not ホン "book", カスミ not カス, おぉ not お): no headword then, the card gets no meaning.
        val covers = scanned.filter { it.first.length >= w.surface.length - skip }.map { it.second.expression }.toSet() + w.base + w.surface
        val covering = heads.filter { it.expression in covers && !piece(it.expression, w.surface) }
        // Among those, preferring one with an English meaning for its card (がんばります: 頑張る, not a monolingual-only 頑張ります).
        val meant = covering.filter { h -> h.terms.any(::bilingual) }
        val own = meant.firstOrNull { it.expression == w.base && it.reading == reading } ?: meant.firstOrNull { it.expression == w.base }
            ?: meant.firstOrNull { kana(w.surface) && it.reading == reading } ?: meant.firstOrNull() ?: covering.firstOrNull { it.expression == w.base } ?: covering.firstOrNull()
        val out = mutableListOf(Form(written, reading, own))
        if (w.surface == w.base && dicts.personal.reading(w.base) == reading) out += readings(w.base, dicts.query(Lang.JAPANESE, w.base))
            .filter { it != reading && hasCard(CardIndex.japaneseKey(written, it)) }.map { r -> Form(written, r, heads.firstOrNull { it.expression == w.base && it.reading == r }) }
        // The longest match, only when it covers the word (気にしない from 気); among same-length ones a bilingual dictionary's (しましょう: する, not Pixiv's しましょう). A shorter piece is no row (user, 2026-10-11).
        val longest = scanned.filter { it.first.length == scanned.maxOfOrNull { s -> s.first.length } && it.first.length >= w.surface.length - skip }
        name(longest, heads)?.let { return listOf(it) }
        val (matched, term) = longest.firstOrNull { it.second.expression == w.base && readingOf(it.second) == reading && bilingual(it.second) }
            ?: longest.firstOrNull { it.second.expression == w.base && bilingual(it.second) }
            ?: longest.firstOrNull { bilingual(it.second) } ?: longest.firstOrNull() ?: return out
        val head = heads.firstOrNull { it.expression == term.expression && readingOf(term).let { r -> r.isEmpty() || r == it.reading } }
            ?: Headword(term.expression, readingOf(term), listOf(term))
        // The headword itself keeps its reading: Sudachi reads 壬氏様 みずのえしさま, the dictionary じんしさま.
        out += Form(matched, head.reading.takeIf { matched == term.expression && it.isNotBlank() }
            ?: model.parse(listOf(matched)).first().joinToString("") { hiragana(it.reading) }, head)
        out += Form(term.expression, head.reading, head)
        // A conjugated form is never a row of its own (user, 2026-10-24): 離れてて and 気にしない rate as 離れる and 気にする, when that dictionary form has a row.
        val dictionary = setOf(w.base, term.expression)
        val conjugated = { f: Form -> f.written !in dictionary && out.any { it.written in dictionary } &&
            (f.written == written || f.written == matched) && (w.surface != w.base || matched != term.expression) }
        return entryFirst(out.filterNot { written != w.surface && it.written == w.surface }.filterNot(conjugated).distinctBy { it.key },
            matched, Form(term.expression, head.reading, head), w.surface.length - skip)
    }

    /**
     * The one row of a tap on a character's name (user, 2026-11-02): when only Bee's Character Dictionary has the longest
     * match, 壬氏様 rates as 壬氏 [じんし], not as the piece 壬 [みずのえ] Jiten split off or a card per honorific. Not for
     * all-hiragana matches: そうた could be そう + た.
     */
    private fun name(longest: List<Pair<String, YomitanDictionaries.Term>>, heads: List<Headword>): Form? {
        if (longest.isEmpty() || !longest.all { (_, t) -> t.glossaries.isNotEmpty() && t.glossaries.all { YomitanDictionaries.character(it.raw) != null } }) return null
        val (matched, term) = longest.first()
        if (matched.all { it in 'ぁ'..'ゖ' }) return null
        val honorific = term.glossaries.firstNotNullOfOrNull { YomitanDictionaries.character(it.raw)?.second }.orEmpty()
        val base = term.expression.removeSuffix(honorific)
        heads.firstOrNull { it.expression == base && readingOf(term).startsWith(it.reading) }?.let { return Form(it.expression, it.reading, it) }
        // A name whose reading was dropped (Bee's 紅娘 ほんgにあんg) sits in another word's headword (紅娘 てんとうむし): its
        // own entries, keyed 紅娘 as the subtitles key it. 紅娘様's row rated a card the subtitle didn't go by (2026-11-11).
        val own = heads.firstOrNull { it.expression == base }?.terms.orEmpty().filter { t -> t.glossaries.any { YomitanDictionaries.character(it.raw) != null } }
        if (readingOf(term).isEmpty() && own.isNotEmpty()) return Form(base, "", Headword(base, "", own))
        val head = heads.firstOrNull { it.expression == term.expression } ?: return null
        return Form(head.expression, head.reading, head)
    }

    /** The term has a bilingual dictionary's entry (an English meaning for a card). */
    private fun bilingual(t: YomitanDictionaries.Term) = t.glossaries.any { dicts.groupOf(Lang.JAPANESE, it.dict).contains("bilingual", ignoreCase = true) }

    /** A form to rate: as written, its reading, and the headword whose meaning its new card gets (null: none found). */
    data class Form(val written: String, val reading: String, val head: Headword?,
                    /** As the line writes it, for the card's bold word, when that isn't [written] (可可阿 of カカオ). */
                    val surface: String = written) {
        val key get() = CardIndex.japaneseKey(written, reading)
    }

    /** The term's reading in hiragana; "" when it has none or just repeats the spelling (some dictionaries do). */
    private fun readingOf(t: YomitanDictionaries.Term): String =
        if (t.reading.isEmpty() || (t.reading == t.expression && t.expression.any { it.code in 0x4E00..0x9FFF })) "" else hiragana(t.reading)

    companion object {
        /**
         * English meanings only: no Japanese example lines, no English example sentences ("What will be the end of all this?").
         * The English Sankoku/Daijirin: "ゆ きり ［湯切 り ］ ｟名 ・他サ ｠; 〘料 〙 The act of draining〔＝よけいな…〕 hot water…":
         * its part of speech ｟…｠ holds a " ・" (an example's start for [meanings]) and its 〔…〕 notes sit inside the English.
         * Daijirin's 〖cacao〗 is the word's origin, the meaning only when there is no other (〖archives〗 for アーカイブス); its
         * （名 sust.） is the part of speech.
         */
        fun bilingualMeanings(entry: String): List<String> = meanings(entry.replace(Regex("""｟[^｠]*｠|〔[^〕]*〕|〖[^〗]*〗|（[぀-ヿ㐀-鿿][^）]*）"""), ""), Int.MAX_VALUE).filter { m ->
            m.any { it in 'a'..'z' } && !(m.first().isUpperCase() && m.count { it == ' ' } >= 4 && m.trimEnd().last() in ".?!")
        }.flatMap { m ->
            // Jitendex packs whole entries into one string: "1-dan transitive; to eat; もっと果物を…; You should eat more fruit.;
            // to live on; …". Keep the meaning pieces only (user, 2026-10-14: "In this line" and mined cards showed it all):
            // a Japanese example ends a sense, the piece after it is its translation; no "See also", no tag-only piece.
            val senses = mutableListOf(mutableListOf<String>())
            var translation = false
            var seenEnglish = false // Japanese before any English is the headword ("ゆ きり"), not an example
            val pieces = m.split(';', '；').map(::englishPart).filter { it.isNotEmpty() }
            for (p in pieces) {
                val english = p.any { it in 'a'..'z' || it in 'A'..'Z' }
                if (english) seenEnglish = true
                when {
                    !english -> { if (senses.last().isNotEmpty()) senses += mutableListOf<String>(); translation = seenEnglish }
                    translation -> translation = false
                    // A tag is a piece among others: alone it is the meaning (a character called Kana).
                    p.startsWith("See also") || pieces.size > 1 && p.lowercase().split(' ').all { w -> w in TAGS || w.matches(Regex("""\d*-?dan""")) } -> {}
                    p.any { it in 'a'..'z' } && p !in senses.last() -> senses.last() += p
                }
            }
            senses.filter { it.isNotEmpty() }.map { it.joinToString("; ").let { s -> if (s.length > 90) s.take(88).trimEnd() + "…" else s } }
        }.distinct().ifEmpty {
            listOfNotNull(Regex("""〖([^〗]*)〗""").find(entry)?.groupValues?.get(1)?.trim()?.takeIf { o -> o.any { it in 'a'..'z' || it in 'A'..'Z' } })
        }

        /** A piece cut where Japanese starts after its English ("to harvest. 集める。採集する。" -> "to harvest"). */
        private fun englishPart(piece: String): String {
            val p = piece.trim()
            val latin = p.indexOfFirst { it in 'a'..'z' || it in 'A'..'Z' }
            if (latin < 0) return p
            // Japanese quoted in «» belongs to the meaning (the Spanish dictionaries: "Lo mismo que «カカオの木»").
            var quoted = false
            val ja = p.withIndex().indexOfFirst { (i, c) ->
                if (c == '«') quoted = true else if (c == '»') quoted = false
                i > latin && !quoted && (c.code in 0x3040..0x30FF || c.code in 0x3400..0x9FFF || c in "《〔「【")
            }
            // The headword's kana before the English ("ゆ きり The act of…") is not part of the meaning.
            val start = if (p.take(latin).any { it.code in 0x3040..0x30FF || it.code in 0x3400..0x9FFF }) latin else 0
            return (if (ja < 0) p.substring(start) else p.substring(start, ja)).replace(Regex(" {2,}"), " ").trimEnd(' ', '.', '。', '←', '→')
        }

        /**
         * Grammar and domain tags a Jitendex sense starts with ("noun abbr. baseball", "1-dan transitive"), dropped as pieces.
         * ponytail: a fixed list; add the domains that turn up as meanings.
         */
        private val TAGS = setOf("noun", "abbr.", "abbr", "transitive", "intransitive", "na-adj", "no-adj", "i-adj", "adverb", "adv",
            "suru", "kana", "usually", "colloquial", "honorific", "humble", "polite", "pronoun", "expression", "prefix", "suffix",
            "counter", "interjection", "conjunction", "particle", "auxiliary", "onomatopoeia", "slang", "archaic", "derogatory",
            "baseball", "sports", "music", "computing", "medicine", "law", "food", "cooking", "mathematics", "physics", "chemistry",
            "biology", "buddhism", "shinto", "finance", "business", "grammar", "linguistics", "military", "video", "games", "manga", "anime")

        /**
         * The first real definition of a monolingual entry: grammar tags (（名）《副》〔文〕①) and symbols left out, at least
         * 4 Japanese characters, cut before quoted examples (「…」).
         */
        fun monolingualDefinition(entry: String): String? = meanings(entry).asSequence()
            .map { it.replace(Regex("""（[^）]*）|《[^》]*》|〔[^〕]*〕|\([^)]*\)|〈[^〉]*〉|［[^］]*］|[■□＊*◆◇⦅⦆①-⑳㊀-㊉]"""), "")
                .substringBefore('「').substringBefore('；').trim(' ', ';', '：', ':', '・') }
            .firstOrNull { d -> d.count { it.code in 0x3040..0x30FF || it.code in 0x3400..0x9FFF } >= 4 }

        /**
         * A dictionary entry's text as its senses, each short: split at the sense numbers ("…slightly.; 2 just a minute",
         * "①…"), examples (after ► or ・) dropped, headword and tags out ([meaning]), at most 90 characters.
         */
        fun meanings(entry: String, max: Int = 90): List<String> =
            entry.split(Regex("""(?:^|[;；。\s])\s*(?:\d{1,2}|[①-⑳])\s+(?=\S)"""))
                // Before the first sense number: the headword ("ちょっと"), unless it is a meaning in English.
                .let { parts -> if (parts.size > 1 && parts[0].none { it in 'a'..'z' } && parts[0].length <= 16) parts.drop(1) else parts }
                .map { meaning(it.substringBefore('►').substringBefore(" ・").substringBefore("　・")).trimEnd(';', '；', ' ', '.') }
                .filter { it.length > 1 }
                .map { if (it.length > max) it.take(max - 2).trimEnd() + "…" else it }
                .distinct()

        /** A sense as a short meaning: "よばれる【呼ばれる】; 〘v1・vi〙; 1 to be called out." → "to be called out." */
        fun meaning(s: String) = s.substringAfterLast('】').replace(Regex("""〘[^〙]*〙|［[^］]*］|\[[^\]]*]|→\S+"""), "")
            .replace(Regex("""^[\s;；:・.\d①-⑳◉ⓐ-ⓩ]+"""), "").trim()

        /**
         * A cue's pieces with a character's name as one word, keyed by [name] (user, 2026-11-02): Jiten doesn't know 壬氏, so
         * 壬|氏|様 becomes 壬氏様, keyed 壬氏. A name starts with kanji or katakana: そう|た is not そうた.
         * ponytail: at most 5 pieces; a longer name stays split until this is raised.
         */
        fun joinNames(segments: List<Segment>, name: (String) -> String?): List<Segment> {
            val out = ArrayList<Segment>(segments.size)
            var i = 0
            while (i < segments.size) {
                val first = segments[i]
                val hit = if (first.word == null || first.text.firstOrNull() in 'ぁ'..'ゖ') null
                    else (minOf(i + 5, segments.size) downTo i + 2).firstNotNullOfOrNull { end ->
                        val pieces = segments.subList(i, end)
                        if (pieces.any { it.word == null }) null
                        else pieces.joinToString("") { it.text }.let { t -> name(t)?.let { Segment(t, it) to end } }
                    }
                out += hit?.first ?: first
                i = hit?.second ?: (i + 1)
            }
            return out
        }

        /** A word お/ご joined to the word after it, keyed by that word (お料理 → 料理, お待たせ → 待たせる; JitenSplitter.joinPrefix). */
        fun prefixed(t: JapaneseModel.Token) =
            t.surface.length > 1 && t.surface[0] in "おご" && t.base.firstOrNull() != t.surface[0] && t.surface[1] == t.base.firstOrNull()

        /**
         * A word with お/ご before it or an honorific after it the dictionaries may not have as written (core plan 5e): the
         * word, without the honorific, without お/ご, without both (ごひいきさん: ごひいき, ひいきさん, ひいき). One: neither.
         */
        fun tries(word: String): List<String> {
            val bare = JapaneseModel.HONORIFIC.firstOrNull { word.length > it.length && word.endsWith(it) }?.let { word.dropLast(it.length) } ?: word
            val prefix = word.length > 1 && word[0] in "おご"
            return listOfNotNull(word, bare, word.drop(1).takeIf { prefix }, bare.drop(1).takeIf { prefix && bare.length > 1 }).distinct()
        }

        /**
         * What the dictionaries have for such a word ([tries]): the first try they have; with [base] (the splitter's
         * dictionary form) not among the tries, a conjugated word (お待ち: 待つ) or none, null (the usual rows decide), as for a
         * word that is neither. Empty: none, a card without a meaning. A one-kana piece is never tried: [piece], one kana
         * of a longer word (ご of ごひいきさん, お of おぉ, よ of およ) is never its headword.
         */
        fun whole(word: String, base: String, query: (String) -> List<YomitanDictionaries.Term>): List<YomitanDictionaries.Term>? {
            val tries = tries(word)
            if (tries.size < 2) return null
            query(word).takeIf { it.isNotEmpty() }?.let { return it }
            if (base !in tries && base.isNotEmpty() && !piece(base, word)) return null
            return tries.drop(1).filterNot { piece(it, word) }.firstNotNullOfOrNull { t -> query(t).takeIf { it.isNotEmpty() } }.orEmpty()
        }

        /**
         * Core plan 5b: which of the readings the dictionaries have for [word] ([terms]) it has at [begin] in [text]. Sudachi's
         * ([sudachi]) is one per spelling, not the line's (私 is always わたくし, 明日 あす, 日本 にっぽん, 一日 ついたち). The line's
         * own first: 何 before で, だ, の, と or a counter is なん, else なに; 一日 after 月 ついたち. After a kanji or a digit
         * (a suffix, a counter) Sudachi's stays (緑青館's 館 かん, ３人 にん). Else the most common: the first frequency dictionary's ranks, Sudachi's
         * unless ranked and ten times rarer (私: わたくし 28801, わたし 32 in JPDB; 方 かた 304 / ほう 117 stays かた; an unranked
         * one stays: 翡翠 ひすい); else [COMMON]. A line that writes the word in kana is its reading (まじないを唱える: 呪い
         * まじない, not the common のろい). A word with a card of its own ([hasCard]: its key) keeps that card's reading
         * unless the line decides (上の空's 上 うわ, 呪い まじない stay as Pedro has them). [entryFirst]: the longest match
         * that is an entry as the line writes it (上の空 from a tap on 上), longer than the tapped word, is the first row,
         * before its first kanji gets a reading of its own (上 うえ); a conjugated match (気にしない: 気にする) is not. [chosen]:
         * the reading the person picked (way 3, [PersonalDictionary.reading]) wins over all of it; [forms] then keeps a row
         * for a card the word has under another reading, so that card is still rated. [readings]: the word's readings, in
         * the dictionaries' order, the ones [next] (hold OK, a tap on the reading) goes through, wrapping round. ponytail: two line rules and a
         * four-word table; more go here as they turn up.
         */
        fun reading(text: String, begin: Int, word: String, sudachi: String, terms: List<YomitanDictionaries.Term>,
                    chosen: String? = null, hasCard: (String) -> Boolean = { false }): String {
            val own = terms.filter { it.expression == word && it.reading.isNotEmpty() && it.reading != word }
            val readings = readings(word, terms)
            if (readings.size < 2) return sudachi
            chosen?.takeIf { it in readings }?.let { return it }
            if (!text.startsWith(word, begin) && text.getOrNull(begin)?.let { kana(it.toString()) } == true)
                return hiragana(text.substring(begin)).let { l -> readings.filter { l.startsWith(it) }.maxByOrNull { it.length } } ?: sudachi
            val before = text.getOrNull(begin - 1)
            val after = text.getOrNull(begin + word.length)
            val line = when (word) {
                "何" -> if (after != null && (after in "でだのと" || after in COUNTERS)) "なん" else "なに"
                "一日" -> "ついたち".takeIf { before == '月' }
                else -> null
            }
            if (line != null && line in readings) return line
            if (before != null && (before.isDigit() || before in '一'..'鿿' || before == '々')) return sudachi
            readings.singleOrNull { hasCard(CardIndex.japaneseKey(word, it)) }?.let { return it }
            val dict = own.flatMap { it.frequencies }.firstOrNull()?.first
            val ranks = own.mapNotNull { t ->
                t.frequencies.firstOrNull { it.first == dict }?.second?.split(", ")?.filter { '㋕' !in it || word.none { c -> c in '一'..'鿿' } }
                    ?.mapNotNull { Regex("""\d+""").find(it)?.value?.toIntOrNull() }?.minOrNull()?.let { hiragana(t.reading) to it }
            }.groupBy({ it.first }, { it.second }).mapValues { it.value.min() }
            val top = ranks.minByOrNull { it.value }
            val mine = ranks[sudachi] ?: return COMMON[word]?.takeIf { it in readings } ?: sudachi
            return if (top != null && mine > 10 * top.value) top.key else sudachi
        }

        private const val COUNTERS = "人回度時歳年日月分個本枚匹台冊階番"
        private val COMMON = mapOf("私" to "わたし", "明日" to "あした", "日本" to "にほん", "一日" to "いちにち")

        fun readings(word: String, terms: List<YomitanDictionaries.Term>): List<String> =
            terms.filter { it.expression == word && it.reading.isNotEmpty() && it.reading != word }.map { hiragana(it.reading) }.distinct()

        fun next(readings: List<String>, now: String): String = if (readings.isEmpty()) now else readings[(readings.indexOf(now) + 1) % readings.size]

        fun entryFirst(rows: List<Form>, matched: String, entry: Form, tapped: Int): List<Form> =
            if (matched == entry.written && matched.length > tapped) rows.sortedBy { it.key != entry.key } else rows

        fun kana(s: String) = s.isNotEmpty() && s.all { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヶ' || it == 'ー' }

        fun piece(found: String, word: String) = word.length > 1 && found.length == 1 && (found[0] in 'ぁ'..'ゖ' || found[0] in 'ァ'..'ヶ')

        /** A hiragana word with a long mark that isn't its own (すごーい → すごい, keyed 凄い or すごい). */
        fun stretched(t: JapaneseModel.Token) = 'ー' in t.surface && t.surface.replace("ー", "").length > 1 &&
            t.surface.all { it == 'ー' || it in 'ぁ'..'ゖ' }

        /** Katakana -> hiragana (Sudachi gives readings in katakana, dictionaries in hiragana). */
        fun hiragana(s: String): String = buildString { for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }

        /** A reading's morae (きょう: きょ, う), for drawing its pitch (the TV's word card and the phone's popup). */
        fun morae(kana: String): List<String> {
            val small = "ゃゅょぁぃぅぇぉャュョァィゥェォ"
            val out = ArrayList<String>()
            for (c in kana) if (c in small && out.isNotEmpty()) out[out.lastIndex] = out.last() + c else out += c.toString()
            return out
        }

        /** Whether mora [i] is high with downstep [n]: heiban [0] low then high; atamadaka [1] high then low; [n] low, high up to mora n, then low. */
        fun high(i: Int, n: Int) = when (n) { 0 -> i > 0; 1 -> i == 0; else -> i in 1 until n }

        /** A reading, or "" when it mixes kana and Latin letters: Bee's 紅娘 has ほんgにあんg (pinyin half turned into kana). */
        fun cleanReading(r: String): String =
            if (r.any { it in 'a'..'z' || it in 'A'..'Z' } && r.any { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヶ' }) "" else r

        /** The same sound, however the kana spell it: とうきょう = トオキョオ = トーキョー, えいが = エエガ, きづく = キズク. */
        fun sameSound(a: String, b: String) = spoken(a) == spoken(b)

        private fun spoken(s: String) = buildString {
            for (c in hiragana(s).replace('づ', 'ず').replace('ぢ', 'じ')) {
                val vowel = lastOrNull()?.let { p -> VOWELS.firstOrNull { p in it }?.first() }
                append(when {
                    c == 'ー' && vowel != null -> vowel
                    c == 'う' && vowel == 'お' -> 'お'
                    c == 'い' && vowel == 'え' -> 'え'
                    else -> c
                })
            }
        }

        private val VOWELS = listOf("あかがさざただなはばぱまやゃらわぁ", "いきぎしじちにひびぴみりぃ", "うくぐすずつぬふぶぷむゆゅるぅ",
            "えけげせぜてでねへべぺめれぇ", "おこごそぞとどのほぼぽもよょろをぉ")
    }
}
