package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr

// A show's level on a Home card, the phone's and the TV's (user, 2026-10-14).

/** The episodes not finished yet. */
fun ahead(s: Show) = s.episodes.filterNot { it.watched }

/**
 * Of the scenes ahead, the share at i+0 / i+1 (the PC's count, [io.github.pedrubik2000.kumapie.data.Episode.easy]);
 * null when no episode ahead has a count (made on a device and not synced yet, or no known words sent).
 */
fun easyPercent(s: Show): Int? = ahead(s).filter { it.easy > 0 && it.scenes > 0 }.takeIf { it.isNotEmpty() }
    ?.let { eps -> eps.sumOf { it.easy } * 100 / eps.sumOf { it.scenes } }

/** A show card's second line: "62% easy · 9 left", "9 left" without a count, "Finished" (user, 2026-10-14). */
fun levelLine(s: Show): String {
    val left = ahead(s).size
    if (left == 0) return tr("Finished")
    return easyPercent(s)?.let { tr("%1\$d%% easy · %2\$d left", it, left) } ?: tr("%d left", left)
}

/**
 * Home's "Continue" row: one card per show, its episode left partway most recently; newest first (user, 2026-10-14:
 * BOFURI filled 4 of 6 TV cards). Under 2 minutes left counts as watched (credits). Shorts are feed-only.
 * When the episode played last is finished, the next one not watched instead, from the start (resume null; user,
 * 2026-11-16). [streams]: streamed titles' cards ([Api.going]), already picked by the PC.
 */
fun continueRow(shows: List<Show>, streams: List<Pair<Show, Episode>> = emptyList()): List<Pair<Show, Episode>> =
    (shows.filter { it.kind != "reels" }.mapNotNull { s ->
        val partway = s.episodes.filter { !it.watched && partway(it.resume, it.duration) }.maxByOrNull { it.resumedAt }
        val last = s.episodes.filter { it.resumedAt > 0 }.maxByOrNull { it.resumedAt }
        val done = last != null && last !== partway && finished(last.watched, last.resume, last.duration)
        val next = if (done) s.episodes.drop(s.episodes.indexOf(last) + 1).firstOrNull { !it.watched } else null
        // The next one from the start, unless it was left partway itself.
        (next?.let { n -> n.copy(resume = n.resume?.takeIf { partway(it, n.duration) }, resumedAt = last!!.resumedAt) } ?: partway)
            ?.let { s to it }
    } + streams).sortedByDescending { it.second.resumedAt }

fun streamingRow(saved: List<Found>, playedAt: Map<String, Long>): List<Found> = saved.sortedByDescending { playedAt[it.id] ?: 0L }

const val RESUME_AFTER_S = 20.0

fun partway(at: Double?, duration: Double) = at != null && duration > 0 && at > RESUME_AFTER_S && duration - at > 120

fun finished(watched: Boolean, at: Double?, duration: Double) = watched || at != null && duration > 0 && duration - at <= 120

data class Watching(val id: String, val watched: Boolean, val resume: Double?, val duration: Double, val resumedAt: Long)

fun <T> nextUp(eps: List<T>, going: String?, w: (T) -> Watching): T? {
    val all = eps.map { it to w(it) }
    val at = all.indexOfFirst { it.second.id == going }
    val here = all.getOrNull(at)?.takeIf { !it.second.watched }
    val after = if (at >= 0 && here == null) all.drop(at + 1).firstOrNull { !it.second.watched } else null
    return (here ?: after
        ?: all.filter { !it.second.watched && partway(it.second.resume, it.second.duration) }.maxByOrNull { it.second.resumedAt }
        ?: all.firstOrNull { (_, e) -> !finished(e.watched, e.resume, e.duration) })?.first ?: eps.firstOrNull()
}

fun nextUp(eps: List<Episode>, going: String?): Episode? = nextUp(eps, going) { Watching(it.id, it.watched, it.resume, it.duration, it.resumedAt) }

@JvmName("nextUpJson")
fun nextUp(eps: List<org.json.JSONObject>, going: String?): org.json.JSONObject? =
    nextUp(eps, going) { Watching(it.optString("id"), it.optBoolean("watched"), null, 0.0, 0) }

/** Home's categories, in this order (the PC's library folders). Reels (YouTube shorts made on a phone) are feed-only. */
val KINDS = listOf("anime" to "Anime", "shows" to "Shows", "movies" to "Movies", "youtube" to "YouTube", "visual_novels" to "Visual novels")

/**
 * Home's library, the TV's rows and the phone's tabs: each of [KINDS] then "Other" (labels in English: tr them), the
 * shows easiest first, finished last, no easy count yet (not synced) between them. Empty ones kept (the TV counts rows).
 */
fun libraryRows(shows: List<Show>): List<Pair<String, List<Show>>> {
    val easiest = compareBy<Show>({ ahead(it).isEmpty() }, { -(easyPercent(it) ?: -1) })
    val other = shows.filter { s -> s.kind != "reels" && KINDS.none { it.first == s.kind } }
    return KINDS.map { (k, label) -> label to shows.filter { it.kind == k }.sortedWith(easiest) } + ("Other" to other.sortedWith(easiest))
}

/** Watch time as Stats and Home's cards say it: "45 min", "2 h", "11 h 36 min" (the TV's and the phone's). */
fun hoursMinutes(seconds: Double): String {
    val m = (seconds / 60).toInt()
    return when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}

/** A streamed card's title for the Discover page ([Show.kind] "stream"). */
fun streamFound(s: Show, e: Episode) = Found(s.id, if (e.number == null) "movie" else "series", s.title, s.poster, "")

/** A streamed card's episode to play (kumapie://watch's query; the PC's prepared file). */
fun streamQuery(s: Show, e: Episode) = mapOf("lang" to "ja", "type" to streamFound(s, e).type, "id" to e.id, "show" to s.title,
    "title" to (e.number?.let { "${s.title} $it" } ?: s.title))

/** A title cut at its subtitle for one-line places (the TV): "BOFURI: I Don't Want to Get Hurt…" -> "BOFURI". */
fun shortTitle(title: String): String = title.substringBefore(": ").ifBlank { title }

/** The title to show: the Japanese one where known (user, 2026-11-17). */
val Show.shown: String get() = titleJa.ifEmpty { title }

/** Home's resume card: "14 min left · 61% easy" (the episode's share of i+0 / i+1 scenes, when the PC counted it). */
fun resumeLine(e: Episode): String {
    val left = when {
        e.resume == null -> tr("Next episode")
        e.duration <= 0 -> tr("At %d min", (e.resume / 60).toInt().coerceAtLeast(1)) // length not known yet (no PC, copied from the PC)
        else -> tr("%d min left", ((e.duration - e.resume) / 60).toInt().coerceAtLeast(1))
    }
    val easy = if (e.easy > 0 && e.scenes > 0) e.easy * 100 / e.scenes else null
    return easy?.let { "$left · " + tr("%d%% easy", it) } ?: left
}

sealed interface Route {
    data object Player : Route
    data class Watch(val query: Map<String, String>) : Route
    data class Discover(val found: Found) : Route
}

fun route(show: Show, episode: Episode, hasPc: Boolean): Route = when {
    show.kind != "stream" -> Route.Player
    hasPc && episode.ready -> Route.Watch(streamQuery(show, episode))
    else -> Route.Discover(streamFound(show, episode))
}
