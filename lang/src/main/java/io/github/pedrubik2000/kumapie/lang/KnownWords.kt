package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import android.content.Context
import android.util.Log
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
 *   else **unknown** (red; no orange "learning", user 2026-10-11).
 * The result is saved, so the app colours words offline and at once on start; [refresh] reads Anki again.
 */
class KnownWords(val context: Context, private val settings: Settings, private val language: Language) {
    val lang: Lang get() = language.lang
    private val anki: Cards = Cards.get(context)
    private val srs get() = Cards.get(context).srs
    /** This language's parser is downloaded. */
    val modelReady: Boolean get() = language.model.isReady
    private val dir = File(context.filesDir, if (lang == Lang.GERMAN) "known" else "known-${lang.code}").apply { mkdirs() }
    private val savedFile = File(dir, "words.json")
    private val parseCache = File(dir, "parses.json")
    private val keysCache = File(dir, "cardkeys.json")
    /** What [readCards] read last, for [loadCards]. */
    private val cardsFile = File(dir, "cards.json")
    private val markedFile = File(dir, "marked.txt")
    /** Words imported as known (Jiten's lists for Japanese): known while the word has no card of its own. */
    private val importedFile = File(dir, "imported.txt")
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
    @Volatile private var imported: Set<String> = runCatching { importedFile.readLines().filter { it.isNotEmpty() }.toSet() }
        .getOrDefault(emptySet())

    private var listsRead = markedFile.lastModified() to importedFile.lastModified()

    /**
     * marked.txt and imported.txt again when another KnownWords wrote them since (the app's, for the one in
     * WordColours: a word marked known or a Jiten import there).
     */
    fun reloadLists() {
        val now = markedFile.lastModified() to importedFile.lastModified()
        if (now == listsRead) return
        listsRead = now
        marked = runCatching { markedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())
        imported = runCatching { importedFile.readLines().filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())
    }

    val importedWords: Set<String> get() = imported

    /** Replaces the imported known words (empty: none); [time] = when (the PC's, when they came from another device). */
    fun setImported(words: Set<String>, time: Long = System.currentTimeMillis()) {
        imported = words
        runCatching { importedFile.writeText(words.sorted().joinToString("\n")) }
        settings.prefs.edit().putLong("imported_time_${lang.code}", time).putLong("imported_sent_${lang.code}", -1).apply()
        _status.value = describe()
    }

    /** The Jiten import's last choice on this device (top words, list slugs), so Settings can change it; null: none. */
    var jitenChoice: Pair<Int, Set<String>>?
        get() = settings.prefs.getString("jiten_choice_${lang.code}", null)?.split('|')?.let { (n, s) -> n.toInt() to s.split(',').filter(String::isNotEmpty).toSet() }
        set(v) = settings.prefs.edit().putString("jiten_choice_${lang.code}", v?.let { "${it.first}|${it.second.joinToString(",")}" }).apply()

    /**
     * Jiten's [top] most frequent words and its word [sets] imported as known (the TV's and the phone's Settings and
     * onboarding), remembered as [jitenChoice]; the words marked known are kept apart, never touched. How many came.
     */
    suspend fun importJiten(top: Int, sets: Set<String>): Int {
        val words = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Jiten.words(top, sets) }
        setImported(words)
        jitenChoice = top to sets
        return words.size
    }

    /** No imported words any more (the marked ones stay). */
    fun removeImported() {
        setImported(emptySet())
        jitenChoice = null
    }

    /**
     * The imported words for POST /api/tv/known_lists, which keeps the newest import of all the person's devices:
     * the words go only while the PC may not have them (marks sync through Anki, [useMarked]).
     */
    fun listsBody(): JSONObject {
        val p = settings.prefs
        val time = importedTime()
        val imp = JSONObject().put("time", time)
        if (p.getLong("imported_sent_${lang.code}", -1) != time) imp.put("words", org.json.JSONArray(imported.sorted()))
        return JSONObject().put("imported", imp)
    }

    /** When the import was made: 1 for one from before times were kept (older than any other), 0 for none. */
    private fun importedTime() = settings.prefs.getLong("imported_time_${lang.code}", if (imported.isEmpty()) 0 else 1)

    /** The PC's answer to [listsBody]: takes another device's import when it's newer. */
    fun useLists(o: JSONObject) {
        val p = settings.prefs
        val imp = o.optJSONObject("imported") ?: return
        val time = imp.getLong("time")
        val words = imp.optJSONArray("words")
        if (words != null && time > importedTime())
            setImported((0 until words.length()).map(words::getString).toSet(), time)
        if (time == importedTime()) p.edit().putLong("imported_sent_${lang.code}", time).apply()
    }

    /** Known without a card of its own: imported, or [Language.autoKnown]. */
    private fun extraKnown(word: String) = word in imported || language.autoKnown(word)

    private val _status = MutableStateFlow(describe())
    /** One line for Settings: what the colours come from now, or what went wrong. */
    val status: StateFlow<String> = _status

    var threshold: Double
        get() = settings.prefs.getFloat("known_stability", 7f).toDouble()
        set(value) {
            settings.prefs.edit().putFloat("known_stability", value.toFloat()).apply()
            _status.value = describe()
        }

    /** This language's own cards: its Mined deck only ("deck:Deutsch Mined", subdecks included). */
    private val search get() = "\"deck:${lang.deck}\""

    /** The Anki note type with the German sentences and Core 1000 words: its new name first, then the old one. */
    val noteTypes = lang.noteTypes

    /**
     * Where this person's cards are: kumapie's own store, always (Pedro, 2026-11-19: nobody uses kuma3 any more; it
     * picked kuma3 when the store hadn't come from the PC). Null only when the device has no person yet.
     */
    fun ankiApp(): String? = SrsCards.PKG.takeIf { srs.store(lang) != null }

    /** The app the last read came from, or kumapie's store once it is this person's (a saved kuma3 read must not take ratings then). */
    private fun pkgNow(): String? = if (srs.active(lang)) SrsCards.PKG else lastReading?.pkg
    fun permission(pkg: String) = anki.permission(pkg)
    fun hasPermission(pkg: String) = anki.hasPermission(pkg)

    /** "k" known, null unknown. Without a card of its own: known when imported or [Language.autoKnown]. */
    fun status(inflection: String): String? {
        if (inflection in marked) return "k"
        val f = snapshot?.words?.get(inflection) ?: return if (extraKnown(inflection)) "k" else null
        return when {
            f.manual || (f.best != null && f.best >= threshold) -> "k"
            else -> null
        }
    }

    val ready: Boolean get() = snapshot != null

    /**
     * [status] by cards (kumapie_anki_review_plan.md step 5): "k" when marked known or its own card
     * is studied (due or not), null otherwise; without kuma3's queue, [status] (stability) as before.
     */
    fun statusByCard(word: String): String? = if (word in marked) "k" else when (cardIndex.state(word)) {
        null -> status(word)
        "k", "d" -> "k"
        "u" -> if (extraKnown(word)) "k" else null
        else -> null
    }

    /** The colour to draw [word] in, as the player does ([io.github.pedrubik2000.kumapie.data.Word.shown]): "k" white, "d" green, "b" blue, "u" red. */
    fun shown(word: String): String = if (word in marked) "k" else when (val c = cardIndex.state(word)) {
        null -> status(word) ?: "u"
        "u" -> if (extraKnown(word)) "k" else "u"
        else -> c
    }

    /**
     * What the PC gets (kumapie_plan.md item 6): every word this language shows white or green, sorted. Words known by
     * part of speech alone ([Language.autoKnown]) can't be listed and aren't in it.
     */
    fun knownList(): List<String> {
        val keys = HashSet<String>()
        snapshot?.words?.keys?.let(keys::addAll)
        keys += cardIndex.forms()
        keys += marked
        keys += imported
        return keys.filter { '\t' !in it && statusByCard(it) == "k" }.sorted()
    }

    /** This language's word cards by form and kuma3's queue today: [EpisodeDetail] words' [io.github.pedrubik2000.kumapie.data.Word.card]. */
    val cardIndex = CardIndex()
    @Volatile private var dueAt = 0L

    /** The word cards again without parsing (a card was just mined here); kuma3's queue as it was (a new card isn't due). */
    suspend fun reloadCards() = withContext(Dispatchers.IO) {
        val pkg = pkgNow() ?: return@withContext
        val notes = anki.notes(pkg, search)
        cardIndex.build(notes, anki.cards(pkg, search), cardIndex.due, cardKeys(notes))
    }

    /** The note just mined here into the index: on a TV [reloadCards] takes most of a minute, this one note is quick. */
    suspend fun addNote(id: Long) = withContext(Dispatchers.IO) {
        val pkg = pkgNow() ?: return@withContext
        cardIndex.add(anki.notes(pkg, "nid:$id"), anki.cards(pkg, "nid:$id"), language::cardKeys)
    }

    /**
     * Only the word cards and kuma3's queue, no sentences parsed (the TV has no parser; it colours by the PC's words):
     * [paint] works after it. False without kuma3 or its permission.
     */
    suspend fun readCards(): Boolean = withContext(Dispatchers.IO) {
        srs.sync(lang) // kumapie's own cards: the other devices' ratings (false, and kuma3 below, for the parents)
        val pkg = ankiApp()?.takeIf { hasPermission(it) } ?: return@withContext false
        val notes = whileAnkiStarts { anki.notes(pkg, search) }
        val cards = anki.cards(pkg, search)
        lastReading = Reading(pkg, notes, cards, emptyMap()) // [rate] and [undo] need its kuma3
        val keys = cardKeys(notes)
        cardIndex.build(notes, cards, anki.due(pkg), keys).also { dueAt = System.currentTimeMillis() }
        runCatching { saveCards(pkg, notes.associate { it.id to keys(it) }.filterValues { it.isNotEmpty() }, cards) }
            .onFailure { Log.w("kumapie", "cards not saved: $it") }
        true
    }

    private fun saveCards(pkg: String, keys: Map<Long, List<String>>, cards: List<AnkiCards.Card>) {
        val o = JSONObject().put("pkg", pkg)
        o.put("keys", JSONObject().apply { keys.forEach { (id, k) -> put("$id", JSONArray(k)) } })
        o.put("cards", JSONArray().apply {
            for (c in cards) if (c.noteId in keys) put(JSONArray().put(c.id).put(c.noteId).put(c.reviewed)
                .put(c.stability ?: JSONObject.NULL).put(c.due).put(c.reps).put(c.lapses).put(c.ord).put(c.type))
        })
        o.put("due", JSONObject().apply { cardIndex.due?.forEach { (id, q) -> put("$id", q) } })
        File(dir, "cards.json.tmp").apply { writeText(o.toString()) }.renameTo(cardsFile)
    }

    /**
     * The word cards as [readCards] last had them, without asking kuma3 (on a TV that read takes a minute; this, well
     * under a second): from memory when this run already read them, else from the file. [paint] works after it, with
     * the queue of that last read, so the colours can be a day old until [readCards] ends. False: nothing saved yet.
     */
    suspend fun loadCards(): Boolean = withContext(Dispatchers.IO) {
        if (cardIndex.due != null) return@withContext true
        runCatching {
            val o = JSONObject(cardsFile.readText())
            val k = o.getJSONObject("keys")
            val keys = k.keys().asSequence().associate { id -> id.toLong() to k.getJSONArray(id).let { a -> List(a.length()) { a.getString(it) } } }
            val cs = o.getJSONArray("cards")
            val cards = List(cs.length()) { i ->
                cs.getJSONArray(i).let { c ->
                    AnkiCards.Card(c.getLong(0), c.getLong(1), c.getBoolean(2), if (c.isNull(3)) null else c.getDouble(3), c.getLong(4),
                        c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(8))
                }
            }
            val d = o.getJSONObject("due")
            val notes = keys.keys.map { AnkiCards.Note(it, emptyMap(), emptySet()) }
            if (lastReading == null) lastReading = Reading(o.getString("pkg"), notes, cards, emptyMap())
            cardIndex.build(notes, cards, d.keys().asSequence().associate { it.toLong() to d.getString(it) }) { keys[it.id].orEmpty() }
            true
        }.getOrDefault(false)
    }

    /**
     * kuma3 syncs with AnkiWeb in the background (where kuma3 itself is never opened: the TV), after [readCards]. With
     * [waitMs] it waits that long for the sync to end: true = it synced, so the cards are worth reading again.
     */
    suspend fun syncAnki(waitMs: Long = 0): Boolean = withContext(Dispatchers.IO) {
        val pkg = pkgNow() ?: return@withContext false
        val before = anki.lastSync(pkg)
        val started = anki.sync(pkg)
        Log.i("kumapie", "kuma3 sync: ${if (started) "asked" else "not started (no AnkiWeb login there, or an older kuma3)"}, last synced $before")
        if (!started || before == null) return@withContext false
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until) {
            kotlinx.coroutines.delay(1500)
            if ((anki.lastSync(pkg) ?: before) > before) return@withContext true
        }
        false
    }

    /** kuma3's queue again (opening an episode), at most once a minute: every episode of a list opens at once. */
    suspend fun refreshDue(force: Boolean = false) = withContext(Dispatchers.IO) {
        val pkg = pkgNow() ?: return@withContext
        if (!force && System.currentTimeMillis() - dueAt < 60_000) return@withContext
        dueAt = System.currentTimeMillis()
        cardIndex.update(emptyList(), anki.due(pkg))
    }

    /** The Anki day of [ms]: days start at 4:00 local time (kuma3's default "next day starts at"). */
    private fun ankiDay(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).minusHours(4).toLocalDate()

    /** Forms rated since the last [exposed]: their cards weren't just heard. */
    private val ratedHere = HashSet<String>()

    /**
     * A scene played to its end (SceneController.onSceneSeen): each card of a word in it that wasn't rated there gets an
     * exposure in kumapie's own store (kumapie_plan.md "own Anki"; nothing for kuma3 cards). [keys]: the scene's word keys.
     */
    fun exposed(keys: Collection<String>, episode: String, scene: Int, subs: String?) {
        val rated = synchronized(ratedHere) { ratedHere.toSet().also { ratedHere.clear() } }
        if (!srs.active(lang)) return
        val store = srs.store(lang) ?: return
        val cards = (keys.toSet() - rated).flatMap { cardIndex.cards(it) }.map { it.id }.toSet()
        if (cards.isNotEmpty()) srs.background.launch {
            runCatching { cards.forEach { store.expose(it, episode, scene, subs) } }.onFailure { Log.w("kumapie", "exposures: $it") }
        }
    }

    /** A rating from a word card: the notes and how many cards it answered, and kuma3's Undo label right after. */
    class Rated(val notes: List<Long>, val cards: Int, val undoLabel: String)

    /**
     * Rates the form's own cards in kuma3 ([CardIndex.toAnswer]: every due one, else the first) with [ease] (1 Again ..
     * 4 Easy), [ms] since the word card opened (kuma3 caps it as its reviewer does); null when kuma3 took none.
     */
    suspend fun rate(form: String, ease: Int, ms: Long, watch: Srs.Watch? = null): Rated? = withContext(Dispatchers.IO) {
        val pkg = pkgNow() ?: return@withContext null
        synchronized(ratedHere) { ratedHere += form }
        // kuma3's first queue read on a new day (4:00) clears its Undo (rslib clear_queues_if_day_changed): read it before
        // the first rating of the day, not right after it, or that rating can't be undone.
        if (ankiDay(dueAt) != ankiDay(System.currentTimeMillis())) refreshDue(force = true)
        val answer = cardIndex.toAnswer(form)
        if (answer.isEmpty()) return@withContext null // no card of its own found (a Word field kumapie can't match)
        for (c in answer) anki.answer(pkg, c.noteId, c.ord, ease, ms, watch)
        val notes = answer.map { it.noteId }.distinct()
        val fetched = cardsOf(pkg, notes)
        // kuma3's provider only logs an answer it refused: count the cards whose reviews went up.
        val reps = fetched.associate { it.id to it.reps }
        val answered = answer.filter { (reps[it.id] ?: 0) > it.reps }.map { it.id }.toSet()
        // Not due now; kuma3's queue is read again when the player leaves the scene.
        cardIndex.update(fetched, cardIndex.due?.minus(answered))
        val label = anki.undoLabel(pkg)
        Log.i("kumapie", "rate: $form ease $ease, ${answered.size} of ${answer.size} cards answered, kuma3 Undo: '$label'")
        if (pkg == SrsCards.PKG && answered.isNotEmpty()) srs.syncSoon(lang) // to the PC and the other devices now
        if (answered.isEmpty()) null else Rated(notes, answered.size, label)
    }

    /**
     * Undoes [r] with kuma3's Undo, once per card it answered, only while Undo still names that rating (nothing else
     * done in kuma3 since); a card the rating made stays, new. False when it couldn't, or kuma3 took no review back
     * (its provider doesn't say: the cards' reviews must go down).
     */
    suspend fun undo(r: Rated): Boolean = withContext(Dispatchers.IO) {
        val pkg = pkgNow() ?: return@withContext false
        val label = anki.undoLabel(pkg)
        Log.i("kumapie", "undo: kuma3 says '$label', the rating said '${r.undoLabel}', ${r.cards} cards")
        if (r.undoLabel.isEmpty() || label != r.undoLabel) return@withContext false
        val before = cardsOf(pkg, r.notes).associate { it.id to it.reps }
        repeat(r.cards) { if (anki.undoLabel(pkg).isNotEmpty()) anki.undo(pkg) }
        val after = cardsOf(pkg, r.notes)
        cardIndex.update(after, cardIndex.due) // the queue comes with the player's next read
        val undone = after.count { it.reps < (before[it.id] ?: 0) }
        Log.i("kumapie", "undo: $undone of ${r.cards} reviews taken back, kuma3 now says '${anki.undoLabel(pkg)}'")
        if (pkg == SrsCards.PKG && undone > 0) srs.syncSoon(lang)
        undone > 0
    }

    private fun cardsOf(pkg: String, notes: List<Long>) = anki.cards(pkg, "nid:" + notes.joinToString(","))

    /**
     * The episode's word colours and scene levels from the device's own known words, replacing the PC's. Words the PC
     * marked known (`m`) stay known, as on the PC. Before Anki has been read here: [unread].
     */
    fun apply(split: EpisodeDetail): EpisodeDetail {
        val episode = PersonalDictionary.get(context).join(language.joinNames(split))
        val pcMarked = episode.words.filterValues { it.marked }.keys
        if (!marked.containsAll(pcMarked)) setMarked(marked + pcMarked)
        val snap = snapshot ?: return unread(episode, marked)
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
        // No card of its own but imported / a name: no card state, so [status] ("k") draws it white.
        w.copy(card = cardIndex.state(key)?.takeUnless { it == "u" && extraKnown(key) },
            forms = w.forms?.mapValues { (form, _) -> cardIndex.state(form)?.takeUnless { it == "u" && extraKnown(key) } ?: "" })

    /**
     * Every word of the player's episode coloured again by the card index as it is now (after a rating, an undo, a
     * mined card), the TV and the phone/tablet alike. A word without a card of its own ("u") takes its status:
     * [pcColours] the episode's, the PC's (a PC episode on the TV, which reads no Anki; the PC knows words without
     * cards, Jiten lists and marks: "u" drew せっかく, のに... red, 2026-10-22); otherwise this device's own [status]
     * (the phone, which coloured it with [apply]; an episode built on the device, all red from the builder, 2026-11-18).
     */
    fun repaint(ctl: io.github.pedrubik2000.kumapie.player.SceneController, pcColours: Boolean) {
        val written = if (language !is Japanese) emptyMap() else ctl.episode.scenes.asSequence().flatMap { it.cues }.flatMap { it.segments }
            .filter { it.word != null }.groupBy({ it.word!! }, { it.text })
        for (k in ctl.words.keys.toList()) ctl.words[k]?.let { w ->
            val p = paint(k, written[k]?.let { f -> w.copy(forms = f.associateWith { "" }) } ?: w)
            // A word marked known stays white ([Word.shown]: marked first): an episode built again (a later Jimaku fit)
            // came without the flag and the word's own card ("b") drew it blue again (Pedro, 2026-11-19).
            val n = recolour(p, pcColours, if (pcColours) null else status(k)).let { if (!it.marked && k in marked) it.copy(marked = true) else it }
            if (n != w) ctl.words[k] = n
        }
    }

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
                srs.sync(lang)
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
                // Monolingual definitions too (the PC's morphs recalc unlocks them), keyed by -note id in the same parse cache.
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
                cardIndex.build(notes, cards, anki.due(pkg), cardKeys(notes)).also { dueAt = System.currentTimeMillis() }
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
     * [Language.cardKeys] of each note, from the cache when its Word, Reading, Sentence and the parser are the same as last
     * time: Japanese keys load Jiten's lexicon (~3 s of CPU and ~95 MB on a Pixel 8a), now only for new or edited notes.
     */
    @Synchronized
    private fun cardKeys(notes: List<AnkiCards.Note>): (AnkiCards.Note) -> List<String> {
        val cache = runCatching { JSONObject(keysCache.readText()) }.getOrDefault(JSONObject())
        val out = HashMap<Long, List<String>>()
        for (n in notes) {
            val hash = "${language.parserId}:k3:${(n.fields["Word"] + "\u0001" + n.fields["Reading"] + "\u0001" + n.fields["Sentence"]).hashCode()}"
            val hit = cache.optJSONObject("${n.id}")
            out[n.id] = if (hit != null && hit.optString("h") == hash) hit.getJSONArray("k").let { a -> List(a.length()) { a.getString(it) } }
                else language.cardKeys(n).also { cache.put("${n.id}", JSONObject().put("h", hash).put("k", JSONArray(it))) }
        }
        cache.keys().asSequence().toList().filter { it.toLong() !in out }.forEach { cache.remove(it) }
        keysCache.writeText(cache.toString())
        return { out[it.id].orEmpty() }
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
        for (w in snap.words.keys) if (status(w) == "k") known++
        val ago = (System.currentTimeMillis() - snap.time) / 60_000
        val whenText = when {
            ago < 1 -> tr("just now")
            ago < 120 -> tr("%d min ago", ago)
            else -> tr("%d h ago", ago / 60)
        }
        return tr("%1\$d known, %2\$d new (%3\$d notes, read %4\$s).", known,
            snap.words.size - known, snap.notes, whenText) +
            if (imported.isEmpty()) "" else " " + tr("%d imported as known.", imported.size)
    }

    companion object {
        /**
         * An episode before Anki was read here (kumapie_plan.md item 7): no word is known but the marked ones, not the
         * PC's colours (the PC sends someone else's words as all known).
         */
        fun unread(episode: EpisodeDetail, marked: Set<String>): EpisodeDetail {
            val words = episode.words.mapValues { (key, w) ->
                w.copy(status = if (key in marked) "k" else "u", card = null, forms = null, marked = key in marked)
            }
            return episode.copy(words = words, scenes = episode.scenes.map { s ->
                if (!s.target) s else s.copy(level = io.github.pedrubik2000.kumapie.data.levelOf(s, words))
            })
        }

        val CORE1000 = Lang.GERMAN.tag("core1000")
        /** Word cards mined in kumapie ([Miner]): judged by their Word field, like Core 1000. */
        val MINED_WORD = Lang.GERMAN.tag("word")
        const val NICOS_WEG = "Nicos_Weg_A1"

        /**
         * [repaint]'s rule for one painted word [p]: [pcColours] drops the "u" card states (no card of its own) so the
         * episode's status shows ([io.github.pedrubik2000.kumapie.data.Word.shown] = card ?: status); otherwise the
         * device's own status [own] (null = unknown, keeps [p]'s), the "u" dropped when [own] says known.
         */
        fun recolour(p: io.github.pedrubik2000.kumapie.data.Word, pcColours: Boolean, own: String?): io.github.pedrubik2000.kumapie.data.Word {
            val q = if (pcColours || own == "k") p.copy(card = p.card?.takeUnless { it == "u" }, forms = p.forms?.mapValues { (_, s) -> if (s == "u") "" else s }) else p
            return if (pcColours) q else q.copy(status = own ?: q.status)
        }
        /** The definition fields (the card's template marks the second as locked monolingual). */
        const val DEF_BI = "Definition (bilingual)"
        const val DEF_MONO = "Definition (monolingual)"
        /** MvJ's tags: every word of the monolingual definition is known (set by morphs recalc on the PC; -manually by hand), or not. */
        const val DEF_READY = "_mvj::def-is-ready"
        const val DEF_UNKNOWNS = "_mvj::def-has-unknowns"
        const val KNOWN_MANUALLY = "_card-status::i+0-manually"
    }
}

/** The note has [tag] or a tag under it ("tag::…"), any case. */
internal fun AnkiCards.Note.hasTag(tag: String) = tags.any { it.equals(tag, true) || it.startsWith("$tag::", true) }
