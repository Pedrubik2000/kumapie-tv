package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.player.SceneController

/**
 * What goes on a word card, one implementation for the phone's mine sheet and rating rows and the TV's hold ↑
 * (kumapie_plan.md "still in both apps" 4a). The meaning is the one that fits the line (a card explains only the
 * meaning its sentence uses: Pedro, 2026-10-01), else the PC's meaning for the line, else the first.
 */
object WordCard {
    /** One meaning to choose for a word card ([label] as listed, [gloss] as it goes on the card). */
    data class Choice(val label: String, val gloss: String, val example: Pair<String, String>?)

    /** A word card's meanings, the one picked at first, monolingual definitions, and whether a meaning fit the line. */
    class Choices(val choices: List<Choice>, val chosen: Int, val monos: List<Choice>, val fits: Boolean = false)

    /** A dictionary's short name in a list: its first word ("Jitendex", "JMdict"), else the title cut. */
    fun shortDictName(dict: String): String {
        val word = Regex("^[A-Za-z]+").find(dict)?.value ?: return dict.take(24)
        return if (word.any { it.isUpperCase() }) word else word.replaceFirstChar { it.uppercase() }
    }

    /**
     * German / English: the PC's meaning for the line (listed first, as "kumapie: …") and every dictionary sense, the
     * one that fits the line's English picked; monolingual dictionaries (wty-de-de, wty-en-en) give the definitions.
     */
    suspend fun choices(language: Language, yomitan: YomitanDictionaries, ctl: SceneController, line: Int, surface: String, key: String): Choices {
        val scene = ctl.scene
        val word = ctl.words[key]
        val def = scene.def(line, key)
        val lemma = def?.lemma?.takeIf { it.isNotBlank() } ?: word?.lemma?.takeIf { it.isNotBlank() }
        val own = (def?.english?.takeIf { it.isNotBlank() } ?: (scene.meanings[key] ?: word?.meaning)?.substringAfter(" = "))
            ?.takeIf { it.isNotBlank() }
        val list = mutableListOf<Choice>()
        own?.let { list += Choice("kumapie: $it", it, null) }
        val all = language.entries(Tap(surface, 0, surface.length, key, lemma))
        val isMono = { e: DictEntry -> yomitan.groupOf(ctl.lang, e.dict) == "Monolingual" }
        val entries = all.filterNot(isMono).ifEmpty { all }
        val monos = all.filter(isMono).flatMap { e -> e.senses.take(3).map { Choice("${e.word} (${e.pos}): ${it.gloss}", it.gloss, null) } }
            .distinctBy { it.gloss }.take(8)
        val fits = SensePick.best(entries, SensePick.english(scene, line))
        var fitting = -1
        val seen = HashMap<String, Int>() // the same entry can come twice (by surface and by lemma): one row per meaning
        entries.forEachIndexed { i, e ->
            e.senses.forEachIndexed { j, s ->
                seen[e.pos + "\u0000" + s.gloss]?.let { if (fits == i to j) fitting = it; return@forEachIndexed }
                seen[e.pos + "\u0000" + s.gloss] = list.size
                if (fits == i to j) fitting = list.size
                list += Choice("${e.word} (${e.pos}): ${s.gloss}" + if (fits == i to j) tr("  · fits this line") else "", s.gloss,
                    s.examples.firstOrNull())
            }
        }
        return Choices(list, pick(fitting), monos, fitting >= 0)
    }

    /** The first pick: the sense that fits the line (Pedro, 2026-11-19: before the PC's meaning), else the first. */
    fun pick(fitting: Int): Int = if (fitting >= 0) fitting else 0

    /** A German / English word card as [choices] picks it at first: meaning, its example, the first monolingual definition. */
    suspend fun auto(language: Language, yomitan: YomitanDictionaries, ctl: SceneController, line: Int, surface: String, key: String): Miner.Word {
        val c = choices(language, yomitan, ctl, line, surface, key)
        val pick = c.choices.getOrNull(c.chosen) // none: the card is made without a meaning
        val def = ctl.scene.def(line, key)
        val lemma = def?.lemma?.takeIf { it.isNotBlank() } ?: ctl.words[key]?.lemma?.takeIf { it.isNotBlank() }
        return Miner.Word(surface, key, lemma, pick?.gloss.orEmpty(), pick?.example, def, mono = c.monos.firstOrNull()?.gloss)
    }

    /**
     * A subtitle word's headwords, [headword] first, then the other rows' (気にしない: 気にする): the TV's word card, the phone's
     * popup and mine sheet show these, so they agree with the ratings and the card (core plan 5e). [written]: the word as
     * the line has it, [word]: its dictionary form (the episode's lemma or key). [popup]: a tap's, these for a subtitle
     * word, what the dictionaries find for a word inside a definition (or German, English).
     */
    suspend fun japaneseHeadwords(japanese: Japanese, text: String, offset: Int, written: String, word: String, reading: String): List<Headword> {
        val forms = if (japanese.model.isReady) runCatching { japanese.forms(text, offset) }.getOrDefault(emptyList()) else emptyList()
        val first = headword(forms, reading, written, word) { japanese.yomitan.query(Lang.JAPANESE, it) } ?: return emptyList()
        return (listOf(first) + forms.mapNotNull { it.head }).distinctBy { it.expression to it.reading }
    }

    suspend fun japaneseWord(japanese: Japanese, text: String, offset: Int, surface: String, key: String, word: String, reading: String, english: String?): Miner.Word =
        japaneseHeadwords(japanese, text, offset, surface, word, reading).firstOrNull()?.let { japanese(japanese.yomitan, it, surface, key, null, null, english) }
            ?: Miner.Word(surface, key, word, "")

    suspend fun popup(language: Language, tap: Tap, inLine: Boolean, reading: String): List<Headword> =
        if (inLine && language is Japanese) japaneseHeadwords(language, tap.text, tap.offset, tap.word, tap.lemma ?: tap.key ?: tap.word, reading)
        else language.lookup(tap)

    /**
     * The headword a Japanese word card is made for, the TV's and the phone's (core plan step 5): the first rating row's
     * ([forms]: your own word, a name, the dictionary form covering the whole word, お料理 as 料理), else [written] the
     * dictionaries have with its お/ご or honorific or without them (ごひいきさん: 贔屓, [JapaneseLookup.whole]), else [word]
     * as the dictionaries have it ([query]; read [reading] first, else a common entry ⭐: もらえるでしょ is 貰える). Never a
     * shorter piece (ドラート is not ドラー, item 26; ごひいきさん not ご): then no headword, a card without a meaning.
     */
    fun headword(forms: List<JapaneseLookup.Form>, reading: String, written: String, word: String, query: (String) -> List<YomitanDictionaries.Term>): Headword? {
        forms.firstNotNullOfOrNull { it.head }?.let { return it }
        val terms = JapaneseLookup.whole(written, word, query) ?: if (JapaneseLookup.piece(word, written)) emptyList() else query(word)
        val first = terms.firstOrNull { reading.isNotBlank() && JapaneseLookup.hiragana(it.reading) == reading }
            ?: terms.firstOrNull { t -> t.glossaries.any { "⭐" in it.tags || "★" in it.tags } } ?: terms.firstOrNull() ?: return null
        return Headword(first.expression, JapaneseLookup.hiragana(first.reading), terms.filter { it.expression == first.expression && it.reading == first.reading })
    }

    /**
     * A Japanese headword's meanings: from the bilingual dictionaries (else every one but monolingual and forms ones),
     * the personal dictionary's as written; the one that fits [english] (the line's English) chosen and marked; and
     * monolingual definitions, one per dictionary, encyclopedias (Pixiv, Wikipedia…) last so a 国語 dictionary leads.
     */
    fun japaneseChoices(yomitan: YomitanDictionaries, hw: Headword?, english: String?): Choices {
        val ja = Lang.JAPANESE
        val glossaries = hw?.terms?.flatMap { it.glossaries }.orEmpty()
        fun group(dict: String) = yomitan.groupOf(ja, dict)
        val isMono = { g: YomitanDictionaries.Glossary -> group(g.dict).contains("mono", ignoreCase = true) }
        val isBilingual = { g: YomitanDictionaries.Glossary -> group(g.dict).contains("bilingual", ignoreCase = true) }
        val meaningDicts = glossaries.filter(isBilingual).ifEmpty {
            glossaries.filterNot { isMono(it) || group(it.dict).contains("form", ignoreCase = true) || it.dict.contains("form", ignoreCase = true) }
        }
        fun choice(g: YomitanDictionaries.Glossary, m: String) = Choice("${shortDictName(g.dict)}: $m", m, null)
        var choices = meaningDicts.flatMap { g ->
            // 12: a Jitendex entry is one meaning per sense, and the one that fits can be far down (取る "to gather").
            (if (g.dict == PersonalDictionary.TITLE) g.senses // as you wrote it, in any language
            else g.senses.flatMap { JapaneseLookup.bilingualMeanings(it) }).distinct().take(12).map { choice(g, it) }
        }.distinctBy { it.gloss }.take(40)
        var chosen = 0
        var fits = false
        english?.let { SensePick.bestMeaning(choices.map { c -> c.gloss }, it) }?.let { i ->
            fits = true
            choices = choices.mapIndexed { k, c -> if (k == i) c.copy(label = c.label + tr("  · fits this line")) else c }
            chosen = i
        }
        val encyclopedia = Regex("pixiv|wiki|ニコ|百科", RegexOption.IGNORE_CASE)
        val monos = glossaries.filter(isMono).sortedBy { if (encyclopedia.containsMatchIn(it.dict)) 1 else 0 }
            .mapNotNull { g -> g.senses.firstNotNullOfOrNull { JapaneseLookup.monolingualDefinition(it) }?.let { choice(g, it) } }
            .distinctBy { it.gloss }.take(8)
        return Choices(choices, chosen, monos, fits)
    }

    /**
     * A Japanese word card for [hw]: written as [written] (null: the headword itself), with [reading]; the meaning that
     * fits [english] (null: the first), the first monolingual definition; pitch only for the headword itself, and
     * "form of 気にする" for a conjugated form. No meaning (and no "form of") when the dictionaries have none.
     */
    fun japanese(yomitan: YomitanDictionaries, hw: Headword?, surface: String, key: String?, written: String?, reading: String?,
                 english: String?): Miner.Word {
        val c = japaneseChoices(yomitan, hw, english)
        val pick = c.choices.getOrNull(c.chosen)
        val asWritten = written ?: hw?.expression
        val own = asWritten == hw?.expression
        return Miner.Word(surface, key, asWritten, pick?.gloss.orEmpty(), reading = (reading ?: hw?.reading)?.takeIf { it.isNotBlank() },
            pitch = if (own) hw?.pitches?.flatMap { it.second }?.firstOrNull() else null, mono = c.monos.firstOrNull()?.gloss,
            formOf = hw?.expression?.takeIf { !own })
    }
}
