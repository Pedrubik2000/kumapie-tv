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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class BrowseShown(val filters: BrowseFilters, val view: BrowseView)

@Composable
internal fun BrowseContent(env: DiscoverEnv, state: BrowseState, lists: List<Mine>?, live: Live) {
    val restore = remember { env.hold.take(DiscoverMode.BROWSE) }
    val loggedIn = env.settings.anilistToken.isNotEmpty()
    val filters = state.filters
    val onList = remember(lists) { lists.orEmpty().map { it.kitsuId }.toSet() }
    val ids = remember(state.rows, state.results) { (state.rows.orEmpty().flatMap { it.second } + state.results.orEmpty()).mapNotNull(::kitsuOf).distinct() }
    val built = rememberBrowse(state, onList, ids, env.levels, loggedIn && lists == null)
    var inGrid by remember { mutableStateOf(false) }
    val display = rememberFrozen(built, inGrid)
    val items = display?.view?.items.orEmpty()
    val grid = rememberLazyGridState()
    val focus = remember { LibFocus() }
    val nav = remember { GridNav(env.link.to) }
    var seen by remember { mutableStateOf(false) }
    LinkWhen(env.link, items.isNotEmpty())
    LaunchedEffect(display?.filters) { if (display != null) { if (seen) grid.scrollToItem(0) else seen = true } }
    KeepFocus(display, restore, { d, id -> d.view.items.indexOfFirst { it.id == id } }, grid, focus, nav, env.entry, live)
    val askIds = remember(state.rows, state.results, filters) { browseAskIds(state.rows.orEmpty(), state.results.orEmpty(), filters) }
    BrowseAsks(env.levels, askIds, filters.limit > 0, grid, items)
    Box(Modifier.fillMaxSize()) {
        BrowseHero(env, filters, items, focus)
        Column(Modifier.padding(start = CONTENT_START, end = 28.dp, top = HERO_BOTTOM)) {
            BrowseBody(env, state, display, items, grid, focus, nav) { inGrid = it }
        }
    }
}

@Composable
private fun BrowseHero(env: DiscoverEnv, filters: BrowseFilters, items: List<Found>, focus: LibFocus) {
    val head = focus.id?.let { id -> items.firstOrNull { it.id == id } } ?: items.firstOrNull()
    HeroStrip(head?.let(::FoundTarget), env.api, filters.eyebrow(), head?.shownName.orEmpty(), head?.line.orEmpty(), head?.let(::kitsuOf)?.let { env.levels?.of(it) }, head != null)
}

@Composable
private fun BrowseBody(env: DiscoverEnv, state: BrowseState, display: BrowseShown?, items: List<Found>, grid: LazyGridState, focus: LibFocus, nav: GridNav,
                       inGrid: (Boolean) -> Unit) {
    val error = if (state.filters.query.isNotEmpty()) state.searchError else state.error
    when {
        error != null -> ErrorBox(error, onRetry = state::retry, onSettings = null, server = env.api is Api)
        display == null -> Text(tr("Loading…"), color = Colors.dim)
        else -> {
            Text(remember(display) { browseLine(display.filters, display.view) }, color = Colors.dim, fontSize = 14.sp)
            if (items.isEmpty()) Text(tr("Nothing found"), color = Colors.dim, modifier = Modifier.padding(top = 12.dp))
        }
    }
    if (items.isNotEmpty() && error == null) CoverGrid(items, { it.id }, grid,
        Modifier.focusRequester(nav.grid).onBackKey { backFromGrid(state, env.entry) }.onFocusChanged { inGrid(it.hasFocus) }.focusRestorer(nav.enter),
        focus, env.entry, nav, { MissingKeyLine.up(nav.grid) }) { f, card ->
        Poster(f.poster, f.shownName, f.year, null, card, percent = kitsuOf(f)?.let { env.levels?.of(it) }?.percent, width = 83.dp) {
            env.open(f, f.id)
        }
    }
}

private fun backFromGrid(state: BrowseState, entry: FocusRequester) {
    runCatching { entry.requestFocus() }
    if (state.filters.query.isNotEmpty()) state.pick(state.filters.copy(query = ""))
}

@Composable
private fun rememberBrowse(state: BrowseState, onList: Set<String>, ids: List<String>, levels: Levels?, waitingLists: Boolean): BrowseShown? {
    val f = state.filters
    val rows = state.rows
    val results = state.results
    val loaded = if (f.limit > 0) levels?.loaded else null
    val ready = (f.query.isNotEmpty() && results != null || f.query.isEmpty() && rows != null) && (f.limit == 0 || levels != null) && !(f.notOnList && waitingLists)
    val built by produceState<BrowseShown?>(null, rows, results, f, onList, levels, loaded, ready) {
        if (!ready) return@produceState
        value = withContext(Dispatchers.Default) {
            val numbers = if (f.limit > 0) levels?.snapshot(ids).orEmpty() else emptyMap()
            BrowseShown(f, browseView(rows.orEmpty(), results.orEmpty(), f, onList) { numbers[it] })
        }
    }
    return built
}

@Composable
private fun BrowseAsks(levels: Levels?, ids: List<String>, needed: Boolean, grid: LazyGridState, items: List<Found>) {
    val owner = remember { Any() }
    AskVisible(levels, grid, remember(items) { items.map { kitsuOf(it) } })
    DisposableEffect(levels) { onDispose { levels?.release(owner) } }
    LaunchedEffect(levels, needed, ids) {
        levels ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            levels.drop(owner)
            if (needed) levels.ask(ids, false, owner)
        }
    }
}
