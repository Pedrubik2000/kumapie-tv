package io.github.pedrubik2000.kumapie.mobile.local

import android.content.Context
import android.net.Uri
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Episodes made on this device (YouTube, Real-Debrid, video files): each in `local/<id>/` of the app's external folder with its
 * `video.mp4`, `episode.json` (the same format the PC serves) and `thumb.jpg`; `local/index.json` lists them by show.
 * Their ids start with "local-", so the rest of the app can tell them from the PC's.
 */
class LocalEpisodes(context: Context) {
    val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "local").apply { mkdirs() }
    private val index = File(base, "index.json")

    fun dir(id: String) = File(base, id)
    fun video(id: String) = File(dir(id), "video.mp4")
    fun json(id: String) = File(dir(id), "episode.json")
    fun thumb(id: String) = File(dir(id), "thumb.jpg")

    data class Entry(val id: String, val show: String, val title: String, val duration: Double, val source: String,
                     val season: Int? = null, val number: Int? = null, val scenes: Int = -1,
                     /** [io.github.pedrubik2000.kumapie.data.Lang.code]; "de" for episodes added before languages. */
                     val lang: String = "de",
                     /** Home's category: "anime", "reels" (shorts), "shows", "movies", "youtube" ([category]). */
                     val kind: String = "")

    @Synchronized
    fun entries(): List<Entry> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { i -> fromJson(a.getJSONObject(i)) }
    }.getOrDefault(emptyList()).filter { json(it.id).exists() }.let { list ->
        // Episodes from before languages and categories: their language from episode.json, the category worked out, once.
        if (list.none { it.kind.isEmpty() }) list
        else list.map { e ->
            if (e.kind.isNotEmpty()) e else {
                val lang = runCatching { JSONObject(json(e.id).readText()).optString("lang", "de") }.getOrDefault(e.lang)
                e.copy(lang = lang, kind = category(e.source, lang, e.number))
            }
        }.also { save(it) }
    }.let { list ->
        // Scene counts (for Stats), counted once from episode.json and kept in the index.
        if (list.none { it.scenes < 0 }) list
        else list.map { e -> if (e.scenes >= 0) e else e.copy(scenes = runCatching { JSONObject(json(e.id).readText()).getJSONArray("scenes").length() }.getOrDefault(0)) }
            .also { save(it) }
    }

    @Synchronized
    fun add(e: Entry) {
        save(entries().filter { it.id != e.id } + e)
    }

    /**
     * Moves every subtitle of episode [id] by [seconds] (scenes, lines, English): the manual fix when the automatic sync
     * is off. Answers the total shift so far ("subtitles.txt" keeps it).
     */
    @Synchronized
    fun shift(id: String, seconds: Double): Double {
        val o = JSONObject(json(id).readText())
        fun moved(t: Double) = Math.round((t + seconds).coerceAtLeast(0.0) * 1000) / 1000.0
        val scenes = o.getJSONArray("scenes")
        for (i in 0 until scenes.length()) {
            val sc = scenes.getJSONObject(i)
            sc.put("start", moved(sc.getDouble("start"))).put("end", moved(sc.getDouble("end")))
            val cues = sc.getJSONArray("cues")
            for (k in 0 until cues.length()) cues.getJSONObject(k).let { c -> c.put("start", moved(c.getDouble("start"))).put("end", moved(c.getDouble("end"))) }
            val en = sc.optJSONArray("english") ?: continue
            for (k in 0 until en.length()) en.getJSONArray(k).let { e -> e.put(0, moved(e.getDouble(0))).put(1, moved(e.getDouble(1))) }
        }
        json(id).writeText(o.toString())
        val note = File(dir(id), "shift.txt")
        val total = (note.takeIf { it.exists() }?.readText()?.toDoubleOrNull() ?: 0.0) + seconds
        note.writeText("%.2f".format(java.util.Locale.ROOT, total))
        return total
    }

    fun shifted(id: String): Double = File(dir(id), "shift.txt").takeIf { it.exists() }?.readText()?.toDoubleOrNull() ?: 0.0

    @Synchronized
    fun remove(id: String) {
        dir(id).deleteRecursively()
        save(entries().filter { it.id != id })
    }

    private fun save(list: List<Entry>) = index.writeText(JSONArray(list.map(::toJson)).toString())

    /** The local episodes as shows for the home screen ("local-show-<name>"). */
    fun shows(): List<Show> = entries().groupBy { it.show }.map { (show, all) ->
        // Numbered episodes in order (a season arrives in any order); the rest as added.
        val eps = all.sortedWith(compareBy({ it.season ?: 0 }, { it.number ?: Int.MAX_VALUE }))
        val first = eps.first()
        Show(id = "local-show-" + show.hashCode().toUInt().toString(16), title = show, kind = first.kind.ifEmpty { "youtube" }, lang = first.lang,
            poster = Uri.fromFile(thumb(first.id)).toString(),
            episodes = eps.map { e ->
                Episode(id = e.id, title = e.title, season = e.season, number = e.number, duration = e.duration,
                    thumb = Uri.fromFile(thumb(e.id)).toString(), scenes = maxOf(e.scenes, 0), seen = 0, easy = 0, resume = null)
            })
    }

    companion object {
        fun isLocal(id: String) = id.startsWith("local-")

        /** An index entry as JSON (also what the Wi-Fi transfer sends with an episode). */
        fun toJson(e: Entry): JSONObject = JSONObject().put("id", e.id).put("show", e.show).put("title", e.title)
            .put("duration", e.duration).put("source", e.source)
            .put("season", e.season).put("number", e.number) // null leaves the key out
            .put("scenes", e.scenes).put("lang", e.lang).put("kind", e.kind)

        fun fromJson(o: JSONObject) = Entry(o.getString("id"), o.getString("show"), o.getString("title"), o.optDouble("duration"),
            o.optString("source"), o.optInt("season").takeIf { o.has("season") }, o.optInt("number").takeIf { o.has("number") },
            o.optInt("scenes", -1), o.optString("lang", "de"), o.optString("kind"))

        /** Home's category of an episode made here: YouTube shorts are reels, other YouTube videos youtube; a Japanese series
         * is anime, other numbered episodes shows, the rest movies. */
        fun category(source: String, lang: String, number: Int?): String = when {
            Regex("""youtube\.com/shorts/""").containsMatchIn(source) -> "reels"
            ProcessWorker.isYouTube(source) -> "youtube"
            lang == "ja" -> "anime"
            number != null -> "shows"
            else -> "movies"
        }
    }
}
