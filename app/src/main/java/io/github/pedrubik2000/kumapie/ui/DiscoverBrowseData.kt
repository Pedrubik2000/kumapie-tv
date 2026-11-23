package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.TitleDifficulty

private const val RETURN_WINDOW_MS = 60 * 60_000L

internal enum class DiscoverMode {
    PICK, DUEL, BROWSE;

    fun label(): String = when (this) {
        PICK -> tr("Pick for me")
        DUEL -> tr("This or that")
        BROWSE -> tr("Browse")
    }
}

internal data class BrowseFilters(val catalog: String = "", val query: String = "", val notOnList: Boolean = false, val limit: Int = 0)

internal class BrowseView(val items: List<Found>, val total: Int, val unrated: Int)

internal fun kitsuOf(f: Found): String? = f.id.takeIf { it.startsWith("kitsu:") }?.removePrefix("kitsu:")?.takeIf { it.isNotEmpty() }

internal fun browseView(rows: List<Pair<String, List<Found>>>, results: List<Found>, f: BrowseFilters, onList: Set<String>, level: (String) -> TitleDifficulty?): BrowseView {
    val source = when {
        f.query.isNotEmpty() -> results
        f.catalog.isEmpty() -> rows.flatMap { it.second }
        else -> rows.firstOrNull { it.first == f.catalog }?.second.orEmpty()
    }.distinctBy { it.id }
    val checked = source.filter { !f.notOnList || kitsuOf(it) !in onList }
    val limited = f.limit > 0
    val unrated = if (limited) checked.count { t -> kitsuOf(t)?.let(level) == null } else 0
    val shown = if (limited) checked.filter { t -> kitsuOf(t)?.let(level)?.let { it.band < f.limit } == true } else checked
    return BrowseView(shown, source.size, unrated)
}

internal fun browseLine(f: BrowseFilters, v: BrowseView): String {
    val head = tr("%1\$d of %2\$d shown", v.items.size, v.total).takeIf { f.notOnList || f.limit > 0 } ?: tr("%d titles", v.total)
    return if (v.unrated > 0) head + " · " + tr("%d not rated yet", v.unrated) else head
}

internal fun BrowseFilters.eyebrow(): String =
    (tr("Browse") + " · " + (if (query.isNotEmpty()) tr("Search") + ": " + query else catalog.ifEmpty { tr("All") })).uppercase()

internal var Settings.browseLimit: Int
    get() = prefs.getInt("discover_limit", 0).coerceIn(0, LIMIT_MAX)
    set(v) = prefs.edit().putInt("discover_limit", v).apply()

internal object DiscoverMemory {
    class Back(val mode: DiscoverMode, val cover: String?, val filters: BrowseFilters, val at: Long)

    private var back: Back? = null

    fun open(mode: DiscoverMode, cover: String?, filters: BrowseFilters, now: Long = System.currentTimeMillis()) {
        back = Back(mode, cover, filters, now)
    }

    fun take(now: Long = System.currentTimeMillis()): Back? = back?.takeIf { now - it.at < RETURN_WINDOW_MS }.also { back = null }

    fun forget() {
        back = null
    }
}
