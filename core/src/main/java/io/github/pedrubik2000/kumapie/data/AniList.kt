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

    private fun get(url: String): String = (URL(url.replace("[", "%5B").replace("]", "%5D")).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.api+json")
        inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun post(url: String, body: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000; readTimeout = 30_000
        requestMethod = "POST"; doOutput = true
        setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
        outputStream.use { it.write(body.toByteArray()) }
        (if (responseCode < 400) inputStream else errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
