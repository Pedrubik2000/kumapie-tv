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
 */
fun continueRow(shows: List<Show>): List<Pair<Show, Episode>> = shows.filter { it.kind != "reels" }.mapNotNull { s ->
    s.episodes.filter { e ->
        val at = e.resume ?: 0.0
        !e.watched && e.duration > 0 && at > 20 && e.duration - at > 120
    }.maxByOrNull { it.resumedAt }?.let { s to it }
}.sortedByDescending { it.second.resumedAt }
