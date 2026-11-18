package io.github.pedrubik2000.kumapie.data

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * What the Discover screen asks for. The PC answers it ([Api], kumapie-server discover.py); without a PC the
 * user's own Stremio addons do ([Addons]). The JSON shapes are discover.py's.
 */
interface Discovery {
    /** Rows of titles: [(name, titles)]. */
    suspend fun board(): List<Pair<String, List<Found>>>
    suspend fun discover(q: String): List<Found>

    /** A title: {"name", "poster", "background", "description", "year", "episodes": [{"id", "season", "number", "title", "thumb", "ready", "watched"}]}. */
    suspend fun discoverMeta(type: String, id: String): JSONObject

    /** A title's or episode's files, best first: [{"label", "about", "short"}] + the PC's {"hash", "file", "idx"} or an addon's {"url"} (plays as it is). */
    suspend fun discoverStreams(type: String, id: String): JSONArray
    suspend fun saved(): List<Found>

    /** Keeps [title] on Home ([on]) or drops it; answers the new list. */
    suspend fun save(title: Found, on: Boolean): List<Found>

    /** Marks an episode watched ([on]) or not. */
    suspend fun seen(id: String, on: Boolean)

    /** A title object (also a meta answer). */
    fun found(o: JSONObject) = Found(o.optString("id"), o.optString("type", "series"), o.optString("name"), o.optString("poster"), o.optString("year"),
        o.optString("background"), o.optString("logo"), o.optString("description"), o.optString("genres"), o.optString("rating"), o.optString("runtime"))
}

/**
 * kumapie without a PC (kumapie_plan.md "for other people"): the user's own Stremio addons, asked from the device.
 * [urls]: their addresses as pasted (…/manifest.json, stremio://…). An addon gives what its manifest says: catalogs
 * (rows and search), meta (a title's episodes), streams. kumapie ships with none and plays only streams that come
 * as a link ("url"): a torrent addon needs the user's debrid key in its address. Kept titles and watched marks
 * live in [store].
 */
class Addons(urls: List<String>, private val store: SharedPreferences) : Discovery {
    private val bases = urls.map(::base).filter { it.isNotEmpty() }.distinct()
    private var manifests: List<Pair<String, JSONObject>>? = null

    private suspend fun manifests(): List<Pair<String, JSONObject>> = manifests ?: bases.mapNotNull { b ->
        runCatching { b to JSONObject(get("$b/manifest.json")) }.getOrNull()
    }.also { if (it.isNotEmpty() || bases.isEmpty()) manifests = it }

    /** The addons' names, for Settings ("" for one that doesn't answer). */
    suspend fun names(): List<String> = bases.map { b -> manifests().firstOrNull { it.first == b }?.second?.optString("name").orEmpty() }

    override suspend fun board(): List<Pair<String, List<Found>>> {
        val rows = ArrayList<Pair<String, List<Found>>>()
        // The user's AniList lists first (Watching, Planning), when they gave their username in the onboarding.
        store.getString(ANILIST, "").orEmpty().takeIf { it.isNotBlank() }?.let { user ->
            rows += runCatching { AniList.rows(user, store) }.onFailure { android.util.Log.w("kumapie", "AniList rows: $it") }.getOrDefault(emptyList())
        }
        for ((b, m) in manifests()) for (c in m.optJSONArray("catalogs").objects()) {
            if (rows.size >= MAX_ROWS || needs(c).isNotEmpty()) continue
            val titles = runCatching { metas(get("$b/catalog/${c.getString("type")}/${c.getString("id")}.json")) }.getOrDefault(emptyList())
            if (titles.isNotEmpty()) rows += (c.optString("name").ifEmpty { c.getString("id") } + " · " + c.getString("type")) to titles
        }
        if (rows.isEmpty()) error(if (bases.isEmpty()) NO_ADDON else "None of your addons has a catalog: add one that lists titles (Cinemeta, Kitsu…)")
        return rows
    }

    override suspend fun discover(q: String): List<Found> {
        val out = LinkedHashMap<String, Found>()
        for ((b, m) in manifests()) for (c in m.optJSONArray("catalogs").objects()) {
            if (!searches(c) || needs(c).any { it != "search" }) continue
            runCatching { metas(get("$b/catalog/${c.getString("type")}/${c.getString("id")}/search=${enc(q)}.json")) }
                .getOrDefault(emptyList()).forEach { out.putIfAbsent(it.id, it) }
        }
        return out.values.toList()
    }

    override suspend fun discoverMeta(type: String, id: String): JSONObject {
        val seen = store.getStringSet(SEEN, emptySet()).orEmpty()
        for ((b, m) in manifests()) {
            if (!gives(m, "meta", type, id)) continue
            val meta = runCatching { JSONObject(get("$b/meta/$type/${enc(id)}.json")).optJSONObject("meta") }.getOrNull() ?: continue
            return title(meta, seen)
        }
        error("None of your addons knows this title")
    }

    override suspend fun discoverStreams(type: String, id: String): JSONArray {
        val out = JSONArray()
        var torrents = 0
        for ((b, m) in manifests()) {
            if (!gives(m, "stream", type, id)) continue
            val streams = runCatching { JSONObject(get("$b/stream/$type/${enc(id)}.json", 90_000)).optJSONArray("streams") }.getOrNull()
            for (s in streams.objects()) files(s)?.let(out::put) ?: torrents++
        }
        if (out.length() == 0 && torrents > 0) error("Your addon gives torrents, not links: set it up with your debrid key and paste its new address in Settings")
        return out
    }

    /**
     * The episode after [id] (…:N -> …:N+1) and its file: the one of the same release group as [file] ([Group] at the
     * start of its name) if there is one, else the first this device decodes ([Playable]), else the first. Null when
     * [id] has no episode number; throws when the addons give nothing for it.
     */
    suspend fun next(type: String, id: String, file: String): Pair<String, JSONObject>? {
        val n = id.substringAfterLast(':').toIntOrNull() ?: return null
        val next = id.substringBeforeLast(':') + ":" + (n + 1)
        val files = discoverStreams(type, next).objects()
        val plays = files.filter { Playable.missing(it.optString("label") + " " + it.optString("about") + " " + it.optString("file")).isEmpty() }
        val group = Regex("""^\[[^\]]+\]""").find(file)?.value
        val f = plays.firstOrNull { group != null && it.optString("file").startsWith(group, ignoreCase = true) } ?: plays.firstOrNull() ?: files.firstOrNull()
        return f?.let { next to it }
    }

    /**
     * English lines for [id] from the user's subtitle addons (OpenSubtitles v3…), placed on the [japanese] line starts
     * ([io.github.pedrubik2000.kumapie.player.LiveLines.place]): of the first [MAX_ENGLISH] files, the one that puts
     * most of its lines on a Japanese one. Empty when no addon has one that fits.
     */
    suspend fun english(type: String, id: String, japanese: List<Double>): List<Jimaku.Line> {
        val asked = imdb(type, id) ?: return emptyList()
        val files = manifests().filter { (_, m) -> gives(m, "subtitles", type, asked) }.flatMap { (b, _) ->
            runCatching { JSONObject(get("$b/subtitles/$type/${enc(asked)}.json")).optJSONArray("subtitles").objects() }.getOrDefault(emptyList())
        }.filter { it.optString("lang") in setOf("eng", "en") }.take(MAX_ENGLISH)
        return files.mapNotNull { f ->
            val lines = runCatching { Jimaku.cues("en.srt", get(f.getString("url")).toByteArray(), Jimaku::plain) }.getOrNull()
                ?.takeIf { it.size * 2 >= japanese.size } ?: return@mapNotNull null // signs-only files
            val at = io.github.pedrubik2000.kumapie.player.LiveLines.place(japanese, lines.map { it.start }) ?: return@mapNotNull null
            lines.mapIndexed { i, l -> Jimaku.Line(at[i], at[i] + l.end - l.start, l.text) }
        }.maxByOrNull { ls -> ls.count { l -> japanese.any { kotlin.math.abs(it - l.start) <= 0.3 } } }.orEmpty()
    }

    /** [id] as subtitle addons know it (IMDb: tt…, an episode tt…:season:episode); a Kitsu id through its meta's imdb_id, imdbSeason, imdbEpisode. */
    private suspend fun imdb(type: String, id: String): String? {
        if (id.startsWith("tt")) return id
        val title = if (type == "series") id.substringBeforeLast(':') else id
        for ((b, m) in manifests()) {
            if (!gives(m, "meta", type, title)) continue
            val meta = runCatching { JSONObject(get("$b/meta/$type/${enc(title)}.json")).optJSONObject("meta") }.getOrNull() ?: continue
            if (type != "series") return meta.optString("imdb_id").takeIf { it.startsWith("tt") }
            val v = meta.optJSONArray("videos").objects().firstOrNull { it.optString("id") == id } ?: return null
            val tt = v.optString("imdb_id", meta.optString("imdb_id")).takeIf { it.startsWith("tt") } ?: return null
            return "$tt:${v.optInt("imdbSeason", v.optInt("season", 1))}:${v.optInt("imdbEpisode", v.optInt("episode"))}"
        }
        return null
    }

    override suspend fun saved(): List<Found> = JSONArray(store.getString(SAVED, "[]")).objects().map { found(it) }

    override suspend fun save(title: Found, on: Boolean): List<Found> {
        val kept = saved().filter { it.id != title.id } + if (on) listOf(title) else emptyList()
        store.edit().putString(SAVED, JSONArray().also { a ->
            kept.forEach { a.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("poster", it.poster).put("year", it.year)) }
        }.toString()).apply()
        return kept
    }

    override suspend fun seen(id: String, on: Boolean) {
        val now = store.getStringSet(SEEN, emptySet()).orEmpty()
        store.edit().putStringSet(SEEN, if (on) now + id else now - id).apply()
    }

    private fun metas(json: String): List<Found> = JSONObject(json).optJSONArray("metas").objects().map { found(title(it, emptySet())) }

    private suspend fun get(url: String, readTimeout: Int = 30_000): String = withContext(Dispatchers.IO) {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000
            this.readTimeout = readTimeout
            setRequestProperty("User-Agent", "kumapie")
            try {
                if (responseCode !in 200..299) error("${URL(url).host}: HTTP $responseCode")
                inputStream.bufferedReader().use { it.readText() }
            } finally { disconnect() }
        }
    }

    companion object {
        const val NO_ADDON = "Add a Stremio addon in Settings first"
        /** The user's AniList username (prefs): their lists as the first rows ([AniList]). */
        const val ANILIST = "anilist_user"
        /** The addons the onboarding names (user 2026-11-17): Kitsu (titles), Torrentio with the user's Real-Debrid token (links). */
        const val KITSU = "https://anime-kitsu.strem.fun/manifest.json"
        fun torrentio(rdToken: String) = "https://torrentio.strem.fun/realdebrid=${rdToken.trim()}/manifest.json"
        private const val MAX_ROWS = 8
        private const val MAX_ENGLISH = 5
        private const val SAVED = "alone_saved"
        private const val SEEN = "alone_seen"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this!!.optJSONObject(it) }

        /** An addon's address as pasted -> its base: stremio:// is https, without /manifest.json and a trailing slash. */
        fun base(url: String): String = url.trim().replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
            .let { if (it.isEmpty() || it.contains("://")) it else "https://$it" }
            .removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")

        /** The extras a catalog can't be asked without (its "search", "genre"…). */
        private fun needs(c: JSONObject): List<String> = c.optJSONArray("extra").objects().filter { it.optBoolean("isRequired") }.map { it.optString("name") } +
            (0 until (c.optJSONArray("extraRequired")?.length() ?: 0)).map { c.getJSONArray("extraRequired").getString(it) }

        private fun searches(c: JSONObject): Boolean = c.optJSONArray("extra").objects().any { it.optString("name") == "search" } ||
            (0 until (c.optJSONArray("extraSupported")?.length() ?: 0)).any { c.getJSONArray("extraSupported").getString(it) == "search" }

        /** Does the addon answer [resource] for this [type] and [id] (its manifest's resources, types, idPrefixes)? */
        private fun gives(m: JSONObject, resource: String, type: String, id: String): Boolean {
            val all = m.optJSONArray("resources") ?: return false
            for (i in 0 until all.length()) {
                val r = all.opt(i)
                val o = r as? JSONObject
                if ((o?.optString("name") ?: r.toString()) != resource) continue
                val types = (o?.optJSONArray("types") ?: m.optJSONArray("types"))
                val prefixes = (o?.optJSONArray("idPrefixes") ?: m.optJSONArray("idPrefixes"))
                val typeOk = types == null || (0 until types.length()).any { types.getString(it) == type }
                val idOk = prefixes == null || (0 until prefixes.length()).any { id.startsWith(prefixes.getString(it)) }
                if (typeOk && idOk) return true
            }
            return false
        }

        /** A Stremio meta object -> discover.py's title (its videos as episodes, specials left out, in order). */
        fun title(meta: JSONObject, seen: Set<String>): JSONObject {
            val videos = meta.optJSONArray("videos").objects().filter { it.optInt("season", 1) > 0 }
                .sortedWith(compareBy({ it.optInt("season", 1) }, { it.optInt("episode", it.optInt("number")) }))
            val seasons = videos.map { it.optInt("season", 1) }.distinct().size > 1
            val genres = meta.optJSONArray("genres")?.let { g -> (0 until g.length()).joinToString(", ") { g.getString(it) } }.orEmpty()
            return JSONObject().put("id", meta.optString("id")).put("type", meta.optString("type", "series")).put("name", meta.optString("name"))
                .put("poster", meta.optString("poster")).put("background", meta.optString("background")).put("logo", meta.optString("logo"))
                .put("description", meta.optString("description")).put("year", meta.optString("releaseInfo", meta.optString("year")))
                .put("genres", genres).put("rating", meta.optString("imdbRating")).put("runtime", meta.optString("runtime"))
                .put("episodes", JSONArray().also { a ->
                    videos.forEach { v ->
                        val name = v.optString("title", v.optString("name"))
                        a.put(JSONObject().put("id", v.optString("id")).put("season", v.optInt("season", 1))
                            .put("number", v.optInt("episode", v.optInt("number")))
                            .put("title", if (seasons) "S${v.optInt("season", 1)} · $name" else name)
                            .put("thumb", v.optString("thumbnail")).put("ready", false).put("watched", v.optString("id") in seen))
                    }
                })
        }

        /** A Stremio stream -> a file line; null for one without a link (a torrent). */
        fun files(s: JSONObject): JSONObject? {
            val url = s.optString("url").takeIf { it.startsWith("http") } ?: return null
            val name = s.optString("name").replace(Regex("""\s*\n\s*"""), " ").trim()
            val about = s.optString("title", s.optString("description")).trim()
            return JSONObject().put("label", name.ifEmpty { about.lineSequence().firstOrNull().orEmpty() }).put("about", about)
                .put("short", name).put("url", url).put("file", s.optJSONObject("behaviorHints")?.optString("filename").orEmpty())
        }
    }
}
