package io.github.pedrubik2000.kumapie.lang

/**
 * Each word form -> its own word cards in kuma3 (kumapie_anki_review_plan.md): notes with a Word field (Core 1000,
 * mined words), matched by the exact form ("gehst" is not gehen's card), and which of them kuma3 shows today.
 */
class CardIndex {
    @Volatile private var byForm: Map<String, List<AnkiCards.Card>> = emptyMap()
    /** Card id -> "new" | "learn" | "review": kuma3's queue today; null = not known (an Anki without `kuma3/due`). */
    @Volatile private var due: Map<Long, String>? = null

    /** From the notes and cards [KnownWords] read: the word notes' cards by form. */
    fun build(notes: List<AnkiCards.Note>, cards: List<AnkiCards.Card>, due: Map<Long, String>?) {
        val formOf = notes.mapNotNull { n -> n.fields["Word"]?.let(::form)?.takeIf { it.isNotEmpty() }?.let { n.id to it } }.toMap()
        byForm = cards.filter { it.noteId in formOf }.groupBy { formOf[it.noteId]!! }
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
        /** A Word field as its form: no HTML, nothing from "[" on ("ist[→ sein]", "haben[hat, hatte, …]"), lowercase. */
        fun form(field: String): String = field.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ")
            .substringBefore('[').trim().lowercase()
    }
}
