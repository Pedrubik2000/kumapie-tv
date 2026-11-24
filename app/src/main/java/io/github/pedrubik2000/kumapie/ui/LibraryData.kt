package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.TitleDifficulty

internal const val LIMIT_MAX = 5
private const val RETURN_WINDOW_MS = 60 * 60_000L

enum class LibraryList(private val statuses: Set<String>) {
    WATCHING(setOf("CURRENT", "REPEATING")), PLANNING(setOf("PLANNING")), COMPLETED(setOf("COMPLETED")),
    PAUSED(setOf("PAUSED")), DROPPED(setOf("DROPPED"));

    fun of(all: List<Mine>): List<Mine> = all.filter { it.status in statuses }

    fun label(): String = when (this) {
        WATCHING -> tr("Watching")
        PLANNING -> tr("Planning")
        COMPLETED -> tr("Completed")
        PAUSED -> tr("Paused")
        DROPPED -> tr("Dropped")
    }
}

enum class LibrarySort {
    MINE, EASIEST, SCORE, TITLE, YEAR;

    fun step(by: Int): LibrarySort = entries[Math.floorMod(ordinal + by, entries.size)]

    fun label(): String = when (this) {
        MINE -> tr("My order")
        EASIEST -> tr("Easiest first")
        SCORE -> tr("AniList score")
        TITLE -> tr("Title")
        YEAR -> tr("Year")
    }
}

enum class FormatBand(val formats: Set<String>) {
    ANY(emptySet()), TV(setOf("TV")), MOVIE(setOf("MOVIE")), OVA_ONA(setOf("OVA", "ONA", "SPECIAL")), SHORT(setOf("TV_SHORT"));

    fun step(by: Int): FormatBand = entries[Math.floorMod(ordinal + by, entries.size)]

    fun matches(m: Mine): Boolean = this == ANY || m.format in formats

    fun label(): String = when (this) {
        ANY -> tr("Any")
        TV -> "TV"
        MOVIE -> tr("Movie")
        OVA_ONA -> "OVA · ONA"
        SHORT -> tr("Short")
    }
}

enum class EpisodeBand(val range: IntRange) {
    ANY(0..0), ONE(1..1), TWO_TO_13(2..13), FOURTEEN_TO_26(14..26), MORE(27..Int.MAX_VALUE);

    fun step(by: Int): EpisodeBand = entries[Math.floorMod(ordinal + by, entries.size)]

    fun matches(m: Mine): Boolean = this == ANY || m.episodes == null || m.episodes in range

    fun label(): String = when (this) {
        ANY -> tr("Any")
        ONE -> "1"
        TWO_TO_13 -> "2–13"
        FOURTEEN_TO_26 -> "14–26"
        MORE -> "27+"
    }
}

enum class YearBand(val range: IntRange) {
    ANY(0..0), NEW(2020..9999), TEENS(2010..2019), NOUGHTS(2000..2009), OLD(1..1999);

    fun step(by: Int): YearBand = entries[Math.floorMod(ordinal + by, entries.size)]

    fun matches(m: Mine): Boolean = this == ANY || m.year == 0 || m.year in range

    fun label(): String = when (this) {
        ANY -> tr("Any")
        NEW -> "2020+"
        TEENS -> "2010–2019"
        NOUGHTS -> "2000–2009"
        OLD -> tr("Before 2000")
    }
}

enum class AiringBand(val status: String) {
    ANY(""), FINISHED("FINISHED"), AIRING("RELEASING"), NOT_YET("NOT_YET_RELEASED");

    fun step(by: Int): AiringBand = entries[Math.floorMod(ordinal + by, entries.size)]

    fun matches(m: Mine): Boolean = this == ANY || m.airing.isEmpty() || m.airing == status

    fun label(): String = when (this) {
        ANY -> tr("Any")
        FINISHED -> tr("Finished")
        AIRING -> tr("Airing")
        NOT_YET -> tr("Not yet")
    }
}

data class ChipPicks(val include: Set<String> = emptySet(), val exclude: Set<String> = emptySet()) {
    val any: Boolean get() = include.isNotEmpty() || exclude.isNotEmpty()

    fun cycle(name: String): ChipPicks = when (name) {
        in include -> ChipPicks(include - name, exclude + name)
        in exclude -> ChipPicks(include, exclude - name)
        else -> ChipPicks(include + name, exclude)
    }

    fun allows(have: List<String>): Boolean = include.all { it in have } && exclude.none { it in have }
}

data class LibraryFilters(
    val inProgress: Boolean = false, val limit: Int = 0, val sort: LibrarySort = LibrarySort.MINE, val query: String = "",
    val genres: ChipPicks = ChipPicks(), val tags: ChipPicks = ChipPicks(), val format: FormatBand = FormatBand.ANY, val episodes: EpisodeBand = EpisodeBand.ANY,
    val year: YearBand = YearBand.ANY, val airing: AiringBand = AiringBand.ANY,
) {
    val active: Boolean get() = inProgress || limit > 0 || query.isNotBlank() || genres.any || tags.any || format != FormatBand.ANY ||
        episodes != EpisodeBand.ANY || year != YearBand.ANY || airing != AiringBand.ANY
    val needsLevels: Boolean get() = sort == LibrarySort.EASIEST || limit > 0

    fun matches(m: Mine): Boolean = (!inProgress || m.progress > 0) && matchesQuery(m) && genres.allows(m.genres) && tags.allows(m.tags) &&
        format.matches(m) && episodes.matches(m) && year.matches(m) && airing.matches(m)

    private fun matchesQuery(m: Mine): Boolean {
        val q = query.trim().lowercase()
        return q.isEmpty() || m.native.lowercase().contains(q) || m.romaji.lowercase().contains(q)
    }
}

class LibraryView(val items: List<Mine>, val total: Int, val unrated: Int)

internal object LibraryMemory {
    var list: LibraryList? = null
    var account: String = ""
    var at: Long = 0
    var filters: LibraryFilters? = null
    private var opened: Int? = null
    private var openedFor = ""
    private var openedAt = 0L

    fun open(id: Int, user: String, now: Long = System.currentTimeMillis()) {
        opened = id
        openedFor = user
        openedAt = now
    }

    fun takeOpened(user: String, now: Long = System.currentTimeMillis()): Int? =
        opened?.takeIf { openedFor == user && now - openedAt < RETURN_WINDOW_MS }.also { opened = null }

    fun forget() {
        list = null
        account = ""
        at = 0
        filters = null
        opened = null
    }
}

internal var Settings.library: LibraryFilters
    get() = LibraryFilters(inProgress = prefs.getBoolean("library_in_progress", false), limit = prefs.getInt("library_limit", 0).coerceIn(0, LIMIT_MAX),
        sort = LibrarySort.entries.getOrElse(prefs.getInt("library_sort", 0)) { LibrarySort.MINE })
    set(v) = prefs.edit().putBoolean("library_in_progress", v.inProgress).putInt("library_limit", v.limit).putInt("library_sort", v.sort.ordinal).apply()

fun limitLabel(limit: Int): String = if (limit <= 0) tr("Any") else TitleDifficulty.labelOf(limit - 1)

fun libraryView(all: List<Mine>, list: LibraryList, f: LibraryFilters, level: (String) -> TitleDifficulty?): LibraryView {
    val inList = list.of(all)
    val checked = inList.filter(f::matches)
    val limited = f.limit > 0
    val unrated = if (limited) checked.count { level(it.kitsuId) == null } else 0
    val shown = if (limited) checked.filter { m -> level(m.kitsuId)?.let { it.band < f.limit } != false } else checked
    return LibraryView(sorted(shown, f.sort, level), inList.size, unrated)
}

private fun sorted(items: List<Mine>, sort: LibrarySort, level: (String) -> TitleDifficulty?): List<Mine> = when (sort) {
    LibrarySort.MINE -> items
    LibrarySort.EASIEST -> items.sortedWith(compareBy<Mine>({ level(it.kitsuId) == null }, { level(it.kitsuId)?.raw ?: 0.0 }))
    LibrarySort.SCORE -> items.sortedByDescending { it.average }
    LibrarySort.TITLE -> items.sortedBy { it.shown().lowercase() }
    LibrarySort.YEAR -> items.sortedWith(compareBy<Mine>({ it.year == 0 }, { -it.year }))
}

class LibraryLine(val head: String, val include: String, val exclude: String, val tail: String = "") {
    override fun toString(): String = listOf(head, include, exclude, tail).filter { it.isNotEmpty() }.joinToString(" · ")
}

fun libraryLine(list: LibraryList, f: LibraryFilters, v: LibraryView): LibraryLine {
    val count = when {
        f.active -> tr("%1\$s · %2\$d of %3\$d shown", list.label(), v.items.size, v.total)
        v.total == 1 -> tr("%s · 1 title", list.label())
        else -> tr("%1\$s · %2\$d titles", list.label(), v.total)
    }
    val head = if (v.unrated > 0) count + " · " + tr("%d not rated yet", v.unrated) else count
    val out = (f.genres.exclude.sorted() + f.tags.exclude.sorted()).joinToString(", ")
    return LibraryLine(head, (f.genres.include.sorted() + f.tags.include.sorted()).joinToString(", "), if (out.isEmpty()) "" else tr("no %s", out))
}

fun libraryStatus(m: Mine): String {
    val name = when (m.status) {
        "CURRENT" -> tr("Watching")
        "REPEATING" -> tr("Rewatching")
        "PLANNING" -> tr("Planning")
        "COMPLETED" -> tr("Completed")
        "PAUSED" -> tr("Paused")
        else -> tr("Dropped")
    }
    val part = when {
        m.status == "COMPLETED" -> null
        m.progress <= 0 -> tr("Not started")
        m.episodes != null -> "${m.progress} / ${m.episodes}"
        else -> tr("Ep %d", m.progress)
    }
    return factLine(name, part).uppercase()
}
