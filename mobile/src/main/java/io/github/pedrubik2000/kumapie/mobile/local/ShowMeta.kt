package io.github.pedrubik2000.kumapie.mobile.local

import android.content.Context
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * A device-made show's metadata (kumapie_plan.md items 20-21): `local/shows/<key>/meta.json` + `poster.jpg`, found with
 * python/meta.py (AniList/Jikan/Kitsu, TVmaze, Wikidata; the same file the PC uses). Kept beside the show, keyed like its
 * id ("local-show-<key>"), so the episodes keep their show name (PC folders and grouping don't change); the apps show
 * the matched title in the person's language instead.
 */
class ShowMeta(private val context: Context) {
    private val local = LocalEpisodes(context)

    private fun py(): Python {
        if (!Python.isStarted()) Python.start(com.chaquo.python.android.AndroidPlatform(context.applicationContext))
        return Python.getInstance()
    }

    private fun dir(showId: String) = local.showDir(showId)

    fun of(showId: String): JSONObject? = local.meta(showId)

    /** Candidates, best first: [{"source", "id", "title", "year", "poster"}]. Needs internet. */
    fun search(kind: String, query: String): List<JSONObject> {
        val py = py()
        val found = py.getModule("json").callAttr("dumps", py.getModule("meta").callAttr("search", kind, query)).toString()
        return JSONArray(found).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    }

    /** Saves the pick's details (in [langs]) and poster for the show, and sets its episodes' Home category to [kind]. */
    fun apply(showId: String, kind: String, source: String, id: String, langs: List<String>) {
        save(dir(showId), source, id, langs)
        local.setKind(showId, kind)
    }

    /** The pick's details as `meta.json` + `poster.jpg` in [dir]. */
    fun save(dir: File, source: String, id: String, langs: List<String>) {
        val py = py()
        val d = JSONObject(py.getModule("json").callAttr("dumps",
            py.getModule("meta").callAttr("details", source, id, langs.distinct().joinToString(","))).toString())
        dir.mkdirs()
        d.optString("poster").takeIf { it.startsWith("http") }?.let { url ->
            runCatching { URL(url).openStream().use { s -> File(dir, "poster.part").outputStream().use { s.copyTo(it) } } }
                .onSuccess { File(dir, "poster.part").renameTo(File(dir, "poster.jpg")) }
        }
        File(dir, "meta.json").writeText(d.toString())
    }

    /** YouTube shows made before 2026-10-11 have no picture yet: their channel's, found from one of their videos. */
    fun fillYouTube() {
        for ((show, all) in local.entries().groupBy { it.show }) {
            val e = all.first()
            if ((e.kind == "youtube" || e.kind == "reels") && e.source.startsWith("http") && local.meta(local.showId(show)) == null)
                runCatching { apply(local.showId(show), e.kind, "youtube", e.source, listOf("en")) }
        }
    }

    companion object {
        /** The title in [lang] (the person's), else English, else the original. */
        fun title(meta: JSONObject, lang: String): String? = meta.optJSONObject("title")?.let { t ->
            t.optString(lang).ifBlank { t.optString("en") }.ifBlank { null }
        } ?: meta.optString("original").ifBlank { null }

        fun description(meta: JSONObject, lang: String): String? = meta.optJSONObject("description")?.let { d ->
            d.optString(lang).ifBlank { d.optString("en") }.ifBlank { null }
        }

        /** An episode's title, only once it is watched (titles can spoil, user 2026-10-11). */
        fun episodeTitle(meta: JSONObject?, season: Int?, number: Int?): String? =
            number?.let { meta?.optJSONObject("episodes")?.optString("${season ?: 1}-$it")?.ifBlank { null } }
    }
}
