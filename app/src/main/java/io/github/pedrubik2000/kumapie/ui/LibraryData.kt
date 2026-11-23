package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.TitleDifficulty

internal const val LIMIT_MAX = 5

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
    MINE, EASIEST, SCORE, TITLE;

    fun next(): LibrarySort = entries[(ordinal + 1) % entries.size]

    fun label(): String = when (this) {
        MINE -> tr("My order")
        EASIEST -> tr("Easiest first")
        SCORE -> tr("AniList score")
        TITLE -> tr("Title")
    }
}

data class LibraryFilters(val inProgress: Boolean = false, val onHome: Boolean = false, val limit: Int = 0, val sort: LibrarySort = LibrarySort.MINE) {
    val active: Boolean get() = inProgress || onHome || limit > 0
    val needsLevels: Boolean get() = sort == LibrarySort.EASIEST || limit > 0
}

class LibraryView(val items: List<Mine>, val total: Int, val unrated: Int)

internal object LibraryMemory {
    var list: LibraryList? = null
    var opened: Int? = null
    var account: String = ""
    var at: Long = 0
}

internal var Settings.library: LibraryFilters
    get() = LibraryFilters(prefs.getBoolean("library_in_progress", false), prefs.getBoolean("library_on_home", false),
        prefs.getInt("library_limit", 0).coerceIn(0, LIMIT_MAX), LibrarySort.entries.getOrElse(prefs.getInt("library_sort", 0)) { LibrarySort.MINE })
    set(v) = prefs.edit().putBoolean("library_in_progress", v.inProgress).putBoolean("library_on_home", v.onHome)
        .putInt("library_limit", v.limit).putInt("library_sort", v.sort.ordinal).apply()

fun limitLabel(limit: Int): String = if (limit <= 0) tr("Any") else TitleDifficulty.labelOf(limit - 1)

fun libraryView(all: List<Mine>, list: LibraryList, f: LibraryFilters, kept: Set<String>, level: (String) -> TitleDifficulty?): LibraryView {
    val inList = list.of(all)
    val checked = inList.filter { (!f.inProgress || it.progress > 0) && (!f.onHome || "kitsu:${it.kitsuId}" in kept) }
    val limited = f.limit > 0
    val unrated = if (limited) checked.count { level(it.kitsuId) == null } else 0
    val shown = if (limited) checked.filter { m -> level(m.kitsuId)?.let { it.band < f.limit } == true } else checked
    return LibraryView(sorted(shown, f.sort, level), inList.size, unrated)
}

private fun sorted(items: List<Mine>, sort: LibrarySort, level: (String) -> TitleDifficulty?): List<Mine> = when (sort) {
    LibrarySort.MINE -> items
    LibrarySort.EASIEST -> items.sortedWith(compareBy<Mine>({ level(it.kitsuId) == null }, { level(it.kitsuId)?.raw ?: 0.0 }))
    LibrarySort.SCORE -> items.sortedByDescending { it.average }
    LibrarySort.TITLE -> items.sortedBy { it.shown().lowercase() }
}

fun libraryLine(list: LibraryList, f: LibraryFilters, v: LibraryView, homeFailed: Boolean = false): String {
    if (f.onHome && homeFailed) return tr("%s · Couldn't read Home", list.label())
    val head = when {
        f.active -> tr("%1\$s · %2\$d of %3\$d shown", list.label(), v.items.size, v.total)
        v.total == 1 -> tr("%s · 1 title", list.label())
        else -> tr("%1\$s · %2\$d titles", list.label(), v.total)
    }
    return if (v.unrated > 0) head + " · " + tr("%d not rated yet", v.unrated) else head
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
