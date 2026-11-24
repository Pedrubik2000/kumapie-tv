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

internal class LibraryShown(val list: LibraryList, val filters: LibraryFilters, val view: LibraryView)

@Composable
fun LibraryScreen(api: Discovery, settings: Settings, onHome: () -> Unit, onDiscover: () -> Unit, onReview: () -> Unit, onSettings: () -> Unit,
                  onOpen: (Found) -> Unit, onStats: () -> Unit = {}, jiten: JitenQueue? = null, read: LibraryRead = AniListLists::cacheFirst) {
    val rail = remember { mutableMapOf<String, FocusRequester>() }
    val entry = remember { FocusRequester() }
    val loggedIn = settings.anilistToken.isNotEmpty()
    val back = remember { LibraryMemory.takeOpened(settings.anilist) }
    val all by rememberLists(settings, read, back != null)
    val live = remember { Live() }
    Box(live.track(Modifier.fillMaxSize())) {
        val lists = all
        when {
            !loggedIn -> LoggedOut(onSettings, entry)
            lists == null -> Text(tr("Loading…"), color = Colors.dim, modifier = Modifier.padding(start = CONTENT_START, top = 100.dp))
            else -> LibraryBody(lists, settings, entry, rail, api, jiten, live, back, onOpen)
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
private fun LibraryBody(all: List<Mine>, settings: Settings, entry: FocusRequester, rail: Map<String, FocusRequester>,
                        api: Discovery, jiten: JitenQueue?, live: Live, restore: Int?, onOpen: (Found) -> Unit) {
    var list by remember { mutableStateOf(LibraryMemory.list ?: openingList(all)) }
    LaunchedEffect(all.isNotEmpty()) { if (list == null) list = openingList(all) }
    val current = list ?: LibraryList.WATCHING
    var filters by remember { mutableStateOf(LibraryMemory.filters ?: settings.library) }
    val levels = rememberLevels(rememberQueue(jiten))
    val built = rememberBuilt(all, current, filters, levels)
    var inGrid by remember { mutableStateOf(false) }
    val display = rememberFrozen(built, inGrid)
    val items = display?.view?.items.orEmpty()
    val grid = rememberLazyGridState()
    val focus = remember(current) { LibFocus() }
    val nav = remember { GridNav() }
    var seen by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PickerKind?>(null) }
    var last by remember { mutableStateOf<PickerKind?>(null) }
    val links = remember { PickerLinks(FocusRequester(), FocusRequester()) { k -> inGrid = false; picker = k; last = k } }
    LaunchedEffect(picker == null) { last?.takeIf { picker == null }?.let { (if (it == PickerKind.GENRES) links.genres else links.tags).focusSoon(); last = null } }
    LaunchedEffect(display?.list, display?.filters) { if (display != null) { if (seen) grid.scrollToItem(0) else seen = true } }
    KeepFocus(display, restore, { d, id -> d.view.items.indexOfFirst { it.anilistId == id } }, grid, focus, nav, entry, live)
    Asks(levels, all, current, filters.needsLevels, grid, items)
    val apply: (LibraryFilters) -> Unit = { f -> filters = f; settings.library = f; LibraryMemory.filters = f }
    Box(Modifier.fillMaxSize()) {
        LibraryHero(items, focus, api, levels)
        val open = picker
        if (open != null) {
            LibraryPicker(open, { picker = it; last = it }, librarySource(current, current.of(all), filters, apply), display?.let { libraryLine(it.list, it.filters, it.view) }, items.size) { picker = null }
        } else {
            LibraryColumn(all, current, filters, entry, nav.grid.takeIf { items.isNotEmpty() }, { rail[tr("Library")] ?: FocusRequester.Default },
                { l -> if (list != null || l != LibraryList.WATCHING) { list = l; LibraryMemory.list = l } }, apply, links, Modifier.padding(start = RAIL, top = 28.dp, bottom = 16.dp))
            LibraryGrid(display, items, grid, focus, nav, entry, levels, settings, current, { inGrid = it }, onOpen)
        }
    }
}

@Composable
private fun LibraryGrid(display: LibraryShown?, items: List<Mine>, grid: LazyGridState, focus: LibFocus, nav: GridNav, entry: FocusRequester, levels: Levels?,
                        settings: Settings, current: LibraryList, onInGrid: (Boolean) -> Unit, onOpen: (Found) -> Unit) {
    Column(Modifier.padding(start = CONTENT_START, end = 28.dp, top = HERO_BOTTOM)) {
        display?.let { d ->
            Text(remember(d) { styled(libraryLine(d.list, d.filters, d.view), Colors.dim) }, fontSize = 14.sp)
            if (items.isEmpty()) Text(if (d.view.total == 0) tr("Nothing in this list yet. Pick another list on the left.") else tr("Nothing here with these filters. Change the filters on the left."), color = Colors.dim, modifier = Modifier.padding(top = 12.dp))
        }
        if (items.isNotEmpty()) CoverGrid(items, { it.anilistId }, grid,
            Modifier.focusRequester(nav.grid).onBackKey { runCatching { entry.requestFocus() } }.onFocusChanged { onInGrid(it.hasFocus) }.focusRestorer(nav.enter),
            focus, entry, nav, { FocusRequester.Cancel }) { m, card ->
            val d = display?.list ?: current
            val progress = m.episodes?.takeIf { it > 0 && m.progress > 0 && d != LibraryList.COMPLETED }?.let { m.progress.toFloat() / it }
            Poster(m.cover, m.shown(), if (m.progress > 0) m.progressLine() else "", progress, card, level = levels?.of(m.kitsuId), width = 83.dp) {
                LibraryMemory.open(m.anilistId, settings.anilist)
                onOpen(m.found())
            }
        }
    }
}

@Composable
private fun rememberBuilt(all: List<Mine>, current: LibraryList, filters: LibraryFilters, levels: Levels?): LibraryShown? {
    val needed = filters.needsLevels
    val loaded = if (needed) levels?.loaded else null
    val ready = !needed || levels != null
    val built by produceState<LibraryShown?>(null, all, current, filters, levels, loaded, ready) {
        if (!ready) return@produceState
        value = withContext(Dispatchers.Default) {
            val numbers = if (needed) levels?.snapshot(current.of(all).map { it.kitsuId }).orEmpty() else emptyMap()
            LibraryShown(current, filters, libraryView(all, current, filters) { numbers[it] })
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
