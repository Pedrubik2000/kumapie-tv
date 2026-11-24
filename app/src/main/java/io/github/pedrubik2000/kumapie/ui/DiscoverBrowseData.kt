package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.AniListBrowse
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.TitleDifficulty

internal enum class DiscoverMode {
    PICK, DUEL, BROWSE;

    fun label(): String = when (this) {
        PICK -> tr("Pick for me")
        DUEL -> tr("This or that")
        BROWSE -> tr("Browse")
    }
}

internal enum class BrowseSort(val anilist: String) {
    POPULAR("POPULARITY_DESC"), TRENDING("TRENDING_DESC"), SCORE("SCORE_DESC"), NEWEST("START_DATE_DESC");

    fun step(by: Int): BrowseSort = entries[Math.floorMod(ordinal + by, entries.size)]

    fun label(): String = when (this) {
        POPULAR -> tr("Most popular")
        TRENDING -> tr("Trending")
        SCORE -> tr("AniList score")
        NEWEST -> tr("Newest")
    }
}

internal enum class BrowseMine {
    ANY, NOT_ON, PLANNING;

    fun step(by: Int, loggedIn: Boolean): BrowseMine = if (!loggedIn) ANY else entries[Math.floorMod(ordinal + by, entries.size)]

    fun label(): String = when (this) {
        ANY -> tr("Any")
        NOT_ON -> tr("Not on it")
        PLANNING -> tr("Only Planning")
    }
}

internal val ANILIST_GENRES = listOf("Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music", "Mystery",
    "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller")

internal data class BrowseFilters(
    val query: String = "", val sort: BrowseSort = BrowseSort.POPULAR, val genres: ChipPicks = ChipPicks(), val tags: ChipPicks = ChipPicks(),
    val format: FormatBand = FormatBand.ANY, val year: YearBand = YearBand.ANY, val airing: AiringBand = AiringBand.ANY,
    val episodes: EpisodeBand = EpisodeBand.ANY, val mine: BrowseMine = BrowseMine.ANY, val limit: Int = 0,
) {
    val needsPlanning: Boolean get() = mine == BrowseMine.PLANNING
}

internal fun kitsuOf(f: Found): String? = f.id.takeIf { it.startsWith("kitsu:") }?.removePrefix("kitsu:")?.takeIf { it.isNotEmpty() }

internal fun BrowseFilters.toQuery(planning: List<Int>): AniListBrowse.Query {
    val search = query.trim()
    val from = year.range.first.takeIf { year != YearBand.ANY && it > 1 }?.let { (it - 1) * 10_000 + 1231 }
    val to = year.range.last.takeIf { year != YearBand.ANY && it < 9999 }?.let { (it + 1) * 10_000 + 101 }
    return AniListBrowse.Query(
        search = search, sort = (if (search.isEmpty()) emptyList() else listOf("SEARCH_MATCH")) + sort.anilist,
        genreIn = genres.include, genreNot = genres.exclude, tagIn = tags.include, tagNot = tags.exclude, formats = format.formats, status = airing.status,
        episodesGreater = episodes.range.first.takeIf { episodes != EpisodeBand.ANY && it > 1 }?.minus(1)
            ?: 0.takeIf { episodes == EpisodeBand.ONE },
        episodesLesser = episodes.range.last.takeIf { episodes != EpisodeBand.ANY && it < Int.MAX_VALUE }?.plus(1),
        startGreater = from, startLesser = to, onList = false.takeIf { mine == BrowseMine.NOT_ON }, idIn = planning.takeIf { needsPlanning },
    )
}

internal class BrowseView(val items: List<Mine>, val unrated: Int)

internal fun browseView(results: List<Mine>, f: BrowseFilters, level: (String) -> TitleDifficulty?): BrowseView {
    if (f.limit <= 0) return BrowseView(results, 0)
    val unrated = results.count { level(it.kitsuId) == null }
    return BrowseView(results.filter { m -> level(m.kitsuId)?.let { it.band < f.limit } != false }, unrated)
}

internal fun browseLine(f: BrowseFilters, total: Int, v: BrowseView): LibraryLine {
    val tail = listOfNotNull(
        f.query.trim().takeIf { it.isNotEmpty() }?.let { "“$it”" }, f.format.takeIf { it != FormatBand.ANY }?.label(),
        f.episodes.takeIf { it != EpisodeBand.ANY }?.let { tr("%s episodes", it.label()) }, f.year.takeIf { it != YearBand.ANY }?.label(),
        f.airing.takeIf { it != AiringBand.ANY }?.label()?.lowercase(),
        when (f.mine) { BrowseMine.ANY -> null; BrowseMine.NOT_ON -> tr("not on my list"); BrowseMine.PLANNING -> tr("only my Planning") },
        f.limit.takeIf { it > 0 }?.let { tr("up to %s", limitLabel(it)) },
        v.unrated.takeIf { it > 0 }?.let { tr("%d not rated yet", it) },
    ).joinToString(" · ")
    val out = (f.genres.exclude.sorted() + f.tags.exclude.sorted()).joinToString(", ")
    return LibraryLine(tr("%d on AniList", total), (f.genres.include.sorted() + f.tags.include.sorted()).joinToString(", "), if (out.isEmpty()) "" else tr("no %s", out), tail)
}

internal fun BrowseFilters.eyebrow(): String =
    (tr("Browse") + " · " + (if (query.isNotBlank()) tr("Search") + ": " + query.trim() else tr("All anime"))).uppercase()

internal var Settings.browseLimit: Int
    get() = prefs.getInt("discover_limit", 0).coerceIn(0, LIMIT_MAX)
    set(v) = prefs.edit().putInt("discover_limit", v).apply()

internal fun browseActive(f: BrowseFilters): List<String> = listOfNotNull(
    tr("Search").takeIf { f.query.isNotBlank() }, tr("Genres").takeIf { f.genres.any }, tr("Tags").takeIf { f.tags.any }, tr("Format").takeIf { f.format != FormatBand.ANY },
    tr("Year").takeIf { f.year != YearBand.ANY }, tr("Airing").takeIf { f.airing != AiringBand.ANY }, tr("Episodes").takeIf { f.episodes != EpisodeBand.ANY },
    tr("My list").takeIf { f.mine != BrowseMine.ANY }, tr("Difficulty").takeIf { f.limit > 0 },
)
