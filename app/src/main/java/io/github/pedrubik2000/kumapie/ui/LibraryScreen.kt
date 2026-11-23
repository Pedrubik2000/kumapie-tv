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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.withContext

typealias LibraryRead = (SharedPreferences, String, String, CoroutineScope, (List<Mine>) -> Unit) -> List<Mine>

private const val COLUMNS = 6

@Composable
fun LibraryScreen(api: Discovery, settings: Settings, onHome: () -> Unit, onDiscover: () -> Unit, onReview: () -> Unit, onSettings: () -> Unit,
                  onOpen: (Found) -> Unit, jiten: JitenQueue? = null, read: LibraryRead = AniListLists::cacheFirst) {
    val rail = remember { mutableMapOf<String, FocusRequester>() }
    val entry = remember { FocusRequester() }
    val loggedIn = settings.anilistToken.isNotEmpty()
    val user = settings.anilist
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<Mine>?>(null) }
    var kept by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(user, loggedIn) {
        all = if (!loggedIn || user.isEmpty()) emptyList()
        else withContext(Dispatchers.IO) { read(settings.prefs, user, settings.anilistToken, scope) { all = it } }
    }
    LaunchedEffect(Unit) { kept = runCatching { api.saved().map { it.id }.toSet() }.getOrNull() }
    Box(Modifier.fillMaxSize()) {
        val lists = all
        when {
            !loggedIn -> LoggedOut(onSettings, entry)
            lists == null -> Text(tr("Loading…"), color = Colors.dim, modifier = Modifier.padding(start = CONTENT_START, top = 100.dp))
            else -> LibraryBody(lists, kept, settings, entry, rail, api, jiten, onOpen)
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

@Composable
private fun LibraryBody(all: List<Mine>, kept: Set<String>?, settings: Settings, entry: FocusRequester, rail: Map<String, FocusRequester>,
                        api: Discovery, jiten: JitenQueue?, onOpen: (Found) -> Unit) {
    var list by remember { mutableStateOf(LibraryMemory.list ?: LibraryList.WATCHING.takeIf { it.of(all).isNotEmpty() } ?: LibraryList.PLANNING) }
    var filters by remember { mutableStateOf(settings.library) }
    val levels = rememberLevels(rememberQueue(jiten))
    val needed = filters.sort == LibrarySort.EASIEST || filters.limit > 0
    val loaded = if (needed) levels?.loaded else null
    val view = remember(all, list, filters, kept, levels, loaded) { libraryView(all, list, filters, kept.orEmpty()) { levels?.of(it) } }
    val restore = remember { LibraryMemory.opened.also { LibraryMemory.opened = null } }
    val restoreAt = remember { view.items.indexOfFirst { it.kitsuId == restore } }
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = if (restoreAt > 0) restoreAt / COLUMNS * COLUMNS else 0)
    val focus = remember(list) { LibFocus() }
    val gridReq = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val sort = remember { FocusRequester() }
    val opened = remember { FocusRequester() }
    var fresh by remember { mutableStateOf(true) }
    val change = { f: LibraryFilters -> filters = f; settings.library = f }
    val empty = view.items.isEmpty()
    LaunchedEffect(list, filters) { if (fresh) fresh = false else grid.scrollToItem(0) }
    LaunchedEffect(Unit) {
        if (restore == null || restoreAt < 0) return@LaunchedEffect entry.focusSoon()
        repeat(30) {
            if (focus.id == restore) return@LaunchedEffect
            runCatching { opened.requestFocus() }
            withFrameNanos { }
        }
    }
    AskVisible(levels, grid, view.items)
    LaunchedEffect(levels, list, needed) { if (needed) levels?.ask(list.of(all).map { it.kitsuId }, false) }
    Box(Modifier.fillMaxSize()) {
        LibraryHero(view.items, focus, api, levels)
        LibraryColumn(all, list, filters, entry, gridReq.takeIf { !empty }, { rail[tr("Library")] ?: FocusRequester.Default },
            { l -> list = l; LibraryMemory.list = l }, change, Modifier.padding(start = RAIL, top = 28.dp))
        Column(Modifier.padding(start = CONTENT_START, end = 28.dp, top = HERO_BOTTOM)) {
            val line = remember(view, list, filters) { libraryLine(list, filters, view) }
            Text(line, color = Colors.dim, fontSize = 14.sp)
            if (empty) Text(if (view.total == 0) tr("Nothing in this list yet") else tr("Nothing here with these filters"), color = Colors.dim, modifier = Modifier.padding(top = 12.dp))
            else LibraryGrid(view.items, list, levels, grid, Modifier.focusRequester(gridReq).onBackKey { runCatching { entry.requestFocus() } }.focusRestorer(first),
                focus, entry, sort, first, opened, restore) { m -> LibraryMemory.opened = m.kitsuId; onOpen(m.found()) }
        }
        Button(onClick = { change(filters.copy(sort = filters.sort.next())) },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 28.dp).focusRequester(sort)
                .focusProperties { down = if (empty) FocusRequester.Default else gridReq; left = entry }) { Text(tr("Sort: %s", filters.sort.label())) }
    }
}

@Composable
private fun LibraryGrid(items: List<Mine>, list: LibraryList, levels: Levels?, state: LazyGridState, modifier: Modifier, focus: LibFocus,
                        entry: FocusRequester, sort: FocusRequester, first: FocusRequester, opened: FocusRequester, restore: String?, onOpen: (Mine) -> Unit) =
    LazyVerticalGrid(GridCells.Fixed(COLUMNS), modifier.fillMaxSize(), state, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(items, key = { _, m -> m.kitsuId }) { i, m ->
            val card = Modifier.onFocusChanged { if (it.isFocused) focus.id = m.kitsuId }
                .focusProperties { if (i % COLUMNS == 0) left = entry; if (i < COLUMNS) up = sort }
                .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                .then(if (m.kitsuId == restore) Modifier.focusRequester(opened) else Modifier)
            val progress = m.episodes?.takeIf { it > 0 && m.progress > 0 && list != LibraryList.COMPLETED }?.let { m.progress.toFloat() / it }
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
