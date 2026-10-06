package io.github.pedrubik2000.kumapie.mobile.german

/**
 * morphs' Recalc (tools/morphs on the PC: recalc.py and scoring.py; keep them alike) on the device, behind a button:
 * new German cards ordered by their unknown words (i+1 by how common its unknown is, then i+2, ..., i+0 last), the
 * `_card-status` tags (Unlock and the PC read i+1) and the `am-study-morphs` field ("Sentence: <unknowns>", MvJ's
 * "| Definition: .." kept). Words come from [KnownWords] (same spaCy model, same known rules), so the plan matches the
 * PC's. [plan] only reads; [apply] writes just what differs, through kuma3 Anki's provider.
 */
class Recalc(private val known: KnownWords, private val anki: AnkiCards = AnkiCards(known.context)) {

    class NoteChange(val id: Long, val tags: Set<String>, val fields: List<String>?)

    class Plan(val pkg: String, val dues: Map<Long, Long>, val notes: List<NoteChange>, val newCards: Int, val levels: Map<String, Int>) {
        val empty: Boolean get() = dues.isEmpty() && notes.isEmpty()
        fun describe(): String = "${levels[READY] ?: 0} new cards at i+1, ${levels[NOT_READY] ?: 0} at i+2 or more, " +
            "${levels[KNOWN] ?: 0} at i+0 (of $newCards new). " +
            if (empty) "Nothing to change." else "${dues.size} change place, ${notes.size} notes get new tags or study words."
    }

    /** Reads Anki again (word colours too) and works out what morphs' Recalc would change. */
    suspend fun plan(): Plan {
        known.refresh().getOrThrow()
        val r = known.lastReading ?: error("Anki wasn't read.")
        val notes = r.notes.associateBy { it.id }
        val keys = r.morphs // judged notes only (Nicos Weg is not), inflections in text order
        val modify = keys.keys.filter { with(known) { !notes[it]!!.hasTag(KnownWords.CORE1000) } }.toSet()
        val manual = keys.keys.filter { with(known) { notes[it]!!.hasTag(KnownWords.KNOWN_MANUALLY) } }.toSet()
        val rank = ranks(keys.values)

        val newCards = r.cards.filter { !it.reviewed && it.noteId in modify }
        val newNotes = newCards.map { it.noteId }.toSet()
        val dues = HashMap<Long, Long>()
        val levels = HashMap<String, Int>()
        val wantTag = HashMap<Long, String?>()
        for (card in newCards) {
            val morphs = keys[card.noteId]!!
            val unknowns = morphs.filter { known.status(it) == null }
            val learning = morphs.any { known.status(it) == "l" }
            val tag = statusTag(unknowns.size, learning, card.noteId in manual)
            wantTag[card.noteId] = tag
            tag?.let { levels[it] = (levels[it] ?: 0) + 1 }
            val due = score(unknowns, morphs.size, rank)
            if (due != card.due) dues[card.id] = due
        }

        val changes = ArrayList<NoteChange>()
        for (nid in modify) {
            val note = notes[nid]!!
            val want = if (nid in newNotes) setOfNotNull(wantTag[nid]) else note.tags.filter { it == KNOWN }.toSet()
            val have = note.tags.filter { it in STATUS_TAGS }.toSet()
            val tags = if (want == have) note.tags else note.tags - (have - want) + (want - have)
            var fields: List<String>? = null
            val old = note.fields[STUDY_FIELD]
            if (nid in newNotes && old != null) {
                val new = studyField(old, keys[nid]!!.filter { known.status(it) == null })
                if (words(new) != words(old)) fields = note.fields.map { (name, v) -> if (name == STUDY_FIELD) new else v }
            }
            if (tags != note.tags || fields != null) changes += NoteChange(nid, tags, fields)
        }
        return Plan(r.pkg, dues, changes, newCards.size, levels)
    }

    /** Writes the plan: the new cards' order in one go, then each changed note. */
    fun apply(plan: Plan): String {
        val moved = if (plan.dues.isEmpty()) 0 else anki.setNewDues(plan.pkg, plan.dues)
        for (n in plan.notes) anki.updateNote(plan.pkg, n.id, n.tags, n.fields)
        return "Done: $moved cards placed, ${plan.notes.size} notes updated."
    }

    companion object {
        const val READY = "_card-status::i+1"
        const val NOT_READY = "_card-status::i+≥2"
        const val KNOWN = "_card-status::i+0"
        val STATUS_TAGS = setOf(READY, NOT_READY, KNOWN)
        const val STUDY_FIELD = "am-study-morphs"

        // scoring.py, with the PC's [morphs.scoring] settings.
        private const val ONE_UNKNOWN_MAX = 999_999L
        private const val PER_EXTRA_UNKNOWN = 1_000_000L
        private const val MAX_NOT_READY = 1_999_999_999L
        private const val ZERO_UNKNOWNS = 2_000_000_000L
        private const val LENGTH_LO = 4
        private const val LENGTH_HI = 7
        private const val LENGTH_PENALTY = 1000L

        /** Rank 1 = the inflection in the most notes; ties alphabetical (Python's string order = code points). */
        fun ranks(noteKeys: Collection<List<String>>): Map<String, Int> {
            val counts = HashMap<String, Int>()
            for (keys in noteKeys) for (k in keys.toSet()) counts[k] = (counts[k] ?: 0) + 1
            return counts.keys.sortedWith { a, b -> counts[b]!!.compareTo(counts[a]!!).takeIf { it != 0 } ?: codePointCompare(a, b) }
                .withIndex().associate { (i, k) -> k to i + 1 }
        }

        private fun codePointCompare(a: String, b: String): Int {
            val x = a.codePoints().toArray()
            val y = b.codePoints().toArray()
            for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return x[i].compareTo(y[i])
            return x.size.compareTo(y.size)
        }

        /** Lower is sooner: i+1 by how common its unknown is, then i+2, i+3, ..., and i+0 last. */
        fun score(unknowns: List<String>, nMorphs: Int, rank: Map<String, Int>): Long {
            val length = LENGTH_PENALTY * (maxOf(0, LENGTH_LO - nMorphs) + maxOf(0, nMorphs - LENGTH_HI))
            if (unknowns.isEmpty()) return ZERO_UNKNOWNS + minOf(length, 1_000_000L)
            val base = unknowns.sumOf { rank[it]!!.toLong() } + length
            if (unknowns.size == 1) return minOf(base, ONE_UNKNOWN_MAX)
            return minOf(PER_EXTRA_UNKNOWN * (unknowns.size - 1) + minOf(base, ONE_UNKNOWN_MAX), MAX_NOT_READY)
        }

        /** The one status tag a new card's note should have (AnkiMorphs' rules). */
        fun statusTag(unknowns: Int, learning: Boolean, manual: Boolean): String? = when {
            manual -> null
            unknowns == 0 -> if (learning) null else KNOWN
            unknowns == 1 -> READY
            else -> NOT_READY
        }

        /** New am-study-morphs value: our "Sentence: .." part, MvJ's "Definition: .." kept. */
        fun studyField(current: String, unknowns: List<String>): String {
            var definition = ""
            val cur = current.trim()
            if ("Sentence:" in cur || "Definition:" in cur) {
                for (part in cur.split("|").map { it.trim() }) if (part.startsWith("Definition:")) definition = part.removePrefix("Definition:").trim()
            }
            val sentence = unknowns.joinToString(", ")
            return when {
                sentence.isNotEmpty() && definition.isNotEmpty() -> "Sentence: $sentence | Definition: $definition"
                sentence.isNotEmpty() -> "Sentence: $sentence"
                definition.isNotEmpty() -> "Definition: $definition"
                else -> ""
            }
        }

        /** A study value as (sentence words, definition words): MvJ reads it as sets, so order alone is no change. */
        fun words(study: String): Pair<Set<String>, Set<String>> {
            val sentence = HashSet<String>()
            val definition = HashSet<String>()
            for (raw in study.split("|")) {
                val part = raw.trim()
                val target = if (part.startsWith("Definition:")) definition else sentence
                val text = if (part.startsWith("Sentence:") || part.startsWith("Definition:")) part.substringAfter(":") else part
                text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { target += it }
            }
            return sentence to definition
        }
    }
}
