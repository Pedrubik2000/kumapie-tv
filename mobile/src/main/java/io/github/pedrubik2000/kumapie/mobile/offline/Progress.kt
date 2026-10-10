package io.github.pedrubik2000.kumapie.mobile.offline

import android.content.Context
import io.github.pedrubik2000.kumapie.i18n.tr
import android.os.Build
import android.util.Log
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.AnkiCards
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * What Pedro did in kumapie (where each episode was left, scenes watched to the end, watch time per study day, words
 * looked up, words marked known), kept in his Anki collection so AnkiWeb carries it between the tablet and the phone.
 *
 * Anki syncs a collection's config as a whole (the newer side wins), so it lives in notes, which sync one by one: one
 * note per device (note type [NOTE_TYPE], its card suspended in the deck [DECK]), written only by that device, plus a
 * "pc" note with the PC's history (the TV). Every device reads all of them and merges: latest position, scenes seen
 * anywhere, time and lookups added up, the latest known/unknown mark. This device's own part is also kept in a file, so
 * a deleted note is simply written again.
 */
class Progress private constructor(context: Context, private val settings: Settings, private val anki: AnkiCards = AnkiCards(context)) {
    private val mine = File(context.filesDir, "progress.json")
    private val othersFile = File(context.filesDir, "progress-others.json")
    private val lock = Mutex()
    @Volatile private var data: JSONObject = runCatching { JSONObject(mine.readText()) }.getOrDefault(empty())
    @Volatile private var others: JSONObject = runCatching { JSONObject(othersFile.readText()) }.getOrDefault(JSONObject())
    @Volatile var merged: View = merge()
        private set

    /** This device's name in Anki: model + a random part, made once. */
    val device: String = settings.prefs.getString("progress_device", null) ?: "${Build.MODEL}-${UUID.randomUUID().toString().take(6)}"
        .also { settings.prefs.edit().putString("progress_device", it).apply() }

    class View(
        val pos: Map<String, Double>,
        val seen: Map<String, String>, // scene -> episode
        val watch: Map<String, Double>, // study day -> seconds
        val lookups: Map<String, Int>,
        val marked: Set<String>,
        val gloss: Map<String, String>,
    ) {
        fun seenIn(episode: String) = seen.count { it.value == episode }
    }

    // ------------------------------------------------------------------ recording (this device)

    fun record(episode: String, pos: Double?, seen: Collection<String>, watched: Double) = change {
        val now = now()
        if (pos != null) it.getJSONObject("pos").put(episode, JSONArray().put(pos).put(now))
        val s = it.getJSONObject("seen")
        for (scene in seen) {
            val old = s.optJSONArray(scene)
            s.put(scene, JSONArray().put(episode).put(old?.optLong(1) ?: now).put(now).put((old?.optInt(3) ?: 0) + 1))
        }
        if (watched > 0) it.getJSONObject("watch").let { w -> w.put(studyDay(), w.optDouble(studyDay(), 0.0) + minOf(watched, 600.0)) }
    }

    fun lookup(word: String): Int {
        change { it.getJSONObject("lookups").let { l -> l.put(word, l.optInt(word) + 1) } }
        return merged.lookups[word] ?: 1
    }

    fun mark(word: String, known: Boolean) = change { it.getJSONObject("marked").put(word, JSONArray().put(known).put(now())) }

    private fun change(edit: (JSONObject) -> Unit) = synchronized(this) {
        val d = JSONObject(data.toString())
        edit(d)
        data = d
        mine.writeText(d.toString())
        merged = merge()
    }

    // ------------------------------------------------------------------ Anki and the PC

    /**
     * Writes this device's note (when it changed), reads the other devices' notes, and refreshes the "pc" note from
     * the PC when it answers. Quietly does nothing when Anki can't be reached.
     */
    suspend fun sync(api: io.github.pedrubik2000.kumapie.data.Api?) = lock.withLock {
        runCatching {
            // kuma3 Anki only: the Play Store AnkiDroid can't suspend the card, which would then come up for review.
            val pkg = anki.installed().firstOrNull()?.takeIf { it != "com.ichi2.anki" && anki.hasPermission(it) } ?: return@withLock
            val notes = anki.notes(pkg, "\"note:$NOTE_TYPE\"")
            val byDevice = notes.associateBy { it.fields["Device"] ?: "" }
            val pc = api?.let { runCatching { it.history() }.getOrNull() }
            val pcText = pc?.let { JSONObject(it).toString() }
            if (pcText != null && byDevice["pc"]?.fields?.get("Data") != pcText) write(pkg, "pc", pcText, byDevice["pc"])
            val text = data.toString()
            if (byDevice[device]?.fields?.get("Data") != text) write(pkg, device, text, byDevice[device])
            val o = JSONObject()
            for ((dev, n) in byDevice) if (dev != device && dev.isNotEmpty()) runCatching { o.put(dev, JSONObject(n.fields["Data"] ?: "{}")) }
            if (pcText != null) o.put("pc", JSONObject(pcText))
            others = o
            othersFile.writeText(o.toString())
            merged = merge()
        }.onFailure { Log.w("kumapie", "progress sync: $it") }
    }

    private fun write(pkg: String, dev: String, text: String, existing: AnkiCards.Note?) {
        if (existing != null) return anki.updateNote(pkg, existing.id, existing.tags, listOf(dev, text))
        val mid = anki.noteType(pkg, listOf(NOTE_TYPE))?.first ?: anki.addNoteType(pkg, NOTE_TYPE, listOf("Device", "Data"))
        val nid = anki.addNote(pkg, mid, listOf(dev, text), listOf("kumapie"), anki.deck(pkg, DECK))
        try {
            anki.suspendCards(pkg, nid)
        } catch (e: Exception) { // an older kuma3 Anki: take the note out again rather than leave a card to review
            anki.deleteNote(pkg, nid)
            throw IllegalStateException(tr("kuma3 Anki is too old to keep kumapie's progress (update it)"), e)
        }
    }

    // ------------------------------------------------------------------ merging

    private fun merge(): View {
        val parts = listOf(data) + others.keys().asSequence().mapNotNull { others.optJSONObject(it) }.toList()
        val pos = HashMap<String, Pair<Double, Long>>()
        val seen = HashMap<String, String>()
        val watch = HashMap<String, Double>()
        val lookups = HashMap<String, Int>()
        val marked = HashMap<String, Pair<Boolean, Long>>()
        val gloss = HashMap<String, String>()
        for (p in parts) {
            p.optJSONObject("pos")?.let { o ->
                for (k in o.keys()) {
                    val a = o.getJSONArray(k)
                    if ((pos[k]?.second ?: -1) <= a.optLong(1)) pos[k] = a.getDouble(0) to a.optLong(1)
                }
            }
            p.optJSONObject("seen")?.let { o -> for (k in o.keys()) seen[k] = o.getJSONArray(k).getString(0) }
            p.optJSONObject("watch")?.let { o -> for (k in o.keys()) watch[k] = (watch[k] ?: 0.0) + o.getDouble(k) }
            p.optJSONObject("lookups")?.let { o -> for (k in o.keys()) lookups[k] = (lookups[k] ?: 0) + o.getInt(k) }
            p.optJSONObject("marked")?.let { o ->
                for (k in o.keys()) {
                    val a = o.getJSONArray(k)
                    if ((marked[k]?.second ?: -1) <= a.optLong(1)) marked[k] = a.getBoolean(0) to a.optLong(1)
                }
            }
            p.optJSONObject("gloss")?.let { o -> for (k in o.keys()) gloss[k] = o.getString(k) }
        }
        return View(pos.mapValues { it.value.first }, seen, watch, lookups, marked.filterValues { it.first }.keys, gloss)
    }

    companion object {
        @Volatile private var one: Progress? = null

        /** One per app process (the player, and the condensed service each make a Library). */
        fun of(context: Context): Progress = one ?: synchronized(this) {
            one ?: Progress(context.applicationContext, Settings(context.applicationContext)).also { one = it }
        }

        const val NOTE_TYPE = "🐻 kumapie progress"
        const val DECK = "kumapie"

        private fun empty() = JSONObject().put("pos", JSONObject()).put("seen", JSONObject()).put("watch", JSONObject())
            .put("lookups", JSONObject()).put("marked", JSONObject())

        private fun now() = System.currentTimeMillis() / 1000

        /** The study day (it starts at 4 am, like the PC's and Anki's). */
        fun studyDay(): String = LocalDateTime.now().minusHours(4).toLocalDate().toString()
    }
}
