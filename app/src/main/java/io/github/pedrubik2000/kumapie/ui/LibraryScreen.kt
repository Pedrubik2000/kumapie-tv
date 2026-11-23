package io.github.pedrubik2000.kumapie.ui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

typealias LibraryRead = (SharedPreferences, String, String, CoroutineScope, (List<Mine>) -> Unit) -> List<Mine>

private const val COLUMNS = 6
private const val REFRESH_EVERY_MS = 10 * 60_000L
private const val RESTORE_WAIT_MS = 3_000L

private class Shown(val list: LibraryList, val filters: LibraryFilters, val view: LibraryView)

private class Live(val focusIn: State<Boolean>, val touched: State<Boolean>)

@Composable
fun LibraryScreen(api: Discovery, settings: Settings, onHome: () -> Unit, onDiscover: () -> Unit, onReview: () -> Unit, onSettings: () -> Unit,
                  onOpen: (Found) -> Unit, jiten: JitenQueue? = null, read: LibraryRead = AniListLists::cacheFirst) {
    val rail = remember { mutableMapOf<String, FocusRequester>() }
    val entry = remember { FocusRequester() }
    val loggedIn = settings.anilistToken.isNotEmpty()
    val user = settings.anilist
    val scope = rememberCoroutineScope()
    val returning = remember { LibraryMemory.opened != null }
    var all by remember { mutableStateOf<List<Mine>?>(null) }
    var kept by remember { mutableStateOf<Set<String>?>(null) }
    var keptFailed by remember { mutableStateOf(false) }
    val focusIn = remember { mutableStateOf(false) }
    val touched = remember { mutableStateOf(false) }
    LaunchedEffect(user, loggedIn) {
        all = if (!loggedIn || user.isEmpty()) emptyList()
        else withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!returning && (LibraryMemory.account != user || now - LibraryMemory.at >= REFRESH_EVERY_MS)) {
                LibraryMemory.account = user
                LibraryMemory.at = now
                read(settings.prefs, user, settings.anilistToken, scope) { all = it }
            } else AniListLists.cached(settings.prefs, user)
        }
    }
    LaunchedEffect(Unit) { runCatching { api.saved().map { it.id }.toSet() }.onSuccess { kept = it }.onFailure { keptFailed = true } }
    Box(Modifier.fillMaxSize().onFocusChanged { focusIn.value = it.hasFocus }.onPreviewKeyEvent { touched.value = true; false }) {
        val lists = all
        when {
            !loggedIn -> LoggedOut(onSettings, entry)
            lists == null -> Text(tr("Loading…"), color = Colors.dim, modifier = Modifier.padding(start = CONTENT_START, top = 100.dp))
            else -> LibraryBody(lists, kept, keptFailed, settings, entry, rail, api, jiten, Live(focusIn, touched), onOpen)
        }
        DiscoverRail(onHome, onReview, onSettings, onDiscover = onDiscover, library = true, focus = rail, toRows = entry.takeIf { !loggedIn || all != null })
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

private class Nav {
    val grid = FocusRequester()
    val enter = FocusRequester()
    val sort = FocusRequester()
    var target by mutableStateOf<Int?>(null)
}

@Composable
private fun LibraryBody(all: List<Mine>, kept: Set<String>?, keptFailed: Boolean, settings: Settings, entry: FocusRequester, rail: Map<String, FocusRequester>,
                        api: Discovery, jiten: JitenQueue?, live: Live, onOpen: (Found) -> Unit) {
    var list by remember { mutableStateOf(LibraryMemory.list ?: openingList(all)) }
    LaunchedEffect(all.isNotEmpty()) { if (list == null) list = openingList(all) }
    val current = list ?: LibraryList.WATCHING
    var filters by remember { mutableStateOf(settings.library) }
    val levels = rememberLevels(rememberQueue(jiten))
    val built = rememberBuilt(all, current, filters, kept, keptFailed, levels)
    var inGrid by remember { mutableStateOf(false) }
    var display by remember { mutableStateOf<Shown?>(null) }
    LaunchedEffect(built, inGrid) { built?.let { if (!inGrid || display == null) display = it } }
    val items = display?.view?.items.orEmpty()
    val restore = remember { LibraryMemory.opened.also { LibraryMemory.opened = null } }
    val grid = rememberLazyGridState()
    val focus = remember(current) { LibFocus() }
    val nav = remember { Nav() }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(display?.list, display?.filters) { if (display != null) { if (seen) grid.scrollToItem(0) else seen = true } }
    KeepFocus(display, restore, grid, focus, nav, entry, live)
    Asks(levels, all, current, filters.needsLevels, grid, items)
    Box(Modifier.fillMaxSize()) {
        LibraryHero(items, focus, api, levels)
        LibraryColumn(all, current, filters, entry, nav.grid.takeIf { items.isNotEmpty() }, { rail[tr("Library")] ?: FocusRequester.Default },
            { l -> if (list != null || l != LibraryList.WATCHING) { list = l; LibraryMemory.list = l } },
            { f -> filters = f; settings.library = f }, Modifier.padding(start = RAIL, top = 28.dp))
        Column(Modifier.padding(start = CONTENT_START, end = 28.dp, top = HERO_BOTTOM)) {
            display?.let { d ->
                Text(remember(d, keptFailed) { libraryLine(d.list, d.filters, d.view, keptFailed) }, color = Colors.dim, fontSize = 14.sp)
                if (items.isEmpty()) Text(if (d.view.total == 0) tr("Nothing in this list yet") else tr("Nothing here with these filters"), color = Colors.dim, modifier = Modifier.padding(top = 12.dp))
            }
            if (items.isNotEmpty()) LibraryGrid(items, display?.list ?: current, levels, grid,
                Modifier.focusRequester(nav.grid).onBackKey { runCatching { entry.requestFocus() } }.onFocusChanged { inGrid = it.hasFocus }.focusRestorer(nav.enter),
                focus, entry, nav) { m -> LibraryMemory.opened = m.anilistId; onOpen(m.found()) }
        }
        Button(onClick = { val f = filters.copy(sort = filters.sort.next()); filters = f; settings.library = f },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 28.dp).focusRequester(nav.sort)
                .onBackKey { runCatching { entry.requestFocus() } }
                .focusProperties { down = if (items.isEmpty()) FocusRequester.Default else nav.grid; left = entry }) { Text(tr("Sort: %s", filters.sort.label())) }
    }
}

@Composable
private fun rememberBuilt(all: List<Mine>, current: LibraryList, filters: LibraryFilters, kept: Set<String>?, keptFailed: Boolean, levels: Levels?): Shown? {
    val needed = filters.needsLevels
    val loaded = if (needed) levels?.loaded else null
    val ready = (!needed || levels != null) && (!filters.onHome || kept != null || keptFailed)
    val built by produceState<Shown?>(null, all, current, filters, kept, keptFailed, levels, loaded, ready) {
        if (!ready) return@produceState
        value = withContext(Dispatchers.Default) {
            val numbers = if (needed) levels?.snapshot(current.of(all).map { it.kitsuId }).orEmpty() else emptyMap()
            Shown(current, filters, libraryView(all, current, filters, kept.orEmpty()) { numbers[it] })
        }
    }
    return built
}

@Composable
private fun KeepFocus(display: Shown?, restore: Int?, grid: LazyGridState, focus: LibFocus, nav: Nav, entry: FocusRequester, live: Live) {
    val now = rememberUpdatedState(display)
    var restoring by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try {
            val first = if (restore == null) null else withContext(Dispatchers.Default) {
                withTimeoutOrNull(RESTORE_WAIT_MS) { snapshotFlow { now.value }.filterNotNull().first() }
            }
            val at = first?.view?.items?.indexOfFirst { it.anilistId == restore } ?: -1
            if (at >= 0) {
                nav.target = restore
                grid.scrollToItem(at)
                repeat(30) {
                    if (live.touched.value || focus.id == restore) return@repeat
                    runCatching { nav.enter.requestFocus() }
                    withFrameNanos { }
                }
                nav.target = null
            }
            if (!live.touched.value && (restore == null || focus.id != restore)) entry.focusSoon()
        } finally {
            restoring = false
        }
    }
    LaunchedEffect(display, live.focusIn.value, restoring) {
        if (display == null || restoring || live.focusIn.value) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (!live.focusIn.value) entry.focusSoon()
    }
}

@Composable
private fun Asks(levels: Levels?, all: List<Mine>, current: LibraryList, needed: Boolean, grid: LazyGridState, items: List<Mine>) {
    val owner = remember { Any() }
    AskVisible(levels, grid, items)
    DisposableEffect(levels) { onDispose { levels?.drop(owner) } }
    LaunchedEffect(levels, current, needed, all) {
        levels ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            levels.know(all)
            levels.drop(owner)
            if (needed) levels.ask(current.of(all).map { it.kitsuId }, false, owner)
        }
    }
}

@Composable
private fun LibraryGrid(items: List<Mine>, d: LibraryList, levels: Levels?, state: LazyGridState, modifier: Modifier, focus: LibFocus,
                        entry: FocusRequester, nav: Nav, onOpen: (Mine) -> Unit) =
    LazyVerticalGrid(GridCells.Fixed(COLUMNS), modifier.fillMaxSize(), state, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(items, key = { _, m -> m.anilistId }) { i, m ->
            val first by remember(i) { derivedStateOf { state.firstVisibleItemIndex == i } }
            val card = Modifier.onFocusChanged { if (it.isFocused) focus.id = m.anilistId }
                .focusProperties { if (i % COLUMNS == 0) left = entry; if (i < COLUMNS) up = nav.sort }
                .then(if (nav.target?.let { m.anilistId == it } ?: first) Modifier.focusRequester(nav.enter) else Modifier)
            val progress = m.episodes?.takeIf { it > 0 && m.progress > 0 && d != LibraryList.COMPLETED }?.let { m.progress.toFloat() / it }
            Poster(m.cover, m.shown(), if (m.progress > 0) m.progressLine() else "", progress, card, percent = levels?.of(m.kitsuId)?.percent, width = 83.dp) { onOpen(m) }
        }
    }

@Composable
private fun AskVisible(levels: Levels?, grid: LazyGridState, items: List<Mine>) {
    levels ?: return
    LaunchedEffect(levels, items) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.map { it.index } }.collectLatest { at ->
            withContext(Dispatchers.Default) { levels.ask(at.mapNotNull { items.getOrNull(it)?.kitsuId }, true) }
        }
    }
}
