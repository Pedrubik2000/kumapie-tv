package io.github.pedrubik2000.kumapie.mobile.german

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
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
 * Which German words Pedro knows, worked out on the device from his Anki (kuma3 Anki) the way morphs does on
 * the PC, so the subtitles' colours no longer need the PC:
 * - the German notes' fields are parsed by spaCy (same model as the PC) into morphs, keyed by inflection;
 * - a word is **known** (white) when one of its reviewed cards has FSRS stability >= the threshold (Settings,
 *   7 days by default), its note is tagged known-manually, or it was marked known in the app;
 *   **learning** (orange) when it is only on reviewed cards below that; else **unknown** (red).
 * The result is saved, so the app colours words offline and at once on start; [refresh] reads Anki again.
 */
class KnownWords(val context: Context, private val settings: Settings) {
    private val anki = AnkiCards(context)
    val model = GermanModel(context)
    private val dir = File(context.filesDir, "known").apply { mkdirs() }
    private val savedFile = File(dir, "words.json")
    private val parseCache = File(dir, "parses.json")
    private val markedFile = File(dir, "marked.txt")
    private val lock = Mutex()

    /** What one inflection's cards say. */
    data class Facts(val best: Double?, val reviewed: Boolean, val manual: Boolean)

    data class Snapshot(val words: Map<String, Facts>, val time: Long, val app: String, val notes: Int, val cards: Int)

    @Volatile private var snapshot: Snapshot? = load()

    /** What the last [refresh] read from Anki: notes, cards, and each judged note's inflections in text order. */
    class Reading(val pkg: String, val notes: List<AnkiCards.Note>, val cards: List<AnkiCards.Card>, val morphs: Map<Long, List<String>>)
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

    /** The Anki note type with the German sentences and Core 1000 words (renamed "kuma3 German" later). */
    var noteType: String
        get() = settings.prefs.getString("german_note_type", "🇩🇪 MvJ") ?: "🇩🇪 MvJ"
        set(value) = settings.prefs.edit().putString("german_note_type", value.trim()).apply()

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
     * The episode's word colours and scene levels from the device's own known words, replacing the PC's, once Anki
     * has been read here. Words the PC marked known (`m`) stay known, as on the PC.
     */
    fun apply(episode: EpisodeDetail): EpisodeDetail {
        val snap = snapshot ?: return episode
        val pcMarked = episode.words.filterValues { it.marked }.keys
        if (!marked.containsAll(pcMarked)) setMarked(marked + pcMarked)
        val words = episode.words.mapValues { (key, w) ->
            w.copy(status = status(key) ?: "u", stability = snap.words[key]?.best, marked = key in marked)
        }
        val scenes = episode.scenes.map { s ->
            if (!s.german) s else s.copy(level = s.cues.flatMap { c -> c.segments.mapNotNull { it.word } }.toSet()
                .count { words[it]?.status == "u" })
        }
        return episode.copy(words = words, scenes = scenes)
    }

    /** A word marked known in the app (or unmarked); answers its status now. */
    fun mark(word: String, known: Boolean): String {
        setMarked(if (known) marked + word else marked - word)
        return status(word) ?: "u"
    }

    private fun setMarked(words: Set<String>) {
        marked = words
        runCatching { markedFile.writeText(words.sorted().joinToString("\n")) }
    }

    /** Reads Anki again and works out every word's facts (parsing only new or changed fields). */
    suspend fun refresh(): Result<Snapshot> = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val pkg = ankiApp() ?: error("No AnkiDroid found on this device.")
                if (!hasPermission(pkg)) error("kumapie may not read Anki yet: allow it in Settings.")
                if (!model.isReady) error("The German model isn't downloaded yet.")
                val started = System.currentTimeMillis()
                _status.value = "Reading Anki…"
                val search = "note:\"${noteType.replace("\"", "\\\"")}\""
                val notes = whileAnkiStarts { anki.notes(pkg, search) }
                if (notes.isEmpty()) error("No notes of type \"$noteType\" in Anki.")
                val cards = anki.cards(pkg, search)
                val read = System.currentTimeMillis()

                // The field each note is judged by (as morphs' filters on the PC).
                val fields = notes.mapNotNull { n ->
                    val name = when {
                        n.hasTag(CORE1000) || n.hasTag(MINED_WORD) -> "Word"
                        n.hasTag(NICOS_WEG) -> null
                        else -> "Sentence"
                    }
                    n.fields[name]?.let { n.id to it }
                }.toMap()
                _status.value = "Parsing ${fields.size} notes…"
                val morphs = parse(fields)
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
                lastReading = Reading(pkg, notes, cards, morphs.mapValues { it.value.toList() })
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
        val hash = { text: String -> "${GermanModel.NAME}-${GermanModel.VERSION}:${text.hashCode()}" }
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
        if (todo.isNotEmpty()) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context))
            val result = Python.getInstance().getModule("german")
                .callAttr("parse_fields", model.dir.path, JSONArray(todo.map { fields[it] }).toString()).toString()
            val parsed = JSONArray(result)
            todo.forEachIndexed { i, nid ->
                val morphs = parsed.getJSONArray(i)
                val inflections = (0 until morphs.length()).map { morphs.getJSONArray(it).getString(1) }.toSet()
                out[nid] = inflections
                cache.put(key(nid), JSONObject().put("h", hash(fields[nid]!!)).put("m", JSONArray(inflections.toList())))
            }
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
                    error("That Anki isn't set up yet: open it once, then read Anki again.")
                }
                if (e.message?.contains("before AnkiDroidApp was initialized") != true) throw e
                Log.i("kumapie", "known words: Anki is still starting, trying again")
                kotlinx.coroutines.delay(1_500)
            }
        }
        return read()
    }

    internal fun AnkiCards.Note.hasTag(tag: String) = tags.any { it.equals(tag, true) || it.startsWith("$tag::", true) }

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
        val snap = snapshot ?: return "Colours come from the PC until Anki is read here."
        var known = 0
        var learning = 0
        for (w in snap.words.keys) when (status(w)) { "k" -> known++; "l" -> learning++ }
        val ago = (System.currentTimeMillis() - snap.time) / 60_000
        val whenText = when {
            ago < 1 -> "just now"
            ago < 120 -> "$ago min ago"
            else -> "${ago / 60} h ago"
        }
        return "$known known, $learning learning, ${snap.words.size - known - learning} new " +
            "(${snap.notes} notes, read $whenText)."
    }

    companion object {
        const val CORE1000 = "_de::core1000"
        /** Word cards mined in kumapie ([Miner]): judged by their Word field, like Core 1000. */
        const val MINED_WORD = "_de::word"
        const val NICOS_WEG = "Nicos_Weg_A1"
        const val KNOWN_MANUALLY = "_card-status::i+0-manually"
    }
}
