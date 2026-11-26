package io.github.pedrubik2000.kumapie.lang

import android.util.Log
import io.github.pedrubik2000.kumapie.data.AnkiSource
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AnkiNotSetUp : IllegalStateException(tr("That Anki isn't set up yet: open it once, then read Anki again."))

class AnkiKnown(dir: File, private val settings: Settings, private val language: Language, private val anki: Cards, private val changed: () -> Unit = {}) {
    data class Read(val count: Int, val error: String? = null)

    private fun shown(w: Set<String>) = Noise.countable(w, language.lang).size

    private val file = File(dir, "anki-known.txt")
    private val keysFile = File(dir, "anki-cardkeys.json")
    private val mutex = Mutex()

    @Volatile var words: Set<String> = runCatching { file.readLines().filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())
        private set

    private val _state = MutableStateFlow(Read(shown(words)))
    val state: StateFlow<Read> = _state

    private var stamp = file.lastModified()

    private fun replace(new: Set<String>) {
        val same = new == words
        words = new
        _state.value = Read(shown(new))
        if (same) return
        val saved = runCatching {
            if (new.isEmpty()) {
                file.delete() || !file.exists()
            } else {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(new.sorted().joinToString("\n"))
                tmp.renameTo(file)
            }
        }.getOrDefault(false)
        if (!saved) Log.w("kumapie", "anki words not saved to $file")
        stamp = file.lastModified()
        changed()
    }

    fun reload() {
        if (file.lastModified() == stamp) return
        stamp = file.lastModified()
        val fresh = runCatching { file.readLines().filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())
        if (fresh == words) return
        words = fresh
        _state.value = Read(shown(fresh))
        changed()
    }

    private fun chosen(): String? = AnkiSource.appFor(settings.ankiSource, anki.installed())

    suspend fun read(force: Boolean = false): Boolean = withContext(Dispatchers.IO) { mutex.withLock { readLocked(force) } }

    private suspend fun readLocked(force: Boolean): Boolean {
        val pkg = chosen()
        if (pkg == null) {
            replace(emptySet())
            return false
        }
        if (!force && settings.plainWords) return false
        return try {
            if (!anki.hasPermission(pkg)) error(tr("Reading Anki is off: allow it to use this source."))
            val search = AnkiSource.reviewedSearch(settings.ankiDecks(language.lang))
            val notes = whileAnkiStarts { anki.notes(pkg, search) }
                .map { remap(it, settings.ankiWordField(language.lang), settings.ankiReadingField(language.lang)) }
            val keys = cachedCardKeys(language, keysFile, notes)
            if (chosen() != pkg) return false
            replace(notes.flatMap(keys).toSet())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("kumapie", "anki source not read: $e")
            _state.value = Read(shown(words), e.message ?: e.toString())
            false
        }
    }

    companion object {
        fun remap(note: AnkiCards.Note, wordField: String, readingField: String): AnkiCards.Note {
            if (wordField == "Word" && readingField == "Reading") return note
            val mapped = listOfNotNull(note.fields[wordField]?.let { "Word" to it }, note.fields[readingField]?.let { "Reading" to it })
            return note.copy(fields = note.fields - "Word" - "Reading" + mapped)
        }
    }
}

/**
 * [Language.cardKeys] of each note, from the cache file when its Word, Reading, Sentence and the parser are the same as
 * last time: Japanese keys load Jiten's lexicon (~3 s of CPU and ~95 MB on a Pixel 8a), now only for new or edited notes.
 */
internal fun cachedCardKeys(language: Language, cacheFile: File, notes: List<AnkiCards.Note>): (AnkiCards.Note) -> List<String> = synchronized(cacheFile.path.intern()) {
    val cache = runCatching { JSONObject(cacheFile.readText()) }.getOrDefault(JSONObject())
    val out = HashMap<Long, List<String>>()
    for (n in notes) {
        val hash = "${language.parserId}:k3:${(n.fields["Word"] + "\u0001" + n.fields["Reading"] + "\u0001" + n.fields["Sentence"]).hashCode()}"
        val hit = cache.optJSONObject("${n.id}")
        out[n.id] = if (hit != null && hit.optString("h") == hash) hit.getJSONArray("k").let { a -> List(a.length()) { a.getString(it) } }
            else language.cardKeys(n).also { cache.put("${n.id}", JSONObject().put("h", hash).put("k", JSONArray(it))) }
    }
    cache.keys().asSequence().toList().filter { it.toLong() !in out }.forEach { cache.remove(it) }
    cacheFile.writeText(cache.toString())
    return@synchronized { out[it.id].orEmpty() }
}

/**
 * Runs a read, again while the AnkiDroid it woke is still starting: its provider answers before the app has
 * finished starting ("Collection accessed before AnkiDroidApp was initialized"). "Storage is not configured"
 * is not retried: that AnkiDroid was never set up, and opening it again would give it an empty collection.
 */
internal suspend fun <T> whileAnkiStarts(read: () -> T): T {
    repeat(5) {
        try {
            return read()
        } catch (e: IllegalStateException) {
            if (e.message?.contains("storage is not configured", true) == true) {
                throw AnkiNotSetUp()
            }
            if (e.message?.contains("before AnkiDroidApp was initialized") != true) throw e
            Log.i("kumapie", "known words: Anki is still starting, trying again")
            kotlinx.coroutines.delay(1_500)
        }
    }
    return read()
}
