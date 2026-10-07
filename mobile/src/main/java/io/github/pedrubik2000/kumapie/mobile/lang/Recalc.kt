package io.github.pedrubik2000.kumapie.mobile.lang

import io.github.pedrubik2000.kumapie.i18n.tr

/**
 * morphs' Recalc (tools/morphs on the PC: recalc.py and scoring.py; keep them alike) on the device, behind a button:
 * new German cards ordered by their unknown words (i+1 by how common its unknown is, then i+2, ..., i+0 last), the
 * `_card-status` tags (Unlock and the PC read i+1) and the `am-study-morphs` field ("Sentence: <unknowns>"). Also MvJ's
 * definition unlock: a note whose monolingual definition has only known words (the card's own word aside) gets
 * `_mvj::def-is-ready` (the card then shows it unlocked), else `_mvj::def-has-unknowns` and "| Definition: <unknowns>". Words come from [KnownWords] (same spaCy model, same known rules), so the plan matches the
 * PC's. [plan] only reads; [apply] writes just what differs, through kuma3 Anki's provider.
 */
class Recalc(private val known: KnownWords, private val anki: AnkiCards = AnkiCards(known.context)) {

    class NoteChange(val id: Long, val tags: Set<String>, val fields: List<String>?)

    class Plan(val pkg: String, val dues: Map<Long, Long>, val notes: List<NoteChange>, val newCards: Int, val levels: Map<String, Int>,
               val defsReady: Int = 0, val defs: Int = 0) {
        val empty: Boolean get() = dues.isEmpty() && notes.isEmpty()
        fun describe(): String = tr("%1\$d new cards at i+1, %2\$d at i+2 or more, %3\$d at i+0 (of %4\$d new). " +
            "%5\$d of %6\$d German definitions unlocked. ", levels[READY] ?: 0, levels[NOT_READY] ?: 0, levels[KNOWN] ?: 0, newCards,
            defsReady, defs) +
            if (empty) tr("Nothing to change.") else tr("%1\$d change place, %2\$d notes get new tags or study words.", dues.size, notes.size)
    }

    /** Reads Anki again (word colours too) and works out what morphs' Recalc would change. */
    suspend fun plan(): Plan {
        known.refresh().getOrThrow()
        val r = known.lastReading ?: error(tr("Anki wasn't read."))
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

        // The definition's words that aren't known yet, the card's own word aside (MvJ's study morphs).
        val defUnknowns = r.defMorphs.mapValues { (nid, ws) -> ws.filter { it !in keys[nid].orEmpty() && known.status(it) != "k" } }

        val changes = ArrayList<NoteChange>()
        for (nid in modify + defUnknowns.keys) {
            val note = notes[nid] ?: continue
            var tags = note.tags
            if (nid in modify) {
                val want = if (nid in newNotes) setOfNotNull(wantTag[nid]) else note.tags.filter { it == KNOWN }.toSet()
                val have = note.tags.filter { it in STATUS_TAGS }.toSet()
                if (want != have) tags = tags - (have - want) + (want - have)
            }
            val defs = defUnknowns[nid]
            if (defs != null) tags = tags - DEF_TAGS + (if (defs.isEmpty()) KnownWords.DEF_READY else KnownWords.DEF_UNKNOWNS)
            var fields: List<String>? = null
            val old = note.fields[STUDY_FIELD]
            if ((nid in newNotes || defs != null) && old != null) {
                val sentence = if (nid in newNotes) keys[nid]!!.filter { known.status(it) == null } else null
                val new = studyField(old, sentence, defs)
                if (words(new) != words(old)) fields = note.fields.map { (name, v) -> if (name == STUDY_FIELD) new else v }
            }
            if (tags != note.tags || fields != null) changes += NoteChange(nid, tags, fields)
        }
        return Plan(r.pkg, dues, changes, newCards.size, levels, defUnknowns.count { it.value.isEmpty() }, defUnknowns.size)
    }

    /** Writes the plan: the new cards' order in one go, then each changed note. */
    fun apply(plan: Plan): String {
        val moved = if (plan.dues.isEmpty()) 0 else anki.setNewDues(plan.pkg, plan.dues)
        for (n in plan.notes) anki.updateNote(plan.pkg, n.id, n.tags, n.fields)
        return tr("Done: %1\$d cards placed, %2\$d notes updated.", moved, plan.notes.size)
    }

    companion object {
        const val READY = "_card-status::i+1"
        const val NOT_READY = "_card-status::i+≥2"
        const val KNOWN = "_card-status::i+0"
        val STATUS_TAGS = setOf(READY, NOT_READY, KNOWN)
        const val STUDY_FIELD = "am-study-morphs"
        val DEF_TAGS = setOf(KnownWords.DEF_READY, KnownWords.DEF_UNKNOWNS)

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

        /** New am-study-morphs value: "Sentence: <unknowns> | Definition: <unknowns>"; a null list keeps that part as it is. */
        fun studyField(current: String, unknowns: List<String>?, defUnknowns: List<String>? = null): String {
            val (oldSentence, oldDefinition) = words(current)
            val sentence = (unknowns ?: oldSentence.toList()).joinToString(", ")
            val definition = (defUnknowns ?: oldDefinition.toList()).joinToString(", ")
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
