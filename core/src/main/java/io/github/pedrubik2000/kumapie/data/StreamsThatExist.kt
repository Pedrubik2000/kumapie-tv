package io.github.pedrubik2000.kumapie.data

import android.content.Context
import kotlinx.coroutines.CancellationException
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

private val CLOSED_RANGE = Regex("""(\d{4})\s*[-–—]\s*(\d{4})""")

fun metaEnded(meta: JSONObject): Boolean =
    meta.optString("status").lowercase() in setOf("ended", "finished", "completed") || CLOSED_RANGE.containsMatchIn(meta.optString("year"))

fun savedEnded(context: Context, streams: List<Pair<Show, Episode>>): Set<String> =
    streams.map { it.first.id }.distinct().filter { id -> SavedMeta.get(context, id)?.let(::metaEnded) == true }.toSet()

fun streamsThatExist(
    streams: List<Pair<Show, Episode>>, all: List<Mine>, metaIds: Map<String, Set<String>>, lastPlayed: Map<String, String>, ended: Set<String> = emptySet(),
): List<Pair<Show, Episode>> {
    val byKitsu = all.associateBy { it.kitsuId }
    return streams.filter { (s, e) ->
        val n = e.number
        if (e.resume != null || n == null || lastPlayed[s.id] == e.id) return@filter true
        val count = s.id.takeIf { it.startsWith("kitsu:") }?.removePrefix("kitsu:")?.let { byKitsu[it] }?.episodes?.takeIf { it > 0 }
        val meta = metaIds[s.id]
        when {
            meta != null && e.id in meta -> true
            count != null -> n <= count
            else -> !(meta != null && s.id in ended)
        }
    }
}

private const val LISTS_EVERY_MS = 10 * 60 * 1000L

suspend fun goingThatExist(
    discovery: Discovery, context: Context, settings: Settings, now: Long = System.currentTimeMillis(),
    refresh: suspend (String) -> List<Mine> = { AniListLists.refresh(settings.prefs, settings.anilist, it) },
    publish: (List<Pair<Show, Episode>>) -> Unit,
) {
    val prefs = settings.prefs
    val user = settings.anilist
    val going = runCatching { discovery.going() }.getOrDefault(emptyList())
    suspend fun filtered(lists: List<Mine>) = withContext(Dispatchers.IO) {
        streamsThatExist(going, lists, savedEpisodeIds(context, going), lastPlayedIds(prefs.all), savedEnded(context, going))
    }
    publish(filtered(if (user.isEmpty()) emptyList() else withContext(Dispatchers.IO) { AniListLists.cached(prefs, user) }))
    val key = "anilist_lists_at_$user"
    if (user.isEmpty() || settings.anilistToken.isEmpty() || now - prefs.getLong(key, 0L) < LISTS_EVERY_MS) return
    prefs.edit().putLong(key, now).apply()
    try { settings.withAniList(refresh)?.let { publish(filtered(it)) } } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
}
