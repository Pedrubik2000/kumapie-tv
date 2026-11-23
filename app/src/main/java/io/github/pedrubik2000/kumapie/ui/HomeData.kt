package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.Stable
import io.github.pedrubik2000.kumapie.data.BestForYou
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.levelLine
import io.github.pedrubik2000.kumapie.data.shortTitle
import io.github.pedrubik2000.kumapie.data.shown
import io.github.pedrubik2000.kumapie.i18n.tr

fun planningOrder(all: List<Mine>): List<Mine> {
    val byId = all.associateBy { it.anilistId }
    val items = all.map { BestForYou.Item(it.anilistId, true, it.status, it.score, "", it.average.takeIf { a -> a > 0 }, it.genres) }
    return BestForYou.planning(items).mapNotNull { byId[it] }
}

@Stable
class HomeData(
    val going: List<Pair<Show, Episode>>, val all: List<Mine>, val loggedIn: Boolean,
    val library: List<Pair<String, List<Show>>>,
) {
    val byKitsu: Map<String, Mine> = all.associateBy { it.kitsuId }
    val watching: List<Mine> = if (loggedIn) all.filter { it.watching } else emptyList()
    val planning: List<Mine> = if (loggedIn) planningOrder(all) else emptyList()
    val names: List<String> = listOfNotNull(
        tr("Continue watching").takeIf { going.isNotEmpty() },
        tr("Watching on AniList").takeIf { watching.isNotEmpty() },
        tr("Planning on AniList").takeIf { planning.isNotEmpty() },
    )
    val resume: ResumeTarget? = going.firstOrNull()?.let { (s, e) -> ResumeTarget(s, e, byKitsu[s.kitsuId()]) }

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
