package io.github.pedrubik2000.kumapie.ui

import android.content.SharedPreferences
import io.github.pedrubik2000.kumapie.data.BestForYou
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlin.math.floor

private const val NEVER = "discover_never"
private const val SKIPPED = "discover_skipped"
private const val MAX_NEVER = 500
private const val MAX_SKIPPED = 200
internal const val SKIP_DAYS = 7
private const val DAY_MS = 24 * 60 * 60_000L

internal class PickStore(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {
    private fun nevers(): List<Int> = prefs.getString(NEVER, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    private fun skips(): List<Pair<Int, Long>> = prefs.getString(SKIPPED, "").orEmpty().split(',').mapNotNull { p ->
        p.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to (it.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null) }
    }.filter { clock() - it.second < SKIP_DAYS * DAY_MS && it.second - clock() <= SKIP_DAYS * DAY_MS }

    fun never(): Set<Int> = nevers().toSet()

    fun hidden(): Set<Int> = never() + skips().map { it.first }

    fun notForMe(id: Int) {
        prefs.edit().putString(NEVER, (nevers() - id + id).takeLast(MAX_NEVER).joinToString(",")).apply()
    }

    fun skip(id: Int) {
        val kept = (skips().filter { it.first != id } + (id to clock())).takeLast(MAX_SKIPPED)
        prefs.edit().putString(SKIPPED, kept.joinToString(",") { "${it.first}:${it.second}" }).apply()
    }
}

internal fun candidates(ranked: List<Mine>, hidden: Set<Int>, onHome: Set<String>): List<Mine> =
    ranked.filter { it.anilistId !in hidden && "kitsu:${it.kitsuId}" !in onHome }

internal fun scaleTo10(format: String): Double? = when (format) {
    "POINT_100" -> 0.1
    "POINT_10", "POINT_10_DECIMAL" -> 1.0
    "POINT_5" -> 2.0
    else -> null
}

internal fun whyLines(m: Mine, why: List<BestForYou.Why>, tenth: Double?): List<String> {
    val taste = why.take(2).mapNotNull { w ->
        val average = w.average * (tenth ?: 0.0)
        when {
            tenth != null && w.scored >= 2 && average >= 7 -> tr("You score %1\$s %2\$d+ on average", w.feature, floor(average).toInt())
            w.finished >= 2 -> tr("You finished %1\$d titles with %2\$s", w.finished, w.feature)
            else -> null
        }
    }
    val facts = listOfNotNull(
        m.average.takeIf { it >= 75 }?.let { tr("AniList %d", it) },
        m.episodes?.takeIf { it in 2..13 }?.let { tr("Short: %d episodes", it) },
    )
    return (taste + facts).take(3)
}

private fun formatName(format: String): String? = when (format) {
    "TV", "OVA", "ONA" -> format
    "MOVIE" -> tr("Movie")
    else -> null
}

internal fun pickFacts(m: Mine): String = factLine(formatName(m.format), ListTarget(m).facts(), m.average.takeIf { it > 0 }?.let { tr("AniList %d", it) })

internal fun duelFacts(m: Mine): String =
    factLine(m.episodes?.let { tr("%d ep", it) }, m.year.takeIf { it > 0 }?.toString(), m.average.takeIf { it > 0 }?.let { tr("AniList %d", it) })
