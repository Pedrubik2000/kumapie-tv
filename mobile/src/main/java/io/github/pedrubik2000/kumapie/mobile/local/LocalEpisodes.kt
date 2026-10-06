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
                     val lang: String = "de")

    @Synchronized
    fun entries(): List<Entry> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                Entry(it.getString("id"), it.getString("show"), it.getString("title"), it.optDouble("duration"), it.optString("source"),
                    it.optInt("season").takeIf { _ -> it.has("season") }, it.optInt("number").takeIf { _ -> it.has("number") },
                    it.optInt("scenes", -1), it.optString("lang", "de"))
            }
        }
    }.getOrDefault(emptyList()).filter { json(it.id).exists() }.let { list ->
        // Scene counts (for Stats), counted once from episode.json and kept in the index.
        if (list.none { it.scenes < 0 }) list
        else list.map { e -> if (e.scenes >= 0) e else e.copy(scenes = runCatching { JSONObject(json(e.id).readText()).getJSONArray("scenes").length() }.getOrDefault(0)) }
            .also { save(it) }
    }

    @Synchronized
    fun add(e: Entry) {
        save(entries().filter { it.id != e.id } + e)
    }

    @Synchronized
    fun remove(id: String) {
        dir(id).deleteRecursively()
        save(entries().filter { it.id != id })
    }

    private fun save(list: List<Entry>) = index.writeText(JSONArray(list.map {
        JSONObject().put("id", it.id).put("show", it.show).put("title", it.title).put("duration", it.duration).put("source", it.source)
            .put("season", it.season).put("number", it.number) // null leaves the key out
            .put("scenes", it.scenes).put("lang", it.lang)
    }).toString())

    /** The local episodes as shows for the home screen ("local-show-<name>"). */
    fun shows(): List<Show> = entries().groupBy { it.show }.map { (show, all) ->
        // Numbered episodes in order (a season arrives in any order); the rest as added.
        val eps = all.sortedWith(compareBy({ it.season ?: 0 }, { it.number ?: Int.MAX_VALUE }))
        val first = eps.first()
        Show(id = "local-show-" + show.hashCode().toUInt().toString(16), title = show, kind = "youtube",
            poster = Uri.fromFile(thumb(first.id)).toString(),
            episodes = eps.map { e ->
                Episode(id = e.id, title = e.title, season = e.season, number = e.number, duration = e.duration,
                    thumb = Uri.fromFile(thumb(e.id)).toString(), scenes = maxOf(e.scenes, 0), seen = 0, easy = 0, resume = null)
            })
    }

    companion object {
        fun isLocal(id: String) = id.startsWith("local-")
    }
}
