package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.graphics.Color
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.LangTotal
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.hoursMinutes
import io.github.pedrubik2000.kumapie.data.resumeLine
import io.github.pedrubik2000.kumapie.data.shortTitle
import io.github.pedrubik2000.kumapie.data.shown
import io.github.pedrubik2000.kumapie.i18n.tr

data class StripChip(val text: String, val current: Boolean)

fun episodeStrip(number: Int?, left: String, total: Int?): List<StripChip> {
    val n = number ?: return emptyList()
    val before = if (n > 1) listOf(StripChip(tr("Ep %d · watched", n - 1), false)) else emptyList()
    val after = (n + 1..n + 3).filter { total == null || it <= total }.map { StripChip(tr("Ep %d", it), false) }
    return before + StripChip(tr("Ep %d", n) + " · " + left, true) + after
}

class RowNav(val position: String, val up: String?, val down: String?)

fun rowNav(names: List<String>, at: Int): RowNav? =
    if (names.size < 2) null else RowNav(tr("%1\$d of %2\$d", at + 1, names.size), names.getOrNull(at - 1), names.getOrNull(at + 1))

fun statsLine(s: LangTotal): String = listOf(
    hoursMinutes(s.today) + " " + tr("Today").lowercase(),
    hoursMinutes(s.week) + " " + tr("Last 7 days").lowercase(),
    (if (s.total < 36000) hoursMinutes(s.total) else "${"%.1f".format(s.total / 3600)} h") + " " + tr("In total").lowercase(),
    "%,d".format(s.known) + " " + tr("Words known").lowercase(),
).joinToString(" · ")

fun factLine(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

fun episodesText(total: Int?): String? = total?.takeIf { it > 1 }?.let { tr("%d episodes", it) }

fun leftText(e: Episode): String = resumeLine(e).substringBefore(" · ")

fun Mine.shown(): String = native.ifEmpty { romaji }

fun Mine.progressLine(): String = episodes?.let { tr("Ep %1\$d of %2\$d", progress, it) } ?: tr("Ep %d", progress)

fun Mine.statusName(): String = when (status) {
    "REPEATING" -> tr("Rewatching")
    "PLANNING" -> tr("Planning")
    else -> tr("Watching")
}

fun Mine.found() = Found("kitsu:$kitsuId", "anime", shown(), cover, year.takeIf { it > 0 }?.toString().orEmpty(), banner, "", "",
    genres.joinToString(", "), average.takeIf { it > 0 }?.let { "%.1f".format(it / 10.0) }.orEmpty(), "", "")

fun Show.kitsuId(): String? = id.takeIf { it.startsWith("kitsu:") }?.removePrefix("kitsu:")?.takeIf { it.isNotEmpty() }

sealed interface HeroTarget {
    val key: String
}

data class ResumeTarget(val show: Show, val episode: Episode, val mine: Mine?) : HeroTarget {
    override val key get() = show.id
}

data class ContinueTarget(val show: Show, val episode: Episode, val index: Int, val of: Int, val mine: Mine?) : HeroTarget {
    override val key get() = show.id
}

data class ListTarget(val mine: Mine) : HeroTarget {
    override val key get() = "kitsu:${mine.kitsuId}"
}

data class FoundTarget(val found: Found) : HeroTarget {
    override val key get() = found.id
}

fun HeroTarget.kitsu(): String? = when (this) {
    is ResumeTarget -> show.kitsuId()
    is ContinueTarget -> show.kitsuId()
    is ListTarget -> mine.kitsuId
    is FoundTarget -> kitsuOf(found)
}

fun ContinueTarget.facts(): String = factLine(
    episodesText(mine?.episodes ?: show.episodes.size), (mine?.year?.takeIf { it > 0 } ?: show.year)?.toString(),
    mine?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", "),
    if (show.lang == "ja" && (episode.scenes > 0 || episode.ready)) "✓ " + tr("Japanese subtitles") else null,
)

fun ListTarget.facts(): String = factLine(
    episodesText(mine.episodes), mine.year.takeIf { it > 0 }?.toString(), mine.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
)

fun ListTarget.chips(): List<String> = listOfNotNull(
    if (mine.watching) tr("On AniList: %s", mine.statusName()) else null,
    if (mine.watching) mine.progressLine() else null,
    mine.average.takeIf { it > 0 }?.let { tr("AniList %d", it) },
)

fun HeroTarget.eyebrow(): String = when (this) {
    is ResumeTarget -> tr("Continue watching")
    is ContinueTarget -> tr("Continue watching") + " · " + tr("%1\$d of %2\$d", index + 1, of)
    is ListTarget -> if (mine.watching) tr("Watching on AniList") else tr("Planning on AniList") + " · " + tr("Not started")
    is FoundTarget -> ""
}.uppercase()

fun HeroTarget.title(): String = when (this) {
    is ResumeTarget -> shortTitle(show.shown)
    is ContinueTarget -> shortTitle(show.shown)
    is ListTarget -> mine.shown()
    is FoundTarget -> found.shownName
}

fun bandColour(percent: Int): Color = when {
    percent < 40 -> DifficultyColours.blue
    percent < 60 -> DifficultyColours.green
    percent < 75 -> DifficultyColours.lime
    percent < 85 -> DifficultyColours.orange
    percent < 95 -> DifficultyColours.red
    else -> DifficultyColours.purple
}

fun HeroTarget.fallback(): String = when (this) {
    is ResumeTarget -> episode.thumb.ifEmpty { show.poster }
    is ContinueTarget -> episode.thumb.ifEmpty { show.poster }
    is ListTarget -> mine.banner.ifEmpty { mine.cover }
    is FoundTarget -> found.background.ifEmpty { found.poster }
}

fun HeroTarget.type(): String = when {
    this is ResumeTarget && episode.id == show.id -> "movie"
    this is FoundTarget -> found.type
    else -> "series"
}
