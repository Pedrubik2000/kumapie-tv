package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val COLUMNS = 6
private const val RESTORE_WAIT_MS = 3_000L

internal class GridNav(val grid: FocusRequester = FocusRequester()) {
    val enter = FocusRequester()
    var target by mutableStateOf<Any?>(null)
}

@Composable
internal fun <T : Any> rememberFrozen(built: T?, frozen: Boolean): T? {
    var display by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(built, frozen) { built?.let { if (!frozen || display == null) display = it } }
    return display
}

@Composable
internal fun <T> CoverGrid(items: List<T>, id: (T) -> Any, state: LazyGridState, modifier: Modifier, focus: LibFocus, entry: FocusRequester,
                           nav: GridNav, above: () -> FocusRequester, cell: @Composable (T, Modifier) -> Unit) {
    LaunchedEffect(state, items) {
        snapshotFlow { focus.id }.collectLatest { fid ->
            val i = items.indexOfFirst { id(it) == fid }
            if (i < 0) return@collectLatest
            withFrameNanos { }
            withFrameNanos { }
            if (!rowIsWhole(state, i)) state.animateScrollToItem(i / COLUMNS * COLUMNS)
        }
    }
    LazyVerticalGrid(GridCells.Fixed(COLUMNS), modifier.fillMaxSize().clipToBounds(), state, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(items, key = { _, m -> id(m) }) { i, m ->
            val first by remember(i) { derivedStateOf { state.firstVisibleItemIndex == i } }
            val card = Modifier.onFocusChanged { if (it.isFocused) focus.id = id(m) }
                .focusProperties { if (i % COLUMNS == 0) left = entry; if (i < COLUMNS) up = above() }
                .then(if (nav.target?.let { id(m) == it } ?: first) Modifier.focusRequester(nav.enter) else Modifier)
            cell(m, card)
        }
    }
}

private fun rowIsWhole(state: LazyGridState, index: Int): Boolean {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    val whole = item.offset.y >= info.beforeContentPadding && item.offset.y + item.size.height <= info.viewportEndOffset
    val aboveCut = index / COLUMNS - 1 == state.firstVisibleItemIndex / COLUMNS && state.firstVisibleItemScrollOffset != 0
    return whole && !aboveCut
}

@Composable
internal fun <D : Any> KeepFocus(display: D?, restore: Any?, indexOf: (D, Any) -> Int, grid: LazyGridState, focus: LibFocus, nav: GridNav,
                                 entry: FocusRequester, live: Live) {
    val now = rememberUpdatedState(display)
    var restoring by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try {
            val first = if (restore == null) null else withContext(Dispatchers.Default) {
                withTimeoutOrNull(RESTORE_WAIT_MS) { snapshotFlow { now.value }.filterNotNull().first() }
            }
            val at = if (restore == null) -1 else first?.let { indexOf(it, restore) } ?: -1
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
    EntryWhenIdle(display, restoring, live, entry)
}

@Composable
internal fun AskVisible(levels: Levels?, grid: LazyGridState, ids: List<String?>) {
    levels ?: return
    LaunchedEffect(levels, ids) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.map { it.index } }.collectLatest { at ->
            withContext(Dispatchers.Default) { levels.ask(at.mapNotNull { ids.getOrNull(it) }, true) }
        }
    }
}
