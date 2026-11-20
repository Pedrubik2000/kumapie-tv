package io.github.pedrubik2000.kumapie.data

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The user's AniList lists as Discover rows without a PC (kumapie_plan.md "Onboarding"): no Stremio addon does this
 * simply, so kumapie asks AniList's public API by username (a public profile, no login), and maps each title to its
 * Kitsu id (Kitsu's mappings, cached in [store]) so the Kitsu and Torrentio addons know it, as the PC's anilist.py does.
 */
object AniList {
    private const val CACHE = "anilist_kitsu_2" // _2: the first build cached every title as "not on Kitsu"

    /** "AniList · Watching" and "AniList · Planning" for [user]; titles Kitsu doesn't know are left out. */
    suspend fun rows(user: String, store: SharedPreferences): List<Pair<String, List<Found>>> = withContext(Dispatchers.IO) {
        val q = "query(\$u:String){MediaListCollection(userName:\$u,type:ANIME,status_in:[CURRENT,PLANNING]){lists{status entries{media{" +
            "id title{romaji english native} coverImage{extraLarge} bannerImage startDate{year} averageScore description(asHtml:false)}}}}}"
        val body = JSONObject().put("query", q).put("variables", JSONObject().put("u", user)).toString()
        val answer = JSONObject(post("https://graphql.anilist.co", body))
        answer.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.let { error("AniList: $it") }
        val lists = answer.getJSONObject("data").getJSONObject("MediaListCollection").getJSONArray("lists")
        val byStatus = (0 until lists.length()).map { lists.getJSONObject(it) }.associate { l ->
            l.getString("status") to l.getJSONArray("entries").let { e -> (0 until e.length()).map { e.getJSONObject(it).getJSONObject("media") } }
        }
        val kitsu = kitsuIds(byStatus.values.flatten().map { it.getInt("id") }, store)
        listOf("CURRENT" to "AniList · Watching", "PLANNING" to "AniList · Planning").mapNotNull { (status, name) ->
            val found = byStatus[status].orEmpty().mapNotNull { m ->
                val k = kitsu[m.getInt("id")] ?: return@mapNotNull null
                val t = m.getJSONObject("title")
                // The Japanese title first (user, 2026-11-17), else English, else romaji.
                fun name(field: String) = t.optString(field).takeIf { it.isNotEmpty() && it != "null" }
                Found("kitsu:$k", "anime", name("native") ?: name("english") ?: t.optString("romaji"),
                    m.optJSONObject("coverImage")?.optString("extraLarge").orEmpty(), m.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }?.toString().orEmpty(),
                    m.optString("bannerImage").takeIf { it != "null" }.orEmpty(), "",
                    m.optString("description").takeIf { it != "null" }.orEmpty().replace(Regex("<[^>]+>"), ""), "",
                    m.optInt("averageScore").takeIf { it > 0 }?.let { "%.1f".format(it / 10.0) }.orEmpty(), "")
            }
            found.takeIf { it.isNotEmpty() }?.let { name to it }
        }
    }

    /** AniList ids -> Kitsu ids, 20 per request, kept in [store] (a mapping doesn't change). */
    private fun kitsuIds(ids: List<Int>, store: SharedPreferences): Map<Int, String> {
        val cache = JSONObject(store.getString(CACHE, "{}") ?: "{}")
        for (chunk in ids.filter { !cache.has(it.toString()) }.distinct().chunked(20)) runCatching {
            val url = "https://kitsu.io/api/edge/mappings?filter[externalSite]=anilist/anime&filter[externalId]=${chunk.joinToString(",")}" +
                "&page[limit]=20&include=item&fields[anime]=id" // without include, relationships.item has no id
            val data = JSONObject(get(url)).getJSONArray("data")
            for (i in 0 until data.length()) data.getJSONObject(i).let { m ->
                val k = m.getJSONObject("relationships").getJSONObject("item").optJSONObject("data")?.optString("id") ?: return@let
                cache.put(m.getJSONObject("attributes").getString("externalId"), k)
            }
            chunk.forEach { if (!cache.has(it.toString())) cache.put(it.toString(), "") } // none on Kitsu: not asked again
        }
        store.edit().putString(CACHE, cache.toString()).apply()
        return ids.associateWith { cache.optString(it.toString()) }.filterValues { it.isNotEmpty() }
    }


    const val APP_CLIENT = 56516

    const val TV_CLIENT = 57597

    fun loginUrl(client: Int = APP_CLIENT) = "https://anilist.co/api/v2/oauth/authorize?client_id=$client&response_type=token"

    fun tokenOf(redirect: String): String? = redirect.substringAfter('#', "").split('&')
        .firstOrNull { it.startsWith("access_token=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }

    class LoggedOut : Exception("AniList: log in again")

    data class Entry(
        val status: String? = null, val score: Double = 0.0, val progress: Int = 0, val repeat: Int = 0,
        val started: String = "", val completed: String = "", val episodes: Int? = null,
    )

    val STATUSES = listOf("CURRENT", "PLANNING", "COMPLETED", "REPEATING", "PAUSED", "DROPPED")

    fun maxScore(format: String) = when (format) { "POINT_100" -> 100; "POINT_5" -> 5; "POINT_3" -> 3; else -> 10 }

    fun parseScore(text: String, format: String): Double? {
        val v = text.trim().replace(',', '.').ifEmpty { "0" }.toDoubleOrNull() ?: return null
        if (v < 0 || v > maxScore(format)) return null
        return if (format == "POINT_10_DECIMAL") Math.round(v * 10) / 10.0 else v.takeIf { it == Math.floor(it) }
    }

    fun showScore(score: Double, format: String) = if (format == "POINT_10_DECIMAL") "%.1f".format(score) else score.toInt().toString()

    suspend fun viewer(token: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val v = gql(token, "{Viewer{name mediaListOptions{scoreFormat}}}", JSONObject()).getJSONObject("Viewer")
        v.getString("name") to (v.optJSONObject("mediaListOptions")?.optString("scoreFormat")?.takeIf { it != "null" } ?: "POINT_10")
    }

    suspend fun idOfKitsu(kitsu: String, store: SharedPreferences): Int? = withContext(Dispatchers.IO) {
        val n = kitsu.removePrefix("kitsu:").substringBefore(':').takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@withContext null
        val key = "anilist_of_kitsu_$n"
        store.getString(key, null)?.let { return@withContext it.toIntOrNull() }
        val data = JSONObject(get("https://kitsu.io/api/edge/anime/$n/mappings?filter[externalSite]=anilist/anime")).getJSONArray("data")
        val id = (0 until data.length()).map { data.getJSONObject(it).getJSONObject("attributes") }
            .firstOrNull { it.optString("externalSite") == "anilist/anime" }?.optString("externalId")?.toIntOrNull()
        store.edit().putString(key, id?.toString().orEmpty()).apply()
        id
    }

    private const val FIELDS = "status score progress repeat startedAt{year month day} completedAt{year month day}"

    suspend fun entry(token: String, media: Int): Entry = withContext(Dispatchers.IO) {
        val m = gql(token, "query(\$id:Int){Media(id:\$id){episodes mediaListEntry{$FIELDS}}}", JSONObject().put("id", media)).getJSONObject("Media")
        val episodes = m.optInt("episodes").takeIf { it > 0 }
        m.optJSONObject("mediaListEntry")?.let { entryOf(it).copy(episodes = episodes) } ?: Entry(episodes = episodes)
    }

    suspend fun save(token: String, media: Int, e: Entry): Entry = withContext(Dispatchers.IO) {
        val q = "mutation(\$id:Int,\$s:MediaListStatus,\$sc:Float,\$p:Int,\$r:Int,\$a:FuzzyDateInput,\$c:FuzzyDateInput)" +
            "{SaveMediaListEntry(mediaId:\$id,status:\$s,score:\$sc,progress:\$p,repeat:\$r,startedAt:\$a,completedAt:\$c){$FIELDS}}"
        val v = JSONObject().put("id", media).put("s", e.status ?: "CURRENT").put("sc", e.score).put("p", e.progress).put("r", e.repeat)
            .put("a", fuzzy(e.started)).put("c", fuzzy(e.completed))
        entryOf(gql(token, q, v).getJSONObject("SaveMediaListEntry")).copy(episodes = e.episodes)
    }

    fun watched(e: Entry, number: Int, today: String): Entry {
        if (number <= e.progress && e.status != null) return e
        var n = e.copy(progress = maxOf(e.progress, number))
        if (e.status == null || e.status == "PLANNING" || e.status == "PAUSED" || e.status == "DROPPED") n = n.copy(status = "CURRENT")
        if (n.started.isEmpty() && e.progress == 0 && e.status != "COMPLETED") n = n.copy(started = today)
        if (e.episodes != null && n.progress >= e.episodes && e.status != "COMPLETED") {
            n = n.copy(status = "COMPLETED", completed = today, repeat = if (e.status == "REPEATING") e.repeat + 1 else e.repeat)
        }
        return n
    }

    private fun entryOf(j: JSONObject) = Entry(
        j.optString("status").takeIf { it.isNotEmpty() && it != "null" }, j.optDouble("score", 0.0).takeIf { !it.isNaN() } ?: 0.0,
        j.optInt("progress"), j.optInt("repeat"), date(j.optJSONObject("startedAt")), date(j.optJSONObject("completedAt")),
    )

    private fun date(d: JSONObject?): String {
        val y = d?.optInt("year") ?: 0
        if (d == null || y <= 0) return ""
        return "%04d-%02d-%02d".format(y, d.optInt("month").coerceAtLeast(1), d.optInt("day").coerceAtLeast(1))
    }

    private fun fuzzy(s: String): JSONObject {
        val p = s.trim().split('-').map { it.toIntOrNull() }
        return JSONObject().put("year", p.getOrNull(0) ?: JSONObject.NULL).put("month", p.getOrNull(1) ?: JSONObject.NULL)
            .put("day", p.getOrNull(2) ?: JSONObject.NULL)
    }

    private fun gql(token: String, query: String, variables: JSONObject): JSONObject {
        val (code, text) = post("https://graphql.anilist.co", JSONObject().put("query", query).put("variables", variables).toString(), token)
        val answer = runCatching { JSONObject(text) }.getOrNull()
        val error = answer?.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
        if (code == 401 || error?.contains("token", ignoreCase = true) == true || error == "Unauthorized.") throw LoggedOut()
        error?.let { error("AniList: $it") }
        return answer?.optJSONObject("data") ?: error("AniList: HTTP $code")
    }

    private fun get(url: String): String = (URL(url.replace("[", "%5B").replace("]", "%5D")).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.api+json")
        inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun post(url: String, body: String): String = post(url, body, null).second

    private fun post(url: String, body: String, token: String?): Pair<Int, String> = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000; readTimeout = 30_000
        requestMethod = "POST"; doOutput = true
        setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
        token?.let { setRequestProperty("Authorization", "Bearer $it") }
        outputStream.use { it.write(body.toByteArray()) }
        responseCode to ((if (responseCode < 400) inputStream else errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
    }
}
