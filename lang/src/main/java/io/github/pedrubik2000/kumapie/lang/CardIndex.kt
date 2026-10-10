package io.github.pedrubik2000.kumapie.lang

/**
 * Each word form -> its own word cards in kuma3 (kumapie_anki_review_plan.md): notes with a Word field (Core 1000,
 * mined words), matched by the exact form ("gehst" is not gehen's card), and which of them kuma3 shows today.
 */
class CardIndex {
    @Volatile private var byForm: Map<String, List<AnkiCards.Card>> = emptyMap()
    /** Card id -> "new" | "learn" | "review": kuma3's queue today; null = not known (an Anki without `kuma3/due`). */
    @Volatile var due: Map<Long, String>? = null
        private set

    /** From the notes and cards [KnownWords] read: the word notes' cards under each of their [keys] ([Language.cardKeys]). */
    fun build(notes: List<AnkiCards.Note>, cards: List<AnkiCards.Card>, due: Map<Long, String>?,
              keys: (AnkiCards.Note) -> List<String> = ::spacedKeys) {
        val keysOf = notes.associate { it.id to keys(it) }.filterValues { it.isNotEmpty() }
        byForm = cards.filter { it.noteId in keysOf }.flatMap { c -> keysOf[c.noteId]!!.map { it to c } }
            .groupBy({ it.first }, { it.second })
        this.due = due
    }

    /** Cards read again (after a rating or Undo; none: only the queue) and kuma3's queue now. */
    fun update(fresh: List<AnkiCards.Card>, due: Map<Long, String>?) {
        val byId = fresh.associateBy { it.id }
        byForm = byForm.mapValues { (_, cs) -> cs.map { byId[it.id] ?: it } }
        this.due = due
    }

    /**
     * [io.github.pedrubik2000.kumapie.data.Word.card] of a form: "d" an own card is due for review today, "k" studied
     * and not due, "n" new or still learning, "u" no card of its own; null when kuma3's queue isn't known.
     */
    fun state(form: String): String? {
        val due = due ?: return null
        val own = byForm[form.lowercase()] ?: return "u"
        return when {
            own.any { due[it.id] == "review" } -> "d"
            own.any { it.type == 2 } -> "k"
            else -> "n"
        }
    }

    /** The form's own cards. */
    fun cards(form: String): List<AnkiCards.Card> = byForm[form.lowercase()].orEmpty()

    /** What one rating answers: every card of the form kuma3 shows today, else its first card (an early review). */
    fun toAnswer(form: String): List<AnkiCards.Card> {
        val own = cards(form)
        return own.filter { due?.containsKey(it.id) == true }.ifEmpty { own.take(1) }
    }

    companion object {
        /**
         * A Word field as its form: no HTML tags or entities (mined words are escaped: "Wenn&#39;s"), nothing from "[" on
         * ("ist[→ sein]", "haben[hat, hatte, …]"), lowercase.
         */
        fun form(field: String): String = plain(field).substringBefore('[').trim().lowercase()

        /** German, English: the Word field's form. */
        fun spacedKeys(note: AnkiCards.Note): List<String> = listOfNotNull(note.fields["Word"]?.let(::form)?.takeIf { it.isNotEmpty() })

        /**
         * Japanese: "spelling\treading" (上手/じょうず is not 上手/うわて), then the spelling alone (the subtitle colours:
         * episodes keep no readings). Word fields as Kaishi and kumapie write them (`日本[にほん] 語[ご]:0-`, `人[*ひと]:2-`,
         * `拾[ひろ]\う:0`, `気[き]:0 に / 入[い]る:0`, `みんな:3-`), or JPDB's Word + Reading fields.
         */
        fun japaneseKeys(note: AnkiCards.Note): List<String> {
            val field = note.fields["Word"] ?: return emptyList()
            // Anki furigana: "base[reading]", the base being the text since the last space.
            val s = plain(field).replace(Regex(""":[0-9A-Za-z\-]*"""), "").replace(Regex("""[/*\\-]"""), "")
            val ruby = Regex("""([^\s\[\]]+)\[([^\]]*)]""")
            val spelling = s.replace(ruby, "$1").replace(Regex("""\s"""), "")
            if (spelling.isEmpty()) return emptyList()
            val reading = note.fields["Reading"]?.let(::plain)?.trim()?.takeIf { it.isNotEmpty() }
                ?: s.replace(ruby, "$2").replace(Regex("""\s"""), "")
            return listOf(japaneseKey(spelling, reading), spelling)
        }

        /** A Japanese word's key: as written, tab, its reading in hiragana (kana-only words: the word itself). */
        fun japaneseKey(written: String, reading: String): String =
            written + "\t" + JapaneseLookup.hiragana(reading.ifBlank { written })

        /** A field without HTML tags or entities. */
        private fun plain(field: String): String = field.replace(Regex("<[^>]*>"), "")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
