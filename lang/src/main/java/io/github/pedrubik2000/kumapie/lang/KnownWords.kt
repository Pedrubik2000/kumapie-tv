package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import android.content.Context
import android.util.Log
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Which words of a language Pedro knows, worked out on the device from his Anki (kuma3 Anki) the way morphs does on
 * the PC, so the subtitles' colours no longer need the PC:
 * - German notes' fields are parsed by spaCy (same model as the PC) into morphs, keyed by inflection; Japanese
 *   notes' by Sudachi ([JapaneseModel]), keyed by dictionary form;
 * - a word is **known** (white) when one of its reviewed cards has FSRS stability >= the threshold (Settings,
 *   7 days by default), its note is tagged known-manually, or it was marked known in the app;
 *   **learning** (orange) when it is only on reviewed cards below that; else **unknown** (red).
 * The result is saved, so the app colours words offline and at once on start; [refresh] reads Anki again.
 */
class KnownWords(val context: Context, private val settings: Settings, private val language: Language) {
    val lang: Lang get() = language.lang
    private val anki = AnkiCards(context)
    /** This language's parser is downloaded. */
    val modelReady: Boolean get() = language.model.isReady
    private val dir = File(context.filesDir, if (lang == Lang.GERMAN) "known" else "known-${lang.code}").apply { mkdirs() }
    private val savedFile = File(dir, "words.json")
    private val parseCache = File(dir, "parses.json")
    private val markedFile = File(dir, "marked.txt")
    private val lock = Mutex()

    /** What one inflection's cards say. */
    data class Facts(val best: Double?, val reviewed: Boolean, val manual: Boolean)

    data class Snapshot(val words: Map<String, Facts>, val time: Long, val app: String, val notes: Int, val cards: Int)

    @Volatile private var snapshot: Snapshot? = load()

    /**
     * What the last [refresh] read from Anki: notes, cards, each judged note's inflections in text order, and the
     * inflections of each note's monolingual definition ([DEF_MONO], when it has one).
     */
    class Reading(val pkg: String, val notes: List<AnkiCards.Note>, val cards: List<AnkiCards.Card>, val morphs: Map<Long, List<String>>,
                  val defMorphs: Map<Long, List<String>> = emptyMap())
    @Volatile var lastReading: Reading? = null
        private set
    @Volatile private var marked: Set<String> = runCatching { markedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
        .getOrDefault(emptySet())

    private val _status = MutableStateFlow(describe())
    /** One line for Settings: what the colours come from now, or what went wrong. */
    val status: StateFlow<String> = _status

    var threshold: Double
        get() = settings.prefs.getFloat("known_stability", 7f).toDouble()
        set(value) {
            settings.prefs.edit().putFloat("known_stability", value.toFloat()).apply()
            _status.value = describe()
        }

    /** This language's notes in Anki ("note:🐻 German" OR "note:🇩🇪 MvJ"). */
    private val search get() = noteTypes.joinToString(" OR ", "(", ")") { "\"note:$it\"" }

    /** The Anki note type with the German sentences and Core 1000 words: its new name first, then the old one. */
    val noteTypes = lang.noteTypes

    /** The AnkiDroid to read: the first installed one (kuma3 Anki first), or null. */
    fun ankiApp(): String? = anki.installed().firstOrNull()
    fun permission(pkg: String) = anki.permission(pkg)
    fun hasPermission(pkg: String) = anki.hasPermission(pkg)

    /** "k" known, "l" learning, null unknown - or null for everything when Anki was never read. */
    fun status(inflection: String): String? {
        if (inflection in marked) return "k"
        val f = snapshot?.words?.get(inflection) ?: return null
        return when {
            f.manual || (f.best != null && f.best >= threshold) -> "k"
            f.reviewed -> "l"
            else -> null
        }
    }

    val ready: Boolean get() = snapshot != null

    /**
     * [status] by cards (kumapie_anki_review_plan.md step 5, for Recalc's order): "k" when marked known or its own card
     * is studied (due or not), null otherwise; without kuma3's queue, [status] (stability) as before.
     */
    fun statusByCard(word: String): String? = if (word in marked) "k" else when (cardIndex.state(word)) {
        null -> status(word)
        "k", "d" -> "k"
        else -> null
    }

    /** This language's word cards by form and kuma3's queue today: [EpisodeDetail] words' [io.github.pedrubik2000.kumapie.data.Word.card]. */
    val cardIndex = CardIndex()
    @Volatile private var dueAt = 0L

    /** The word cards again without parsing (a card was just mined here); kuma3's queue as it was (a new card isn't due). */
    suspend fun reloadCards() = withContext(Dispatchers.IO) {
        val pkg = lastReading?.pkg ?: return@withContext
        cardIndex.build(anki.notes(pkg, search), anki.cards(pkg, search), cardIndex.due, language::cardKeys)
    }

    /** kuma3's queue again (opening an episode), at most once a minute: every episode of a list opens at once. */
    suspend fun refreshDue(force: Boolean = false) = withContext(Dispatchers.IO) {
        val pkg = lastReading?.pkg ?: return@withContext
        if (!force && System.currentTimeMillis() - dueAt < 60_000) return@withContext
        dueAt = System.currentTimeMillis()
        cardIndex.update(emptyList(), anki.due(pkg))
    }

    /** The Anki day of [ms]: days start at 4:00 local time (kuma3's default "next day starts at"). */
    private fun ankiDay(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).minusHours(4).toLocalDate()

    /** A rating from a word card: the notes and how many cards it answered, and kuma3's Undo label right after. */
    class Rated(val notes: List<Long>, val cards: Int, val undoLabel: String)

    /**
     * Rates the form's own cards in kuma3 ([CardIndex.toAnswer]: every due one, else the first) with [ease] (1 Again ..
     * 4 Easy), [ms] since the word card opened (kuma3 caps it as its reviewer does); null when kuma3 took none.
     */
    suspend fun rate(form: String, ease: Int, ms: Long): Rated? = withContext(Dispatchers.IO) {
        val pkg = lastReading?.pkg ?: return@withContext null
        // kuma3's first queue read on a new day (4:00) clears its Undo (rslib clear_queues_if_day_changed): read it before
        // the first rating of the day, not right after it, or that rating can't be undone.
        if (ankiDay(dueAt) != ankiDay(System.currentTimeMillis())) refreshDue(force = true)
        val answer = cardIndex.toAnswer(form)
        if (answer.isEmpty()) return@withContext null // no card of its own found (a Word field kumapie can't match)
        for (c in answer) anki.answer(pkg, c.noteId, c.ord, ease, ms)
        val notes = answer.map { it.noteId }.distinct()
        val fetched = cardsOf(pkg, notes)
        // kuma3's provider only logs an answer it refused: count the cards whose reviews went up.
        val reps = fetched.associate { it.id to it.reps }
        val answered = answer.filter { (reps[it.id] ?: 0) > it.reps }.map { it.id }.toSet()
        // Not due now; kuma3's queue is read again when the player leaves the scene.
        cardIndex.update(fetched, cardIndex.due?.minus(answered))
        val label = anki.undoLabel(pkg)
        Log.i("kumapie", "rate: $form ease $ease, ${answered.size} of ${answer.size} cards answered, kuma3 Undo: '$label'")
        if (answered.isEmpty()) null else Rated(notes, answered.size, label)
    }

    /**
     * Undoes [r] with kuma3's Undo, once per card it answered, only while Undo still names that rating (nothing else
     * done in kuma3 since); a card the rating made stays, new. False when it couldn't, or kuma3 took no review back
     * (its provider doesn't say: the cards' reviews must go down).
     */
    suspend fun undo(r: Rated): Boolean = withContext(Dispatchers.IO) {
        val pkg = lastReading?.pkg ?: return@withContext false
        val label = anki.undoLabel(pkg)
        Log.i("kumapie", "undo: kuma3 says '$label', the rating said '${r.undoLabel}', ${r.cards} cards")
        if (r.undoLabel.isEmpty() || label != r.undoLabel) return@withContext false
        val before = cardsOf(pkg, r.notes).associate { it.id to it.reps }
        repeat(r.cards) { if (anki.undoLabel(pkg).isNotEmpty()) anki.undo(pkg) }
        val after = cardsOf(pkg, r.notes)
        cardIndex.update(after, cardIndex.due) // the queue comes with the player's next read
        val undone = after.count { it.reps < (before[it.id] ?: 0) }
        Log.i("kumapie", "undo: $undone of ${r.cards} reviews taken back, kuma3 now says '${anki.undoLabel(pkg)}'")
        undone > 0
    }

    private fun cardsOf(pkg: String, notes: List<Long>) = anki.cards(pkg, "nid:" + notes.joinToString(","))

    /**
     * The episode's word colours and scene levels from the device's own known words, replacing the PC's, once Anki
     * has been read here. Words the PC marked known (`m`) stay known, as on the PC.
     */
    fun apply(episode: EpisodeDetail): EpisodeDetail {
        val snap = snapshot ?: return episode
        val pcMarked = episode.words.filterValues { it.marked }.keys
        if (!marked.containsAll(pcMarked)) setMarked(marked + pcMarked)
        // Japanese words are kept by dictionary form; each way the episode writes one is coloured by its own card.
        val written = if (language !is Japanese) emptyMap() else episode.scenes.asSequence().flatMap { it.cues }.flatMap { it.segments }
            .filter { it.word != null }.groupBy({ it.word!! }, { it.text })
        val words = episode.words.mapValues { (key, w) ->
            paint(key, w.copy(status = status(key) ?: "u", stability = snap.words[key]?.best, marked = key in marked,
                forms = written[key]?.associateWith { "" }))
        }
        return episode.copy(words = words, scenes = levels(episode.scenes, words))
    }

    /** Each scene's level (i+N) from the words' colours (kumapie_anki_review_plan.md step 5: [levelOf]). */
    private fun levels(scenes: List<io.github.pedrubik2000.kumapie.data.Scene>, words: Map<String, io.github.pedrubik2000.kumapie.data.Word>) =
        scenes.map { s -> if (!s.target) s else s.copy(level = io.github.pedrubik2000.kumapie.data.levelOf(s, words)) }

    /** Word [w] (episode key [key]) with its card states from [cardIndex] now: its own, and its written forms'. */
    fun paint(key: String, w: io.github.pedrubik2000.kumapie.data.Word) =
        w.copy(card = cardIndex.state(key), forms = w.forms?.mapValues { (form, _) -> cardIndex.state(form) ?: "" })

    /** A word marked known in the app (or unmarked); answers its status now. */
    fun mark(word: String, known: Boolean): String {
        setMarked(if (known) marked + word else marked - word)
        return status(word) ?: "u"
    }

    /** Words marked known in kumapie (and the PC's), as [Progress] merged them from every device. */
    val markedWords: Set<String> get() = marked

    fun useMarked(words: Set<String>) {
        if (words != marked) setMarked(words)
        _status.value = describe()
    }

    private fun setMarked(words: Set<String>) {
        marked = words
        runCatching { markedFile.writeText(words.sorted().joinToString("\n")) }
    }

    /** Reads Anki again and works out every word's facts (parsing only new or changed fields). */
    suspend fun refresh(): Result<Snapshot> = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val pkg = ankiApp() ?: error(tr("No AnkiDroid found on this device."))
                if (!hasPermission(pkg)) error(tr("kumapie may not read Anki yet: allow it in Settings."))
                if (!modelReady) error(if (lang == Lang.JAPANESE) tr("The %s dictionary isn't downloaded yet.", lang.displayName)
                    else tr("The %s model isn't downloaded yet.", lang.displayName))
                val started = System.currentTimeMillis()
                _status.value = tr("Reading Anki…")
                val notes = whileAnkiStarts { anki.notes(pkg, search) }
                // None yet (a new kuma3: the note type comes with the first mined card): no word known yet, every word new.
                val cards = anki.cards(pkg, search)
                val read = System.currentTimeMillis()

                // The field each note is judged by (as morphs' filters on the PC).
                val fields = notes.mapNotNull { n -> n.fields[language.judgedField(n)]?.let { n.id to it } }.toMap()
                // Monolingual definitions too (Recalc unlocks them), keyed by -note id in the same parse cache.
                val defs = notes.mapNotNull { n -> n.fields[DEF_MONO]?.takeIf { it.isNotBlank() }?.let { -n.id to it } }.toMap()
                _status.value = tr("Parsing %d notes…", fields.size)
                val parsedAll = parse(fields + defs)
                val morphs = parsedAll.filterKeys { it > 0 }
                val defMorphs = parsedAll.filterKeys { it < 0 }.mapKeys { -it.key }
                val parsed = System.currentTimeMillis()

                val manualNotes = notes.filter { it.hasTag(KNOWN_MANUALLY) }.map { it.id }.toSet()
                val words = HashMap<String, Facts>()
                for (nid in morphs.keys) for (w in morphs[nid]!!) words[w] = words[w] ?: Facts(null, false, false)
                for (nid in manualNotes) morphs[nid]?.forEach { w -> words[w] = words[w]!!.copy(manual = true) }
                for (card in cards) {
                    if (!card.reviewed) continue
                    morphs[card.noteId]?.forEach { w ->
                        val f = words[w]!!
                        val best = listOfNotNull(f.best, card.stability).maxOrNull()
                        words[w] = f.copy(best = best, reviewed = true)
                    }
                }
                lastReading = Reading(pkg, notes, cards, morphs.mapValues { it.value.toList() }, defMorphs.mapValues { it.value.toList() })
                // Word cards by form, for the colours and ratings.
                cardIndex.build(notes, cards, anki.due(pkg), language::cardKeys).also { dueAt = System.currentTimeMillis() }
                val snap = Snapshot(words, System.currentTimeMillis(), pkg, notes.size, cards.size)
                save(snap)
                snapshot = snap
                _status.value = describe()
                Log.i("kumapie", "known words: ${notes.size} notes, ${cards.size} cards read in ${read - started} ms, " +
                    "parsed in ${parsed - read} ms, ${words.size} words")
                snap
            }.onFailure {
                Log.w("kumapie", "known words: $it")
                _status.value = it.message ?: it.toString()
            }
        }
    }

    /** Fields -> inflections per note, from the cache when the text and model are the same as last time. */
    private fun parse(fields: Map<Long, String>): Map<Long, Set<String>> {
        val cache = runCatching { JSONObject(parseCache.readText()) }.getOrDefault(JSONObject())
        val key = { nid: Long -> "$nid" }
        val hash = { text: String -> "${language.parserId}:${text.hashCode()}" }
        val out = HashMap<Long, Set<String>>()
        val todo = ArrayList<Long>()
        for ((nid, text) in fields) {
            val hit = cache.optJSONObject(key(nid))
            if (hit != null && hit.optString("h") == hash(text)) {
                out[nid] = hit.getJSONArray("m").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            } else {
                todo += nid
            }
        }
        if (todo.isNotEmpty()) language.wordKeys(todo.map { fields[it]!! }).forEachIndexed { i, words ->
            out[todo[i]] = words
            cache.put(key(todo[i]), JSONObject().put("h", hash(fields[todo[i]]!!)).put("m", JSONArray(words.toList())))
        }
        // Forget notes no longer there.
        cache.keys().asSequence().toList().filter { it.toLong() !in fields }.forEach { cache.remove(it) }
        parseCache.writeText(cache.toString())
        return out
    }

    /**
     * Runs a read, again while the AnkiDroid it woke is still starting: its provider answers before the app has
     * finished starting ("Collection accessed before AnkiDroidApp was initialized"). "Storage is not configured"
     * is not retried: that AnkiDroid was never set up, and opening it again would give it an empty collection.
     */
    private suspend fun <T> whileAnkiStarts(read: () -> T): T {
        repeat(5) {
            try {
                return read()
            } catch (e: IllegalStateException) {
                if (e.message?.contains("storage is not configured", true) == true) {
                    error(tr("That Anki isn't set up yet: open it once, then read Anki again."))
                }
                if (e.message?.contains("before AnkiDroidApp was initialized") != true) throw e
                Log.i("kumapie", "known words: Anki is still starting, trying again")
                kotlinx.coroutines.delay(1_500)
            }
        }
        return read()
    }


    private fun save(snap: Snapshot) {
        val words = JSONObject()
        snap.words.forEach { (w, f) ->
            words.put(w, JSONArray().put(f.best ?: JSONObject.NULL).put(f.reviewed).put(f.manual))
        }
        val o = JSONObject().put("time", snap.time).put("app", snap.app).put("notes", snap.notes).put("cards", snap.cards)
            .put("words", words)
        val tmp = File(dir, "words.json.part")
        tmp.writeText(o.toString())
        tmp.renameTo(savedFile)
    }

    private fun load(): Snapshot? = runCatching {
        val o = JSONObject(savedFile.readText())
        val w = o.getJSONObject("words")
        val words = w.keys().asSequence().associateWith { k ->
            w.getJSONArray(k).let { a -> Facts(if (a.isNull(0)) null else a.getDouble(0), a.getBoolean(1), a.getBoolean(2)) }
        }
        Snapshot(words, o.getLong("time"), o.getString("app"), o.getInt("notes"), o.getInt("cards"))
    }.getOrNull()

    private fun describe(): String {
        val snap = snapshot ?: return if (lang == Lang.GERMAN) tr("Colours come from the PC until Anki is read here.") else tr("Anki not read yet.")
        var known = 0
        var learning = 0
        for (w in snap.words.keys) when (status(w)) { "k" -> known++; "l" -> learning++ }
        val ago = (System.currentTimeMillis() - snap.time) / 60_000
        val whenText = when {
            ago < 1 -> tr("just now")
            ago < 120 -> tr("%d min ago", ago)
            else -> tr("%d h ago", ago / 60)
        }
        return tr("%1\$d known, %2\$d learning, %3\$d new (%4\$d notes, read %5\$s).", known, learning,
            snap.words.size - known - learning, snap.notes, whenText)
    }

    companion object {
        val CORE1000 = Lang.GERMAN.tag("core1000")
        /** Word cards mined in kumapie ([Miner]): judged by their Word field, like Core 1000. */
        val MINED_WORD = Lang.GERMAN.tag("word")
        const val NICOS_WEG = "Nicos_Weg_A1"
        /** The definition fields (the card's template marks the second as locked monolingual). */
        const val DEF_BI = "Definition (bilingual)"
        const val DEF_MONO = "Definition (monolingual)"
        /** MvJ's tags: every word of the monolingual definition is known (set by [Recalc]; -manually by hand), or not. */
        const val DEF_READY = "_mvj::def-is-ready"
        const val DEF_UNKNOWNS = "_mvj::def-has-unknowns"
        const val KNOWN_MANUALLY = "_card-status::i+0-manually"
    }
}

/** The note has [tag] or a tag under it ("tag::…"), any case. */
internal fun AnkiCards.Note.hasTag(tag: String) = tags.any { it.equals(tag, true) || it.startsWith("$tag::", true) }
