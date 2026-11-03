package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.util.Log
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Scene
import io.github.pedrubik2000.kumapie.data.Segment
import io.github.pedrubik2000.kumapie.data.Word
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The words you added yourself by selecting them in a subtitle (user, 2026-11-03: 可可阿, which no dictionary has). An
 * entry is either the same as an existing word ([Entry.target]: 可可阿 = カカオ, sharing its card, colour and dictionaries)
 * or a word of its own, with a [Entry.reading] and [Entry.meaning]. The text is then one word in every episode when it
 * opens ([join]) and [YomitanDictionaries] finds it like a dictionary entry. A copy on each device (it works offline);
 * [sync] merges it with the PC's, so the phone, tablet and TV share them.
 */
class PersonalDictionary private constructor(context: Context) {
    data class Entry(val lang: String, val text: String, val target: String = "", val reading: String = "", val meaning: String = "",
                     val time: Long = 0, val deleted: Boolean = false)

    /** Where [entry] is in a subtitle line: characters [start] until [end]. */
    data class Span(val start: Int, val end: Int, val entry: Entry)

    private val file = File(context.filesDir, "personal-dictionary.json")
    @Volatile private var all: List<Entry> = load()

    private fun live(lang: String) = all.filter { it.lang == lang && !it.deleted }

    /** The entry written exactly as [text] (spaced languages: in any case), or null. */
    fun entry(lang: String, text: String): Entry? = live(lang).firstOrNull { it.text.equals(text, ignoreCase = spaced(lang)) }

    /** The entry under character [offset] of [text], or null. */
    fun at(lang: String, text: String, offset: Int): Entry? =
        spans(live(lang), text, spaced(lang)).firstOrNull { offset in it.start until it.end }?.entry

    /** The longest entry [text] starts with, or null. */
    fun startingAt(lang: String, text: String): Entry? =
        spans(live(lang), text, spaced(lang)).firstOrNull { it.start == 0 }?.entry

    @Synchronized fun save(e: Entry) = store(merge(all, listOf(e.copy(time = System.currentTimeMillis()))))

    @Synchronized fun delete(e: Entry) = save(e.copy(deleted = true))

    /** [episode] with this language's entries as one word each; a word new to the episode starts red. */
    fun join(episode: EpisodeDetail): EpisodeDetail {
        if (live(episode.lang).isEmpty()) return episode
        val scenes = joinScenes(episode.lang, episode.scenes)
        return episode.copy(scenes = scenes, words = episode.words + newKeys(scenes, episode.words.keys).associateWith { Word("u", "") })
    }

    /** [scenes] with this language's entries as one word each. */
    fun joinScenes(lang: String, scenes: List<Scene>): List<Scene> = live(lang).let { entries ->
        scenes.map { s -> s.copy(cues = s.cues.map { c -> c.copy(segments = join(entries, c.segments, spaced(lang))) }) }
    }

    /** Sends this device's list with [send] (it answers the merged one) and keeps the answer. Offline: tries again next time. */
    suspend fun sync(send: suspend (String) -> String) {
        runCatching {
            val answer = JSONObject(send(toJson(all).toString())).getJSONArray("entries")
            synchronized(this) { store(merge(all, fromJson(answer))) }
        }.onFailure { Log.w("kumapie", "personal dictionary not synced: $it") }
    }

    private fun store(list: List<Entry>) {
        all = list
        val tmp = File(file.path + ".new")
        tmp.writeText(toJson(list).toString())
        tmp.renameTo(file)
    }

    private fun load(): List<Entry> = runCatching { fromJson(JSONArray(file.readText())) }.getOrDefault(emptyList())

    companion object {
        /** The dictionary name an entry's meaning shows under. */
        const val TITLE = "Personal"

        @Volatile private var instance: PersonalDictionary? = null
        fun get(context: Context): PersonalDictionary = instance ?: synchronized(this) {
            instance ?: PersonalDictionary(context.applicationContext).also { instance = it }
        }

        /** Written with spaces: whole words only, in any case. */
        fun spaced(lang: String) = lang != Lang.JAPANESE.code

        /** The word keys in [scenes] that aren't in [known]. */
        fun newKeys(scenes: List<Scene>, known: Set<String>): Set<String> =
            scenes.flatMap { s -> s.cues.flatMap { c -> c.segments.mapNotNull { it.word } } }.toSet() - known

        /** The key an entry's text has in an episode: the word it is the same as, else itself; spaced keys are lowercase. */
        fun key(e: Entry): String = e.target.ifEmpty { e.text }.let { if (spaced(e.lang)) it.lowercase() else it }

        /**
         * Where [entries] are in [text], in order, a longer one first where they overlap. Japanese: anywhere.
         * ponytail: a kana entry also matches inside longer words (はい in はいる); require word edges if that ever shows up.
         */
        fun spans(entries: List<Entry>, text: String, spaced: Boolean): List<Span> {
            val out = ArrayList<Span>()
            for (e in entries.sortedByDescending { it.text.length }) {
                if (e.text.isEmpty()) continue
                var i = text.indexOf(e.text, ignoreCase = spaced)
                while (i >= 0) {
                    val end = i + e.text.length
                    val edges = !spaced || (text.getOrNull(i - 1)?.isLetterOrDigit() != true && text.getOrNull(end)?.isLetterOrDigit() != true)
                    if (edges && out.none { i < it.end && it.start < end }) out += Span(i, end, e)
                    i = text.indexOf(e.text, end, ignoreCase = spaced)
                }
            }
            return out.sortedBy { it.start }
        }

        /** A line's pieces with each of [entries] as one word; what an entry cuts off a piece keeps that piece's word. */
        fun join(entries: List<Entry>, segments: List<Segment>, spaced: Boolean): List<Segment> {
            val text = segments.joinToString("") { it.text }
            val found = spans(entries, text, spaced)
            if (found.isEmpty()) return segments
            val out = ArrayList<Segment>(segments.size)
            var at = 0
            for (s in segments) {
                val end = at + s.text.length
                var from = at
                // The parts of this piece outside every entry; an entry itself goes in at its start.
                for (span in found) {
                    if (span.end <= from || span.start >= end) continue
                    if (from < span.start) out += Segment(text.substring(from, span.start), s.word)
                    if (span.start >= at) out += Segment(text.substring(span.start, span.end), key(span.entry))
                    from = span.end
                }
                if (from < end) out += Segment(text.substring(from, end), s.word)
                at = end
            }
            return out
        }

        /** Both lists, the newest entry per language and text. */
        fun merge(old: List<Entry>, new: List<Entry>): List<Entry> {
            val best = LinkedHashMap<Pair<String, String>, Entry>()
            for (e in old + new) {
                val k = e.lang to e.text
                val had = best[k]
                if (had == null || e.time >= had.time) best[k] = e
            }
            return best.values.toList()
        }

        private fun toJson(list: List<Entry>) = JSONArray(list.map { e ->
            JSONObject().put("lang", e.lang).put("text", e.text).put("target", e.target).put("reading", e.reading)
                .put("meaning", e.meaning).put("time", e.time).put("deleted", e.deleted)
        })

        private fun fromJson(a: JSONArray) = (0 until a.length()).map { i ->
            a.getJSONObject(i).let { o ->
                Entry(o.getString("lang"), o.getString("text"), o.optString("target"), o.optString("reading"), o.optString("meaning"),
                    o.optLong("time"), o.optBoolean("deleted"))
            }
        }
    }
}
