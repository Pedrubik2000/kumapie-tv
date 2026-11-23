package io.github.pedrubik2000.kumapie.ui

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.levelLine
import io.github.pedrubik2000.kumapie.data.shortTitle
import io.github.pedrubik2000.kumapie.data.shown
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.launch

private val ROWS_TOP_TALL = 290.dp
private val ROWS_TOP_SHORT = 250.dp
private val ROW_PADDING = PaddingValues(start = RAIL, end = 32.dp, top = 12.dp, bottom = 24.dp)

@Stable
internal class RowCtx(
    val d: HomeData, val focus: HomeFocus, val api: Discovery, val levels: Levels?, val first: FocusRequester, val below: FocusRequester,
    val toTop: () -> Unit, val scrollTo: (Int) -> Unit, val onShow: (Show) -> Unit, val onPlay: (Show, Episode) -> Unit, val onDiscovered: (Found) -> Unit, val onLogin: () -> Unit,
) {
    fun card(row: Int, index: Int, target: () -> HeroTarget?): Modifier {
        var m: Modifier = Modifier.onFocusChanged { if (it.isFocused) { target()?.let { t -> focus.target = t }; focus.row = row; scrollTo(row) } }
        if (index == 0 && row == 0) m = m.focusRequester(if (d.going.isEmpty()) first else below)
        if (row == 0 && d.going.isNotEmpty()) m = m.onPreviewKeyEvent { e ->
            (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp).also { if (it) toTop() }
        }
        return m
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeBody(d: HomeData, focus: HomeFocus, api: Discovery, levels: Levels?, first: FocusRequester, railFocus: Map<String, FocusRequester>,
                      onShow: (Show) -> Unit, onPlay: (Show, Episode) -> Unit, onDiscovered: (Found) -> Unit, onLogin: () -> Unit) {
    val list = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(ahead = 600.dp, behind = 600.dp))
    val scope = rememberCoroutineScope()
    val below = remember { FocusRequester() }
    val toTop = {
        d.resume?.let { focus.target = it }
        scope.launch { runCatching { list.scrollToItem(0) }; first.focusSoon(); runCatching { list.scrollToItem(0) } }
        Unit
    }
    val ctx = remember(d, levels) { RowCtx(d, focus, api, levels, first, below, toTop, { i -> scope.launch { runCatching { list.animateScrollToItem(i) } }; Unit }, onShow, onPlay, onDiscovered, onLogin) }
    LaunchedEffect(d.resume) { if (focus.target == null || focus.target is ResumeTarget) focus.target = d.resume }
    KnowIds(levels, d.all)
    AskLevels(levels, d.askRows, focus)
    Box(Modifier.fillMaxSize()) {
        HomeHero(focus, d.warm, api, levels, first, below, onPlay, onShow)
        CompositionLocalProvider(LocalBringIntoViewSpec provides HeadingFirst) {
            LazyColumn(Modifier.fillMaxSize().rowsBelowHero(focus).backToTop(ctx, list, toTop), state = list, contentPadding = PaddingValues(bottom = 240.dp)) { homeRows(ctx) }
        }
    }
    LaunchedEffect(d.going) { takeRailLast()?.let { railFocus[it] }?.focusSoon() ?: first.focusSoon() }
}

private fun Modifier.rowsBelowHero(focus: HomeFocus): Modifier = layout { m, c ->
    val top = (if (focus.tall) ROWS_TOP_TALL else ROWS_TOP_SHORT).roundToPx().coerceAtMost(c.maxHeight)
    val p = m.measure(c.copy(minHeight = 0, maxHeight = c.maxHeight - top))
    layout(p.width, c.maxHeight) { p.place(0, top) }
}

private fun Modifier.backToTop(ctx: RowCtx, list: LazyListState, toTop: () -> Unit): Modifier = onKeyEvent {
    val scrolled = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0
    val inRows = ctx.d.going.isNotEmpty() && !ctx.focus.tall
    if (it.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK || !(scrolled || inRows)) return@onKeyEvent false
    if (it.type == KeyEventType.KeyUp) toTop()
    true
}

private fun LazyListScope.homeRows(c: RowCtx) {
    val names = c.d.names
    if (c.d.going.isNotEmpty()) goingRow(c, rowNav(names, 0))
    if (c.d.watching.isNotEmpty()) minesRow(c, "watching", tr("Watching on AniList"), names.indexOf(tr("Watching on AniList")), c.d.watching, true)
    if (c.d.planning.isNotEmpty()) minesRow(c, "planning", tr("Planning on AniList"), names.indexOf(tr("Planning on AniList")), c.d.planning, false)
    if (!c.d.loggedIn) loginRow(c)
    libraryRows(c)
}

@Composable
private fun RowHead(label: String, nav: RowNav?) =
    Row(Modifier.padding(start = RAIL, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
        Heading(label)
        nav?.let { n ->
            Text(n.position, color = Arcade.accent, fontSize = 16.sp, style = Arcade.label)
            n.up?.let { Text("▲ $it", color = Colors.dim, fontSize = 16.sp) }
            n.down?.let { Text("▼ $it", color = Colors.dim, fontSize = 16.sp) }
        }
    }

private fun LazyListScope.goingRow(c: RowCtx, nav: RowNav?) = item(key = "going") {
    val going = c.d.going
    WithTvScroll { Column {
        RowHead(tr("Continue watching"), nav)
        LazyRow(state = remember(going) { LazyListState() }, horizontalArrangement = Arrangement.spacedBy(24.dp), contentPadding = ROW_PADDING) {
            itemsIndexed(going, key = { _, p -> p.second.id }) { i, (s, e) ->
                val target = { ContinueTarget(s, e, i, going.size, c.d.byKitsu[s.kitsuId()]) }
                ResumeCard(s, e, c.card(0, i, target), width = 220.dp, picture = titlePicture(s, e, c.api)) { c.onPlay(s, e) }
            }
        }
    } }
}

private fun LazyListScope.minesRow(c: RowCtx, key: String, label: String, row: Int, mines: List<io.github.pedrubik2000.kumapie.data.Mine>, watching: Boolean) = item(key = key) {
    WithTvScroll { Column {
        RowHead(label, rowNav(c.d.names, row))
        LazyRow(state = remember(mines) { LazyListState() }, horizontalArrangement = Arrangement.spacedBy(24.dp), contentPadding = ROW_PADDING) {
            itemsIndexed(mines, key = { _, m -> m.anilistId }) { i, m ->
                val progress = m.episodes?.takeIf { watching && it > 0 }?.let { m.progress.toFloat() / it }
                Poster(m.cover, m.shown(), if (watching) m.progressLine() else "", progress, c.card(row, i) { ListTarget(m) },
                    percent = c.levels?.of(m.kitsuId)?.percent) { c.onDiscovered(m.found()) }
            }
        }
    } }
}

private fun LazyListScope.loginRow(c: RowCtx) = item(key = "login") {
    LoginLine(Modifier.padding(start = RAIL, top = 16.dp, bottom = 16.dp),
        if (c.d.names.isEmpty()) Modifier.focusRequester(c.first) else Modifier.onFocusChanged { if (it.isFocused) { c.focus.row = c.d.names.size; c.scrollTo(c.d.names.size) } }, c.onLogin)
}

@Composable
internal fun LoginLine(modifier: Modifier, button: Modifier, onLogin: () -> Unit) =
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Log in with AniList to see your lists here"), color = Colors.dim)
        Button(onClick = onLogin, modifier = button) { Text(tr("Log in")) }
    }

private fun LazyListScope.libraryRows(c: RowCtx) {
    val shows = c.d.library.filter { it.second.isNotEmpty() }
    shows.forEachIndexed { r, (label, list) ->
        item(key = "kind-$r") {
            val row = c.d.names.size + r
            val at = row + if (c.d.loggedIn) 0 else 1
            WithTvScroll { Shelf(label, inset = RAIL) {
                itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                    val start = c.d.names.isEmpty() && c.d.loggedIn && r == 0 && i == 0
                    Poster(s.poster, shortTitle(s.shown), levelLine(s), null,
                        (if (start) Modifier.focusRequester(c.first) else Modifier).onFocusChanged { if (it.isFocused) { c.focus.row = row; c.scrollTo(at) } }) { c.onShow(s) }
                }
            } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val HeadingFirst = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WithTvScroll(content: @Composable () -> Unit) = CompositionLocalProvider(LocalBringIntoViewSpec provides TvScroll, content = content)
