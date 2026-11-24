package io.github.pedrubik2000.kumapie.data

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object AniListBrowse {
    const val PER_PAGE = 50
    const val TOTAL_CAP = 5000
    private const val TAG_RANK = 60

    class Query(
        val search: String = "", val sort: List<String> = listOf("POPULARITY_DESC"), val genreIn: Set<String> = emptySet(), val genreNot: Set<String> = emptySet(),
        val tagIn: Set<String> = emptySet(), val tagNot: Set<String> = emptySet(), val formats: Set<String> = emptySet(), val status: String = "",
        val episodesGreater: Int? = null, val episodesLesser: Int? = null, val startGreater: Int? = null, val startLesser: Int? = null,
        val onList: Boolean? = null, val idIn: List<Int>? = null,
    )

    class Page(val items: List<Mine>, val total: Int, val hasNext: Boolean, val expired: Boolean = false)

    private const val QUERY = "query(\$p:Int,\$search:String,\$sort:[MediaSort],\$gi:[String],\$gn:[String],\$ti:[String],\$tn:[String],\$f:[MediaFormat]," +
        "\$st:MediaStatus,\$eg:Int,\$el:Int,\$sg:FuzzyDateInt,\$sl:FuzzyDateInt,\$ol:Boolean,\$ids:[Int]){Page(page:\$p,perPage:$PER_PAGE){pageInfo{total hasNextPage} " +
        "media(type:ANIME,isAdult:false,countryOfOrigin:JP,minimumTagRank:$TAG_RANK,search:\$search,sort:\$sort,genre_in:\$gi,genre_not_in:\$gn,tag_in:\$ti,tag_not_in:\$tn," +
        "format_in:\$f,status:\$st,episodes_greater:\$eg,episodes_lesser:\$el,startDate_greater:\$sg,startDate_lesser:\$sl,onList:\$ol,id_in:\$ids)" +
        "{id format episodes status season averageScore genres tags{name rank isMediaSpoiler isGeneralSpoiler} title{romaji native} coverImage{extraLarge} bannerImage startDate{year}}}}"

    fun variables(q: Query, page: Int, loggedIn: Boolean): JSONObject {
        val v = JSONObject().put("p", page).put("sort", JSONArray(q.sort))
        fun list(key: String, values: Collection<String>) { if (values.isNotEmpty()) v.put(key, JSONArray(values.sorted())) }
        q.search.trim().takeIf { it.isNotEmpty() }?.let { v.put("search", it) }
        list("gi", q.genreIn); list("gn", q.genreNot); list("ti", q.tagIn); list("tn", q.tagNot); list("f", q.formats)
        q.status.takeIf { it.isNotEmpty() }?.let { v.put("st", it) }
        q.episodesGreater?.let { v.put("eg", it) }
        q.episodesLesser?.let { v.put("el", it) }
        q.startGreater?.let { v.put("sg", it) }
        q.startLesser?.let { v.put("sl", it) }
        if (loggedIn) q.onList?.let { v.put("ol", it) }
        q.idIn?.let { v.put("ids", JSONArray(it)) }
        return v
    }

    suspend fun page(
        q: Query, page: Int, token: String, prefs: SharedPreferences,
        ask: suspend (String, JSONObject) -> JSONObject = { t, v -> AniList.gqlCancellable(t, QUERY, v) },
        kitsu: suspend (List<Int>, SharedPreferences) -> Map<Int, String> = AniList::kitsuIds,
    ): Page = withContext(Dispatchers.IO) {
        var expired = false
        val data = try {
            ask(token, variables(q, page, token.isNotEmpty()))
        } catch (e: AniList.LoggedOut) {
            expired = true
            ask("", variables(q, page, false))
        }.getJSONObject("Page")
        val media = data.getJSONArray("media").objects()
        val info = data.optJSONObject("pageInfo")
        val items = AniListLists.parse(media.map { JSONObject().put("media", it) }, kitsu(media.map { it.getInt("id") }, prefs))
        Page(items, info?.optInt("total") ?: items.size, info?.optBoolean("hasNextPage") ?: false, expired)
    }
}
