package io.github.pedrubik2000.kumapie.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun lastPlayedIds(prefs: Map<String, *>): Map<String, String> = prefs.mapNotNull { (k, v) ->
    if (!k.startsWith("alone_last_") || v !is String) null
    else runCatching { k.removePrefix("alone_last_") to JSONObject(v).getString("id") }.getOrNull()
}.toMap()

fun savedEpisodeIds(context: Context, streams: List<Pair<Show, Episode>>): Map<String, Set<String>> = streams.mapNotNull { (s, _) ->
    SavedMeta.get(context, s.id)?.optJSONArray("episodes")
        ?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") }.toSet() }?.takeIf { it.isNotEmpty() }?.let { s.id to it }
}.toMap()

fun streamsThatExist(
    streams: List<Pair<Show, Episode>>, all: List<Mine>, metaIds: Map<String, Set<String>>, lastPlayed: Map<String, String>,
): List<Pair<Show, Episode>> {
    val byKitsu = all.associateBy { it.kitsuId }
    return streams.filter { (s, e) ->
        val n = e.number
        if (e.resume != null || n == null || lastPlayed[s.id] == e.id) return@filter true
        val count = s.id.takeIf { it.startsWith("kitsu:") }?.removePrefix("kitsu:")?.let { byKitsu[it] }?.episodes?.takeIf { it > 0 }
        val meta = metaIds[s.id]
        (meta != null && e.id in meta) || count == null || n <= count
    }
}

suspend fun goingThatExist(discovery: Discovery, context: Context, prefs: android.content.SharedPreferences, anilist: String): List<Pair<Show, Episode>> {
    val going = runCatching { discovery.going() }.getOrDefault(emptyList())
    return withContext(Dispatchers.IO) {
        val lists = anilist.takeIf { it.isNotEmpty() }?.let { AniListLists.cached(prefs, it) }.orEmpty()
        streamsThatExist(going, lists, savedEpisodeIds(context, going), lastPlayedIds(prefs.all))
    }
}
