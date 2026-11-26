package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.Stable
import io.github.pedrubik2000.kumapie.data.BestForYou
import io.github.pedrubik2000.kumapie.data.ContinueHidden
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.levelLine
import io.github.pedrubik2000.kumapie.data.shortTitle
import io.github.pedrubik2000.kumapie.data.shown
import io.github.pedrubik2000.kumapie.i18n.tr

internal fun bestItems(all: List<Mine>): List<BestForYou.Item> =
    all.map { BestForYou.Item(it.anilistId, true, it.status, it.score, "", it.average.takeIf { a -> a > 0 }, it.genres) }

fun planningOrder(all: List<Mine>): List<Mine> {
    val byId = all.associateBy { it.anilistId }
    return BestForYou.planning(bestItems(all)).mapNotNull { byId[it] }
}

@Stable
class HomeData(
    val going: List<Pair<Show, Episode>>, val all: List<Mine>, val loggedIn: Boolean,
    val library: List<Pair<String, List<Show>>>, private val metaIds: Map<String, Set<String>> = emptyMap(),
    val now: Long = System.currentTimeMillis() / 1000,
) {
    fun mineFor(s: Show, e: Episode): Mine? = byKitsu[s.kitsuId()]?.let { m ->
        if ((e.number ?: 0) > (m.episodes ?: Int.MAX_VALUE)) m.copy(episodes = metaIds[s.id]?.size?.takeIf { it >= (e.number ?: 0) }) else m
    }

    val byKitsu: Map<String, Mine> = all.associateBy { it.kitsuId }
    val soon: Map<String, Long> = going.mapNotNull { (s, e) -> ContinueHidden.airsAt(ContinueHidden.mineOf(byKitsu, s), s, e, now)?.let { s.id to it } }.toMap()
    val soonFrom: Int = going.indexOfFirst { it.first.id in soon }
    val watching: List<Mine> = if (loggedIn) all.filter { it.watching } else emptyList()
    val planning: List<Mine> = if (loggedIn) planningOrder(all) else emptyList()
    val names: List<String> = listOfNotNull(
        tr("Continue watching").takeIf { going.isNotEmpty() },
        tr("Watching on AniList").takeIf { watching.isNotEmpty() },
        tr("Planning on AniList").takeIf { planning.isNotEmpty() },
    )
    val resume: ResumeTarget? = going.firstOrNull()?.let { (s, e) -> ResumeTarget(s, e, mineFor(s, e)) }

    val askRows: List<List<String>> = listOf(
        going.mapNotNull { it.first.kitsuId() }.takeIf { going.isNotEmpty() },
        watching.map { it.kitsuId }.takeIf { it.isNotEmpty() },
        planning.map { it.kitsuId }.takeIf { it.isNotEmpty() },
    ).filterNotNull()

    val warm: Pair<String, String> = run {
        val shelves = library.flatMap { it.second }
        val titles = going.map { shortTitle(it.first.shown) } + watching.map { it.shown() } + planning.map { it.shown() } + shelves.map { shortTitle(it.shown) }
        val lines = watching.map { it.progressLine() } + shelves.map(::levelLine)
        titles.joinToString("").toSet().joinToString("") to lines.joinToString("").toSet().joinToString("")
    }
}
