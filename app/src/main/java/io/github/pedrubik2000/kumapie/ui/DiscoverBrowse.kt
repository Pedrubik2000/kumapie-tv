package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MORE_AHEAD = 12
private const val EMPTY_PAGES_CAP = 3

private class BrowseShown(val filters: BrowseFilters, val view: BrowseView)

@Composable
internal fun BrowseContent(env: DiscoverEnv, state: BrowseState, live: Live) {
    val restore = remember { env.hold.take(DiscoverMode.BROWSE) }
    val filters = state.filters
    val built = rememberBrowse(state, env.levels)
    var inGrid by remember { mutableStateOf(false) }
    val display = rememberBrowseFrozen(built, inGrid)
    val items = display?.view?.items.orEmpty()
    val grid = rememberLazyGridState()
    val focus = remember { LibFocus() }
    val nav = remember { GridNav(env.link.to) }
    var seen by remember { mutableStateOf(false) }
    LinkWhen(env.link, items.isNotEmpty())
    LaunchedEffect(display?.filters) { if (display != null) { if (seen) grid.scrollToItem(0) else seen = true } }
    KeepFocus(display, restore, { d, id -> d.view.items.indexOfFirst { it.anilistId == id } }, grid, focus, nav, env.entry, live)
    LaunchedEffect(focus.id, items.size, state.items.size, state.loaded) {
        val at = items.indexOfFirst { it.anilistId == focus.id }
        if (state.loaded && ((items.size < MORE_AHEAD && (items.isNotEmpty() || state.page < EMPTY_PAGES_CAP)) || at >= items.size - MORE_AHEAD)) state.requestMore()
    }
    BrowseAsks(env.levels, state.items, filters.limit > 0, grid, items)
    Box(Modifier.fillMaxSize()) {
        BrowseHero(env, filters, items, focus)
        Column(Modifier.padding(start = CONTENT_START, end = 28.dp, top = HERO_BOTTOM)) {
            BrowseBody(env, state, display, items, grid, focus, nav) { inGrid = it }
        }
    }
}

@Composable
private fun BrowseHero(env: DiscoverEnv, filters: BrowseFilters, items: List<Mine>, focus: LibFocus) {
    val head = (focus.id?.let { id -> items.firstOrNull { it.anilistId == id } } ?: items.firstOrNull())?.found()
    HeroStrip(head?.let(::FoundTarget), env.api, filters.eyebrow(), head?.shownName.orEmpty(), head?.line.orEmpty(), head?.let(::kitsuOf)?.let { env.levels?.of(it) }, head != null)
}

@Composable
private fun BrowseBody(env: DiscoverEnv, state: BrowseState, display: BrowseShown?, items: List<Mine>, grid: LazyGridState, focus: LibFocus, nav: GridNav,
                       inGrid: (Boolean) -> Unit) {
    val error = state.error
    val retry = remember { FocusRequester() }
    when {
        error != null -> ErrorBox(error, onRetry = state::retry, onSettings = null, server = env.api is Api)
        display == null -> Text(tr("Loading…"), color = Colors.dim)
        else -> {
            Text(remember(display, state.total, state.expired) { styled(browseLine(display.filters, state.total, display.view, state.expired), Colors.dim) }, fontSize = 14.sp)
            if (items.isEmpty()) Text(state.emptyNote ?: emptyLine(display.filters), color = Colors.dim, modifier = Modifier.padding(top = 12.dp))
        }
    }
    if (items.isNotEmpty() && error == null) CoverGrid(items, { it.anilistId }, grid,
        Modifier.focusRequester(nav.grid).onBackKey { backFromGrid(state, env.entry) }.onFocusChanged { inGrid(it.hasFocus) }.focusRestorer(nav.enter),
        focus, env.entry, nav, { MissingKeyLine.up(nav.grid) }, state.moreError?.let { msg -> { MoreError(msg, { state.retryMore(); runCatching { nav.grid.requestFocus() } }, retry) } },
        if (state.moreError != null) retry else FocusRequester.Cancel) { m, card ->
        Poster(m.cover, m.shown(), m.year.takeIf { it > 0 }?.toString().orEmpty(), null, card, level = env.levels?.of(m.kitsuId), width = 83.dp) {
            env.open(m.found(), m.anilistId)
        }
    }
}

private fun emptyLine(f: BrowseFilters): String {
    val active = browseActive(f)
    return if (active.isEmpty()) tr("Nothing found on AniList.") else tr("Nothing found. Loosen: %s.", active.joinToString(", "))
}

private fun backFromGrid(state: BrowseState, entry: FocusRequester) {
    runCatching { entry.requestFocus() }
    if (state.filters.query.isNotEmpty()) state.pick(state.filters.copy(query = ""))
}

@Composable
private fun rememberBrowse(state: BrowseState, levels: Levels?): BrowseShown? {
    val f = state.filters
    val items = state.items
    val loaded = if (f.limit > 0) levels?.loaded else null
    val ready = state.loaded && (f.limit == 0 || levels != null)
    val built by produceState<BrowseShown?>(null, items, f, levels, loaded, ready) {
        if (!ready) return@produceState
        value = withContext(Dispatchers.Default) {
            val numbers = if (f.limit > 0) levels?.snapshot(items.map { it.kitsuId }).orEmpty() else emptyMap()
            BrowseShown(f, browseView(items, f) { numbers[it] })
        }
    }
    return built
}

@Composable
private fun BrowseAsks(levels: Levels?, all: List<Mine>, needed: Boolean, grid: LazyGridState, items: List<Mine>) {
    val owner = remember { Any() }
    AskVisible(levels, grid, remember(items) { items.map { it.kitsuId } })
    DisposableEffect(levels) { onDispose { levels?.release(owner) } }
    LaunchedEffect(levels, needed, all) {
        levels ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            levels.know(all)
            levels.drop(owner)
            if (needed) levels.ask(all.map { it.kitsuId }, false, owner)
        }
    }
}

@Composable
internal fun BrowsePicker(state: BrowseState, kind: PickerKind) {
    val f = state.filters
    val source = PickerSource(tr("Filter anime"),
        { k -> (if (k == PickerKind.GENRES) tr("Genres") else tr("Tags")).uppercase() + if (k == PickerKind.TAGS) " · " + tr("most frequent in these results") else "" },
        { k ->
            val picks = if (k == PickerKind.GENRES) f.genres else f.tags
            if (k == PickerKind.GENRES) (ANILIST_GENRES + (picks.include + picks.exclude).filter { it !in ANILIST_GENRES }).map { it to -1 } else names(state.items, k, picks)
        },
        { k -> if (k == PickerKind.GENRES) f.genres else f.tags },
        { k, p -> state.pick(if (k == PickerKind.GENRES) f.copy(genres = p) else f.copy(tags = p)) })
    LibraryPicker(kind, { state.picker = it; state.last = it }, source, if (state.loaded) browseLine(f, state.total, BrowseView(state.items, 0), state.expired) else null, state.total) { state.picker = null }
}

@Composable
private fun MoreError(message: String, onRetry: () -> Unit, focus: FocusRequester) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tr("Couldn't load more: %s", message), color = Colors.dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Button(onClick = onRetry, modifier = Modifier.hint(HintKind.CHOOSE).focusRequester(focus)) { Text(tr("Try again")) }
    }
}

@Composable
private fun rememberBrowseFrozen(built: BrowseShown?, frozen: Boolean): BrowseShown? {
    var display by remember { mutableStateOf<BrowseShown?>(null) }
    LaunchedEffect(built, frozen) {
        val b = built ?: return@LaunchedEffect
        val d = display
        if (!frozen || d == null || b.appends(d)) display = b
    }
    return display
}

private fun BrowseShown.appends(old: BrowseShown): Boolean =
    filters == old.filters && view.items.size >= old.view.items.size && view.items.zip(old.view.items).all { (a, b) -> a.anilistId == b.anilistId }
