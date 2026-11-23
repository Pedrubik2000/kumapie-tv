package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.JitenQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class LibraryShown(val list: LibraryList, val filters: LibraryFilters, val view: LibraryView)

@Composable
fun LibraryScreen(api: Discovery, settings: Settings, onHome: () -> Unit, onDiscover: () -> Unit, onReview: () -> Unit, onSettings: () -> Unit,
                  onOpen: (Found) -> Unit, onStats: () -> Unit = {}, jiten: JitenQueue? = null, read: LibraryRead = AniListLists::cacheFirst) {
    val rail = remember { mutableMapOf<String, FocusRequester>() }
    val entry = remember { FocusRequester() }
    val loggedIn = settings.anilistToken.isNotEmpty()
    val back = remember { LibraryMemory.takeOpened(settings.anilist) }
    val all by rememberLists(settings, read, back != null)
    var kept by remember { mutableStateOf<Set<String>?>(null) }
    var keptFailed by remember { mutableStateOf(false) }
    val live = remember { Live() }
    LaunchedEffect(Unit) { runCatching { api.saved().map { it.id }.toSet() }.onSuccess { kept = it }.onFailure { keptFailed = true } }
    Box(live.track(Modifier.fillMaxSize())) {
        val lists = all
        when {
            !loggedIn -> LoggedOut(onSettings, entry)
            lists == null -> Text(tr("Loading…"), color = Colors.dim, modifier = Modifier.padding(start = CONTENT_START, top = 100.dp))
            else -> LibraryBody(lists, kept, keptFailed, settings, entry, rail, api, jiten, live, back, onOpen)
        }
        DiscoverRail(api, RailNav(onHome, {}, onDiscover, onReview, onStats, onSettings), RailAt.LIBRARY, focus = rail, toRows = entry.takeIf { !loggedIn || all != null })
        HintBar()
    }
}

@Composable
private fun LoggedOut(onLogin: () -> Unit, entry: FocusRequester) {
    Column(Modifier.padding(start = RAIL, top = 96.dp)) {
        Title(tr("Library"), fontSize = 26.sp)
        LoginLine(Modifier.padding(top = 16.dp), Modifier.focusRequester(entry), onLogin)
    }
    LaunchedEffect(Unit) { entry.focusSoon() }
}

private fun openingList(all: List<Mine>): LibraryList? =
    if (all.isEmpty()) null else LibraryList.WATCHING.takeIf { it.of(all).isNotEmpty() } ?: LibraryList.PLANNING

@Composable
private fun LibraryBody(all: List<Mine>, kept: Set<String>?, keptFailed: Boolean, settings: Settings, entry: FocusRequester, rail: Map<String, FocusRequester>,
                        api: Discovery, jiten: JitenQueue?, live: Live, restore: Int?, onOpen: (Found) -> Unit) {
    var list by remember { mutableStateOf(LibraryMemory.list ?: openingList(all)) }
    LaunchedEffect(all.isNotEmpty()) { if (list == null) list = openingList(all) }
    val current = list ?: LibraryList.WATCHING
    var filters by remember { mutableStateOf(settings.library) }
    val levels = rememberLevels(rememberQueue(jiten))
    val built = rememberBuilt(all, current, filters, kept, keptFailed, levels)
    var inGrid by remember { mutableStateOf(false) }
    val display = rememberFrozen(built, inGrid)
    val items = display?.view?.items.orEmpty()
    val grid = rememberLazyGridState()
    val focus = remember(current) { LibFocus() }
    val nav = remember { GridNav() }
    val sort = remember { FocusRequester() }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(display?.list, display?.filters) { if (display != null) { if (seen) grid.scrollToItem(0) else seen = true } }
    KeepFocus(display, restore, { d, id -> d.view.items.indexOfFirst { it.anilistId == id } }, grid, focus, nav, entry, live)
    Asks(levels, all, current, filters.needsLevels, grid, items)
    Box(Modifier.fillMaxSize()) {
        LibraryHero(items, focus, api, levels)
        LibraryColumn(all, current, filters, entry, nav.grid.takeIf { items.isNotEmpty() }, { rail[tr("Library")] ?: FocusRequester.Default },
            { l -> if (list != null || l != LibraryList.WATCHING) { list = l; LibraryMemory.list = l } },
            { f -> filters = f; settings.library = f }, Modifier.padding(start = RAIL, top = 28.dp))
        Column(Modifier.padding(start = CONTENT_START, end = 28.dp, top = HERO_BOTTOM)) {
            display?.let { d ->
                Text(remember(d, keptFailed) { libraryLine(d.list, d.filters, d.view, keptFailed) }, color = Colors.dim, fontSize = 14.sp)
                if (items.isEmpty()) Text(if (d.view.total == 0) tr("Nothing in this list yet. Pick another list on the left.") else tr("Nothing here with these filters. Change DIFFICULTY or SHOW ONLY on the left."), color = Colors.dim, modifier = Modifier.padding(top = 12.dp))
            }
            if (items.isNotEmpty()) CoverGrid(items, { it.anilistId }, grid,
                Modifier.focusRequester(nav.grid).onBackKey { runCatching { entry.requestFocus() } }.onFocusChanged { inGrid = it.hasFocus }.focusRestorer(nav.enter),
                focus, entry, nav, { sort }) { m, card ->
                val d = display?.list ?: current
                val progress = m.episodes?.takeIf { it > 0 && m.progress > 0 && d != LibraryList.COMPLETED }?.let { m.progress.toFloat() / it }
                Poster(m.cover, m.shown(), if (m.progress > 0) m.progressLine() else "", progress, card, level = levels?.of(m.kitsuId), width = 83.dp) {
                    LibraryMemory.open(m.anilistId, settings.anilist)
                    onOpen(m.found())
                }
            }
        }
        Button(onClick = { val f = filters.copy(sort = filters.sort.next()); filters = f; settings.library = f },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 28.dp).hint(HintKind.CHOOSE).focusRequester(sort)
                .onBackKey { runCatching { entry.requestFocus() } }
                .focusProperties { down = if (items.isEmpty()) FocusRequester.Default else nav.grid; left = entry }) { Text(tr("Sort: %s", filters.sort.label())) }
    }
}

@Composable
private fun rememberBuilt(all: List<Mine>, current: LibraryList, filters: LibraryFilters, kept: Set<String>?, keptFailed: Boolean, levels: Levels?): LibraryShown? {
    val needed = filters.needsLevels
    val loaded = if (needed) levels?.loaded else null
    val ready = (!needed || levels != null) && (!filters.onHome || kept != null || keptFailed)
    val built by produceState<LibraryShown?>(null, all, current, filters, kept, keptFailed, levels, loaded, ready) {
        if (!ready) return@produceState
        value = withContext(Dispatchers.Default) {
            val numbers = if (needed) levels?.snapshot(current.of(all).map { it.kitsuId }).orEmpty() else emptyMap()
            LibraryShown(current, filters, libraryView(all, current, filters, kept.orEmpty()) { numbers[it] })
        }
    }
    return built
}

@Composable
private fun Asks(levels: Levels?, all: List<Mine>, current: LibraryList, needed: Boolean, grid: LazyGridState, items: List<Mine>) {
    val owner = remember { Any() }
    AskVisible(levels, grid, remember(items) { items.map { it.kitsuId } })
    DisposableEffect(levels) { onDispose { levels?.release(owner) } }
    LaunchedEffect(levels, current, needed, all) {
        levels ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            levels.know(all)
            levels.drop(owner)
            if (needed) levels.ask(current.of(all).map { it.kitsuId }, false, owner)
        }
    }
}
