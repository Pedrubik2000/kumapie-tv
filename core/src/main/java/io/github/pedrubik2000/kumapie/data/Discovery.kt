package io.github.pedrubik2000.kumapie.data

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.torrent.TorrentRef
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

interface Discovery {
    suspend fun board(): List<Pair<String, List<Found>>>
    suspend fun discover(q: String): List<Found>

    suspend fun discoverMeta(type: String, id: String): JSONObject

    suspend fun discoverStreams(type: String, id: String): JSONArray
    suspend fun saved(): List<Found>

    suspend fun save(title: Found, on: Boolean): List<Found>

    suspend fun seen(id: String, on: Boolean)

    suspend fun going(): List<Pair<Show, Episode>> = emptyList()

    fun goingNow(): List<Pair<Show, Episode>>? = null

    suspend fun playedAt(): Map<String, Long> = going().associate { (s, e) -> s.id to e.resumedAt }

    fun found(o: JSONObject) = Found(o.optString("id"), o.optString("type", "series"), o.optString("name"), o.optString("poster"), o.optString("year"),
        o.optString("background"), o.optString("logo"), o.optString("description"), o.optString("genres"), o.optString("rating"), o.optString("runtime"), o.optString("name_ja"))
}

class Addons(urls: List<String>, private val store: SharedPreferences, val sync: AniListSync = AniListSync(store)) : Discovery {
    private val bases = urls.map(::base).filter { it.isNotEmpty() }.distinct()
    private val manifests = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    fun done(id: String, type: String) {
        store.edit().putStringSet(SEEN, store.getStringSet(SEEN, emptySet()).orEmpty() + id).apply()
        sync.episodeDone(id, type)
    }

    fun left(id: String, type: String, positionMs: Long, durationMs: Long) {
        if (nearEnd(positionMs / 1000.0, durationMs / 1000.0)) done(id, type)
    }

    private suspend fun manifests(): List<Pair<String, JSONObject>> = kotlinx.coroutines.coroutineScope {
        bases.map { b ->
            async { (manifests[b] ?: runCatching { JSONObject(get("$b/manifest.json")) }.getOrNull()?.also { manifests[b] = it })?.let { b to it } }
        }.awaitAll().filterNotNull()
    }

    suspend fun names(): List<String> = manifests().let { all -> bases.map { b -> all.firstOrNull { it.first == b }?.second?.optString("name").orEmpty() } }

    override suspend fun board(): List<Pair<String, List<Found>>> = kotlinx.coroutines.coroutineScope {
        val anilist = store.getString(ANILIST, "").orEmpty().takeIf { it.isNotBlank() }?.let { user ->
            async { runCatching { AniList.rows(user, store) }.onFailure { android.util.Log.w("kumapie", "AniList rows: $it") }.getOrDefault(emptyList()) }
        }
        val catalogs = manifests().flatMap { (b, m) -> m.optJSONArray("catalogs").objects().filter { needs(it).isEmpty() }.map { b to it } }
            .take(MAX_ROWS).map { (b, c) ->
                async {
                    val titles = runCatching { withJa(metas(get("$b/catalog/${c.getString("type")}/${c.getString("id")}.json"))) }.getOrDefault(emptyList())
                    if (titles.isEmpty()) null else (c.optString("name").ifEmpty { c.getString("id") } + " · " + c.getString("type")) to titles
                }
            }
        val rows = (anilist?.await().orEmpty() + catalogs.awaitAll().filterNotNull()).take(MAX_ROWS)
        if (rows.isEmpty()) error(tr(if (bases.isEmpty()) NO_ADDON else "None of your addons has a catalog: add one that lists titles (Cinemeta, Kitsu…)"))
        store.edit().putString(BOARD, JSONArray(rows.map { (name, titles) ->
            JSONObject().put("name", name).put("titles", JSONArray(titles.map { f ->
                JSONObject().put("id", f.id).put("type", f.type).put("name", f.name).put("poster", f.poster).put("year", f.year)
                    .put("background", f.background).put("logo", f.logo).put("description", f.description).put("genres", f.genres)
                    .put("rating", f.rating).put("runtime", f.runtime).put("name_ja", f.nameJa)
            }))
        }).toString()).apply()
        rows
    }

    fun lastBoard(): List<Pair<String, List<Found>>>? = store.getString(BOARD, null)?.let { saved -> runCatching {
        JSONArray(saved).objects().map { r -> r.getString("name") to r.getJSONArray("titles").objects().map(::found) }
    }.getOrNull() }

    override suspend fun discover(q: String): List<Found> {
        val out = LinkedHashMap<String, Found>()
        for ((b, m) in manifests()) for (c in m.optJSONArray("catalogs").objects()) {
            if (!searches(c) || needs(c).any { it != "search" }) continue
            runCatching { metas(get("$b/catalog/${c.getString("type")}/${c.getString("id")}/search=${enc(q)}.json")) }
                .getOrDefault(emptyList()).forEach { out.putIfAbsent(it.id, it) }
        }
        return withJa(out.values.toList())
    }

    override suspend fun discoverMeta(type: String, id: String): JSONObject = withContext(Dispatchers.Default) {
        val seen = store.getStringSet(SEEN, emptySet()).orEmpty()
        for ((b, m) in manifests()) {
            if (!gives(m, "meta", type, id)) continue
            val meta = runCatching { JSONObject(get("$b/meta/$type/${enc(id)}.json")).optJSONObject("meta") }.getOrNull() ?: continue
            return@withContext title(meta, seen)
        }
        error(tr("None of your addons knows this title"))
    }

    override suspend fun discoverStreams(type: String, id: String): JSONArray {
        val out = JSONArray()
        var torrents = 0
        val ours = ArrayList<JSONObject>()
        val streaming = store.getBoolean(Settings.TORRENTS, false)
        for ((b, m) in manifests()) {
            if (!gives(m, "stream", type, id)) continue
            val streams = runCatching { JSONObject(get("$b/stream/$type/${enc(id)}.json", 90_000)).optJSONArray("streams") }.getOrNull()
            for (s in streams.objects()) files(s)?.let(out::put) ?: (if (streaming) torrentFile(s)?.let(ours::add) else null) ?: torrents++
        }
        ours.forEach(out::put)
        if (out.length() == 0 && torrents > 0) error(tr("Your addon gives torrents, not links: set it up with your debrid key and paste its new address in Settings"))
        return out
    }

    suspend fun english(type: String, id: String, japanese: List<Double>): List<Jimaku.Line> = Companion.fitEnglish(englishFiles(type, id), japanese)

    suspend fun englishFiles(type: String, id: String): List<List<Jimaku.Line>> {
        val asked = imdb(type, id) ?: return emptyList()
        val kind = if (type == "movie") "movie" else "series"
        val files = manifests().filter { (_, m) -> gives(m, "subtitles", kind, asked) }.flatMap { (b, _) ->
            runCatching { JSONObject(get("$b/subtitles/$kind/${enc(asked)}.json")).optJSONArray("subtitles").objects() }.getOrDefault(emptyList())
        }.filter { it.optString("lang") in setOf("eng", "en") }.take(MAX_ENGLISH)
        return files.mapNotNull { f -> runCatching { Jimaku.cues("en.srt", bytes(f.getString("url")), Jimaku::plain) }.getOrNull() }
    }

    private suspend fun imdb(type: String, id: String): String? {
        if (id.startsWith("tt")) return id
        val episode = type != "movie" && id.count { it == ':' } >= 2
        val title = if (episode) id.substringBeforeLast(':') else id
        for ((b, m) in manifests()) {
            if (!gives(m, "meta", type, title)) continue
            val meta = runCatching { JSONObject(get("$b/meta/$type/${enc(title)}.json")).optJSONObject("meta") }.getOrNull() ?: continue
            if (!episode) return meta.optString("imdb_id").takeIf { it.startsWith("tt") }
            val v = meta.optJSONArray("videos").objects().firstOrNull { it.optString("id") == id } ?: return null
            val tt = v.optString("imdb_id", meta.optString("imdb_id")).takeIf { it.startsWith("tt") } ?: return null
            return "$tt:${v.optInt("imdbSeason", v.optInt("season", 1))}:${v.optInt("imdbEpisode", v.optInt("episode"))}"
        }
        return null
    }

    override suspend fun saved(): List<Found> = JSONArray(store.getString(SAVED, "[]")).objects().map { found(it) }
        .map { f -> if (f.nameJa.isNotEmpty()) f else f.copy(nameJa = nameJa(f.id)) }

    private suspend fun nameJa(id: String): String {
        val n = id.removePrefix("kitsu:").takeIf { id.startsWith("kitsu:") && it.isNotEmpty() && it.all(Char::isDigit) } ?: return ""
        store.getString("alone_ja_$id", null)?.let { return it }
        val ja = runCatching { JSONObject(get("https://kitsu.app/api/edge/anime/$n", 15_000)).getJSONObject("data").getJSONObject("attributes")
            .optJSONObject("titles")?.optString("ja_jp").orEmpty() }.getOrNull() ?: return ""
        store.edit().putString("alone_ja_$id", ja).apply()
        return ja
    }

    private suspend fun withJa(titles: List<Found>): List<Found> {
        val ask = titles.map { it.id }.filter { id -> id.startsWith("kitsu:") && id.length > 6 && id.drop(6).all(Char::isDigit) && store.getString("alone_ja_$id", null) == null }.distinct()
        for (chunk in ask.chunked(20)) runCatching {
            val data = JSONObject(get("https://kitsu.app/api/edge/anime?filter%5Bid%5D=${chunk.joinToString(",") { it.drop(6) }}" +
                "&fields%5Banime%5D=titles&page%5Blimit%5D=20", 15_000)).getJSONArray("data")
            val ja = (0 until data.length()).map { data.getJSONObject(it) }
                .associate { "kitsu:" + it.getString("id") to it.optJSONObject("attributes")?.optJSONObject("titles")?.optString("ja_jp").orEmpty() }
            store.edit().also { e -> chunk.forEach { id -> e.putString("alone_ja_$id", ja[id].orEmpty()) } }.apply()
        }.onFailure { android.util.Log.w("kumapie", "Kitsu ja names: $it") }
        return titles.map { f -> if (f.nameJa.isNotEmpty()) f else store.getString("alone_ja_${f.id}", null)?.takeIf { it.isNotEmpty() }?.let { f.copy(nameJa = it) } ?: f }
    }

    override suspend fun save(title: Found, on: Boolean): List<Found> = keep(title, on).also { if (on) sync.added(title.id) }

    private suspend fun keep(title: Found, on: Boolean): List<Found> {
        val kept = saved().filter { it.id != title.id } + if (on) listOf(title) else emptyList()
        store.edit().putString(SAVED, JSONArray().also { a ->
            kept.forEach { a.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("poster", it.poster).put("year", it.year).put("name_ja", it.nameJa)) }
        }.toString()).apply()
        return kept
    }

    override suspend fun seen(id: String, on: Boolean) {
        val now = store.getStringSet(SEEN, emptySet()).orEmpty()
        store.edit().putStringSet(SEEN, if (on) now + id else now - id).apply()
    }

    override suspend fun going(): List<Pair<Show, Episode>> = going(saved())

    override suspend fun playedAt(): Map<String, Long> = lastPlays(store.all)

    override fun goingNow(): List<Pair<Show, Episode>> = going(JSONArray(store.getString(SAVED, "[]")).objects().map { found(it) }
        .map { f -> if (f.nameJa.isNotEmpty()) f else f.copy(nameJa = store.getString("alone_ja_${f.id}", null).orEmpty()) })

    private fun going(saved: List<Found>): List<Pair<Show, Episode>> {
        val seen = store.getStringSet(SEEN, emptySet()).orEmpty()
        val keys = store.all.keys
        fun n(id: String) = id.substringAfterLast(':').toIntOrNull() ?: 0
        return saved.mapNotNull { t ->
            val last = store.getString("alone_last_${t.id}", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            var id = last?.optString("id")?.takeIf { it.isNotEmpty() }
                ?: keys.filter { it.startsWith("alone_pos_${t.id}:") }.map { it.removePrefix("alone_pos_") }.maxByOrNull(::n)
                ?: seen.filter { it.startsWith("${t.id}:") }.maxByOrNull(::n)
                ?: return@mapNotNull null
            val pos = if (store.contains("alone_pos_$id")) store.getFloat("alone_pos_$id", 0f).toDouble() else null
            var resume = pos?.takeIf { it > RESUME_AFTER_S }
            if (id in seen || pos == 0.0) {
                id = nextId(t.type, id)?.takeIf { it !in seen } ?: return@mapNotNull null
                resume = null
            }
            val number = if (id == t.id) null else n(id)
            val e = Episode(id, number?.let { tr("Episode %d", it) } ?: t.name, 1, number, store.getFloat("alone_len_$id", 0f).toDouble(), "",
                0, 0, 0, resume, resumedAt = last?.optLong("at") ?: 0L)
            Show(t.id, t.name, "stream", t.poster, listOf(e), "ja", titleJa = t.nameJa) to e
        }.sortedByDescending { it.second.resumedAt }
    }

    fun played(id: String, at: Double, length: Double) {
        store.edit().putFloat("alone_pos_$id", at.toFloat()).putFloat("alone_len_$id", length.toFloat())
            .putString("alone_last_${titleOf(id)}", JSONObject().put("id", id).put("at", System.currentTimeMillis() / 1000).toString()).apply()
    }

    suspend fun keepFromPc(saved: List<Found>, going: List<Pair<Show, Episode>>) {
        (going.map { (s, e) -> Found(s.id, if (e.id == s.id) "movie" else "series", s.title, s.poster, "", nameJa = s.titleJa) } + saved)
            .distinctBy { it.id }.reversed().forEach { keep(it, true) }
        for ((_, e) in going) {
            val n = e.id.substringAfterLast(':').toIntOrNull()
            if (nextId("series", e.id) != null && n != null) for (i in 1 until n) seen(e.id.substringBeforeLast(':') + ":$i", true)
            val here = store.getString("alone_last_${titleOf(e.id)}", null)?.let { runCatching { JSONObject(it).getLong("at") }.getOrNull() }
            if (here != null && here >= e.resumedAt) continue
            e.resume?.let { store.edit().putFloat("alone_pos_${e.id}", it.toFloat()).apply() }
            store.edit().putFloat("alone_len_${e.id}", e.duration.toFloat())
                .putString("alone_last_${titleOf(e.id)}", JSONObject().put("id", e.id).put("at", e.resumedAt).toString()).apply()
        }
    }

    private fun metas(json: String): List<Found> = JSONObject(json).optJSONArray("metas").objects().map { found(title(it, emptySet())) }

    private suspend fun get(url: String, readTimeout: Int = 30_000): String = String(bytes(url, readTimeout), Charsets.UTF_8)

    private suspend fun bytes(url: String, readTimeout: Int = 30_000): ByteArray = withContext(Dispatchers.IO) {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000
            this.readTimeout = readTimeout
            setRequestProperty("User-Agent", "kumapie")
            try {
                if (responseCode !in 200..299) error("${URL(url).host}: HTTP $responseCode")
                inputStream.use { it.readBytes() }
            } finally { disconnect() }
        }
    }

    companion object {
        const val NO_ADDON = "Add a Stremio addon in Settings first"
        const val ANILIST = "anilist_user"
        const val KITSU = "https://anime-kitsu.strem.fun/manifest.json"
        const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json"
        const val TORRENTIO_PLAIN = "https://torrentio.strem.fun/manifest.json"
        fun torrentio(rdToken: String) = "https://torrentio.strem.fun/realdebrid=${rdToken.trim()}/manifest.json"
        fun fitEnglish(files: List<List<Jimaku.Line>>, japanese: List<Double>, duration: Double? = null): List<Jimaku.Line> = files.filter { Jimaku.fitsLength(it, duration) }.mapNotNull { lines ->
            if (lines.size * 2 < japanese.size) return@mapNotNull null
            val at = io.github.pedrubik2000.kumapie.player.LiveLines.place(japanese, lines.map { it.start }) ?: return@mapNotNull null
            lines.mapIndexed { i, l -> Jimaku.Line(at[i], at[i] + l.end - l.start, l.text) }
        }.maxByOrNull { ls -> ls.count { l -> japanese.any { kotlin.math.abs(it - l.start) <= 0.3 } } }.orEmpty()

        fun sources(current: List<String>, kitsu: Boolean, english: Boolean, rd: String, torrents: Boolean = false): List<String> {
            val kept = current.filterNot { it == KITSU || it == OPENSUBTITLES || (rd.isNotBlank() && "torrentio.strem.fun" in it) }
            val plain = TORRENTIO_PLAIN.takeIf { torrents && rd.isBlank() && current.none { "torrentio.strem.fun" in it } }
            return (kept + listOfNotNull(KITSU.takeIf { kitsu }, OPENSUBTITLES.takeIf { english }, rd.takeIf { it.isNotBlank() }?.let(::torrentio), plain)).distinct()
        }
        private const val MAX_ROWS = 8
        private const val MAX_ENGLISH = 5
        private const val SAVED = "alone_saved"
        private const val SEEN = "alone_seen"
        private const val BOARD = "discover_board"

        fun titleOf(id: String) = id.split(':').take(if (id.startsWith("kitsu:")) 2 else 1).joinToString(":")

        fun lastPlays(all: Map<String, *>): Map<String, Long> = all.mapNotNull { (k, v) ->
            if (!k.startsWith("alone_last_") || v !is String) null
            else runCatching { k.removePrefix("alone_last_") to JSONObject(v).getLong("at") }.getOrNull()
        }.toMap()

        fun nextId(type: String, id: String): String? {
            if (type == "movie" || id.count { it == ':' } < 2) return null
            val n = id.substringAfterLast(':').toIntOrNull() ?: return null
            return id.substringBeforeLast(':') + ":" + (n + 1)
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }

        fun base(url: String): String = url.trim().replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
            .let { if (it.isEmpty() || it.contains("://")) it else "https://$it" }
            .removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")

        private fun needs(c: JSONObject): List<String> = c.optJSONArray("extra").objects().filter { it.optBoolean("isRequired") }.map { it.optString("name") } +
            (0 until (c.optJSONArray("extraRequired")?.length() ?: 0)).map { c.getJSONArray("extraRequired").getString(it) }

        private fun searches(c: JSONObject): Boolean = c.optJSONArray("extra").objects().any { it.optString("name") == "search" } ||
            (0 until (c.optJSONArray("extraSupported")?.length() ?: 0)).any { c.getJSONArray("extraSupported").getString(it) == "search" }

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

        fun title(meta: JSONObject, seen: Set<String>): JSONObject {
            val videos = meta.optJSONArray("videos").objects().filter { it.optInt("season", 1) > 0 }
                .sortedWith(compareBy({ it.optInt("season", 1) }, { it.optInt("episode", it.optInt("number")) }))
            val seasons = videos.map { it.optInt("season", 1) }.distinct().size > 1
            val genres = meta.optJSONArray("genres")?.let { g -> (0 until g.length()).joinToString(", ") { g.getString(it) } }.orEmpty()
            return JSONObject().put("id", meta.optString("id")).put("type", meta.optString("type", "series")).put("name", meta.optString("name"))
                .put("poster", meta.optString("poster")).put("background", meta.optString("background")).put("logo", meta.optString("logo"))
                .put("description", meta.optString("description")).put("year", meta.optString("releaseInfo", meta.optString("year")))
                .put("genres", genres).put("rating", meta.optString("imdbRating")).put("runtime", meta.optString("runtime")).put("status", meta.optString("status"))
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

        fun torrentFile(s: JSONObject): JSONObject? {
            val ref = TorrentRef.of(s) ?: return null
            val name = s.optString("name").replace(Regex("""\s*\n\s*"""), " ").trim()
            val about = s.optString("title", s.optString("description")).trim()
            val file = ref.name.ifEmpty { about.lineSequence().firstOrNull().orEmpty() }
            return JSONObject().put("label", name.ifEmpty { file }).put("about", about).put("short", name).put("url", ref.copy(name = file).uri())
                .put("file", file).put("torrent", true).put("seeders", TorrentRef.seeders(about))
        }

        fun files(s: JSONObject): JSONObject? {
            val url = s.optString("url").takeIf { it.startsWith("http") } ?: return null
            val name = s.optString("name").replace(Regex("""\s*\n\s*"""), " ").trim()
            val about = s.optString("title", s.optString("description")).trim()
            return JSONObject().put("label", name.ifEmpty { about.lineSequence().firstOrNull().orEmpty() }).put("about", about)
                .put("short", name).put("url", url).put("file", s.optJSONObject("behaviorHints")?.optString("filename").orEmpty())
        }
    }
}
