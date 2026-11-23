package io.github.pedrubik2000.kumapie.data

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Mine(
    val anilistId: Int, val kitsuId: String, val native: String, val romaji: String, val cover: String, val banner: String,
    val format: String, val episodes: Int?, val status: String, val progress: Int, val score: Double, val average: Int,
    val genres: List<String>, val year: Int,
)

object AniListLists {
    private const val CACHE = "anilist_mine"
    private const val QUERY = "query(\$u:String){MediaListCollection(userName:\$u,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED])" +
        "{lists{entries{status progress score media{id format episodes averageScore genres title{romaji native} coverImage{extraLarge} " +
        "bannerImage startDate{year}}}}}}"

    fun cached(prefs: SharedPreferences): List<Mine> = runCatching {
        JSONArray(prefs.getString(CACHE, "[]")).let { a -> (0 until a.length()).map { mine(a.getJSONObject(it)) } }
    }.getOrDefault(emptyList())

    suspend fun refresh(prefs: SharedPreferences, user: String, token: String): List<Mine> {
        val data = AniList.gql(token, QUERY, JSONObject().put("u", user))
        val entries = data.getJSONObject("MediaListCollection").getJSONArray("lists").objects().flatMap { it.getJSONArray("entries").objects() }
        val kitsu = AniList.kitsuIds(entries.map { it.getJSONObject("media").getInt("id") }, prefs)
        val all = parse(entries, kitsu)
        prefs.edit().putString(CACHE, JSONArray(all.map(::json)).toString()).apply()
        return all
    }

    fun cacheFirst(prefs: SharedPreferences, user: String, token: String, scope: CoroutineScope, fresh: (List<Mine>) -> Unit): List<Mine> {
        if (user.isNotEmpty()) scope.launch { runCatching { refresh(prefs, user, token) }.onSuccess(fresh) }
        return cached(prefs)
    }

    fun parse(entries: List<JSONObject>, kitsu: Map<Int, String>): List<Mine> = entries.mapNotNull { e ->
        val m = e.getJSONObject("media")
        val k = kitsu[m.getInt("id")] ?: return@mapNotNull null
        fun text(o: JSONObject?, f: String) = o?.optString(f)?.takeIf { it != "null" }.orEmpty()
        val t = m.optJSONObject("title")
        Mine(m.getInt("id"), k, text(t, "native"), text(t, "romaji"), text(m.optJSONObject("coverImage"), "extraLarge"), text(m, "bannerImage"),
            text(m, "format"), m.optInt("episodes").takeIf { it > 0 }, e.optString("status"), e.optInt("progress"), e.optDouble("score", 0.0),
            m.optInt("averageScore"), m.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            m.optJSONObject("startDate")?.optInt("year") ?: 0)
    }

    private fun json(m: Mine) = JSONObject().put("id", m.anilistId).put("kitsu", m.kitsuId).put("native", m.native).put("romaji", m.romaji)
        .put("cover", m.cover).put("banner", m.banner).put("format", m.format).put("episodes", m.episodes ?: 0).put("status", m.status)
        .put("progress", m.progress).put("score", m.score).put("average", m.average).put("genres", JSONArray(m.genres)).put("year", m.year)

    private fun mine(j: JSONObject) = Mine(j.getInt("id"), j.getString("kitsu"), j.getString("native"), j.getString("romaji"), j.getString("cover"),
        j.getString("banner"), j.getString("format"), j.getInt("episodes").takeIf { it > 0 }, j.getString("status"), j.getInt("progress"),
        j.getDouble("score"), j.getInt("average"), j.getJSONArray("genres").let { a -> (0 until a.length()).map { a.getString(it) } },
        j.getInt("year"))
}
