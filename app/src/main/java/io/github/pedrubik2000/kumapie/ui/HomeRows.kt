package io.github.pedrubik2000.kumapie.ui

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.ContinueHidden
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.levelLine
import io.github.pedrubik2000.kumapie.data.shortTitle
import io.github.pedrubik2000.kumapie.data.shown
import io.github.pedrubik2000.kumapie.data.streamFound
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.launch

private const val SETTLE_FRAMES = 2
private val ROWS_TOP_TALL = 290.dp
private val ROWS_TOP_SHORT = 250.dp

internal fun rowsTop(tall: Boolean) = if (tall) ROWS_TOP_TALL else ROWS_TOP_SHORT
private val ROW_PADDING = PaddingValues(start = RAIL, end = 32.dp, top = 12.dp, bottom = 24.dp)

@Stable
internal class RowCtx(
    val d: HomeData, val focus: HomeFocus, val api: Discovery, val levels: Levels?, val first: FocusRequester, val below: FocusRequester,
    val toTop: () -> Unit, val scrollTo: (Int) -> Unit, val onShow: (Show) -> Unit, val onPlay: (Show, Episode) -> Unit, val onDiscovered: (Found) -> Unit, val onLogin: () -> Unit,
    val onMenu: (ContinueMenu) -> Unit,
) {
    fun open(s: Show, e: Episode, index: Int) {
        focus.leaving(0, index, s.id)
        if (s.id !in d.soon) onPlay(s, e) else if (s.kind == "stream") onDiscovered(streamFound(s, e)) else onShow(s)
    }

    fun card(row: Int, index: Int, key: Any, target: () -> HeroTarget?): Modifier {
        var m: Modifier = Modifier.onFocusChanged { if (it.isFocused) { target()?.let { t -> focus.target = t }; val moved = focus.row != row; focus.row = row; if (moved) scrollTo(row) } }
        m = m.then(focus.card(row, key))
        if (index == 0 && row == 0) m = m.focusRequester(if (d.going.isEmpty()) first else below)
        if (index == 0) m = m.then(focus.start(d.names.getOrNull(row) ?: row))
        if (row == 0 && d.going.isNotEmpty()) m = m.onPreviewKeyEvent { e ->
            (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp).also { if (it) toTop() }
        }
        return m
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeBody(d: HomeData, focus: HomeFocus, api: Discovery, levels: Levels?, first: FocusRequester, railFocus: Map<String, FocusRequester>,
                      onShow: (Show) -> Unit, onPlay: (Show, Episode) -> Unit, onDiscovered: (Found) -> Unit, onLogin: () -> Unit, onDiscover: () -> Unit,
                      onHide: (Show, Episode) -> Unit = { _, _ -> }, onUnhide: (Show) -> Unit = {}, held: () -> Boolean) {
    val list = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(ahead = 200.dp, behind = 200.dp))
    val scope = rememberCoroutineScope()
    val below = remember { FocusRequester() }
    val toTop = {
        focus.target = d.resume
        focus.memory.last = null
        scope.launch { runCatching { list.scrollToItem(0) }; first.focusSoon(); runCatching { list.scrollToItem(0) } }
        Unit
    }
    SideEffect { focus.toTop = toTop }
    var menu by remember { mutableStateOf<ContinueMenu?>(null) }
    var removed by remember { mutableStateOf<Removed?>(null) }
    val undone = remember { BooleanArray(1) }
    val ctx = remember(d, levels) { RowCtx(d, focus, api, levels, first, below, toTop, { i -> scope.launch { runCatching { list.animateScrollToItem(i) } }; Unit }, onShow, onPlay, onDiscovered, onLogin) { menu = it } }
    LaunchedEffect(d.resume) { if (focus.target == null || focus.target is ResumeTarget) focus.target = d.resume }
    KnowIds(levels, d.all)
    AskLevels(levels, d.askRows, focus)
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        val r = removed
        if (r == null || !undoKey(e)) false
        else {
            // the card under focus would still get this key's KeyUp as a click (play): swallow both, drop the toast on KeyUp
            if (e.type == KeyEventType.KeyDown && !undone[0]) { undone[0] = true; onUnhide(r.show); scope.launch { focus.bring(0, r.index, r.show.id) } }
            if (e.type == KeyEventType.KeyUp) { undone[0] = false; removed = null }
            true
        }
    }) {
        HomeHero(focus, d.warm, api, levels, first, below, d.names.isEmpty(), !d.loggedIn || d.library.any { it.second.isNotEmpty() }, onPlay, onShow, onDiscover)
        CompositionLocalProvider(LocalBringIntoViewSpec provides HeadingFirst) {
            LazyColumn(Modifier.fillMaxSize().rowsBelowHero(focus).backToTop(ctx, list, toTop), state = list, contentPadding = PaddingValues(bottom = 240.dp)) { homeRows(ctx) }
        }
        menu?.let { m ->
            ContinueMenuView(m, onRemove = {
                val next = if (m.index + 1 < d.going.size) m.index + 1 else m.index - 1
                focus.memory.last = d.going.getOrNull(next)?.let { Spot(0, next.coerceAtMost(m.index), it.first.id) }
                menu = null
                removed = Removed(m.show, m.episode, m.index)
                onHide(m.show, m.episode)
            }, onOpen = { menu = null; ctx.open(m.show, m.episode, m.index) }, onCancel = {
                menu = null
                scope.launch { withFrameNanos { }; focus.refocus(0, m.show.id) }
            })
        }
        removed?.let { RemovedToast(it) { removed = null } }
    }
    LaunchedEffect(d.going) {
        val rail = takeRailLast()?.let { railFocus[it] }
        if (rail != null) { focus.memory.last = null; rail.focusSoon() } else if (!focus.restore(list)) {
            repeat(SETTLE_FRAMES) { withFrameNanos { } }
            if (!held()) toTop()
        }
    }
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
private fun RowHead(label: String, nav: RowNav?, focus: HomeFocus, first: Boolean) {
    val pillLine = with(LocalDensity.current) { (rowsTop(focus.tall) + HINT_ROOM).toPx() }
    var inBand by remember { mutableStateOf(first) }
    val railNamed by remember { derivedStateOf { Hints.shown?.isRail == true } }
    val reserve = if ((rememberHintRoom() > 0.dp || railNamed) && inBand) HINT_PILL_WIDTH else 0.dp
    Row(Modifier.onGloballyPositioned { (it.positionInRoot().y < pillLine).let { b -> if (b != inBand) inBand = b } }.padding(start = RAIL, top = 8.dp, end = reserve),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
        Heading(label, maxLines = 1)
        nav?.let { n ->
            Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                Text(n.position, color = Arcade.accent, fontSize = 16.sp, style = Arcade.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                n.up?.let { Text("▲ $it", color = Colors.dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                n.down?.let { Text("▼ $it", color = Colors.dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

private fun LazyListScope.goingRow(c: RowCtx, nav: RowNav?) = item(key = "going") {
    val going = c.d.going
    WithTvScroll { Column {
        RowHead(tr("Continue watching"), nav, c.focus, true)
        LazyRow(state = remember(going) { LazyListState().also { c.focus.rows[0] = it } }, horizontalArrangement = Arrangement.spacedBy(24.dp), contentPadding = ROW_PADDING) {
            itemsIndexed(going, key = { _, p -> p.second.id }) { i, (s, e) ->
                val target = { ContinueTarget(s, e, i, going.size, c.d.mineFor(s, e)) }
                val at = c.d.soon[s.id]
                val where = remember { arrayOfNulls<LayoutCoordinates>(1) }
                val card = @Composable {
                    ResumeCard(s, e, c.card(0, i, s.id, target).onPlaced { where[0] = it }, width = 220.dp, picture = titlePicture(s, e, c.api),
                        airs = at?.let { ContinueHidden.airsText(it, c.d.now) },
                        onLongClick = { c.onMenu(ContinueMenu(s, e, i, where[0]?.positionInRoot()?.x ?: 0f, where[0]?.positionInRoot()?.y ?: 0f)) }) { c.open(s, e, i) }
                }
                if (i == c.d.soonFrom) SoonDivider(card) else card()
            }
        }
    } }
}

@Composable
private fun SoonDivider(card: @Composable () -> Unit) = Box {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Spacer(Modifier.width(2.dp).height(124.dp).background(Arcade.line))
        card()
    }
    Text(tr("Coming soon").uppercase(), color = Colors.dim, fontSize = 12.sp, style = Arcade.label, letterSpacing = 3.sp, maxLines = 1, softWrap = false,
        modifier = Modifier.align(Alignment.TopStart).offset(x = 26.dp, y = (-16).dp))
}

private fun LazyListScope.minesRow(c: RowCtx, key: String, label: String, row: Int, mines: List<io.github.pedrubik2000.kumapie.data.Mine>, watching: Boolean) = item(key = key) {
    WithTvScroll { Column {
        RowHead(label, rowNav(c.d.names, row), c.focus, row == 0)
        LazyRow(state = remember(mines) { LazyListState().also { c.focus.rows[row] = it } }, horizontalArrangement = Arrangement.spacedBy(24.dp), contentPadding = ROW_PADDING) {
            itemsIndexed(mines, key = { _, m -> m.anilistId }) { i, m ->
                val progress = m.episodes?.takeIf { watching && it > 0 }?.let { m.progress.toFloat() / it }
                Poster(m.cover, m.shown(), if (watching) m.progressLine() else "", progress, c.card(row, i, m.anilistId) { ListTarget(m) },
                    level = c.levels?.of(m.kitsuId)) { c.focus.leaving(row, i, m.anilistId); c.onDiscovered(m.found()) }
            }
        }
    } }
}

private fun LazyListScope.loginRow(c: RowCtx) = item(key = "login") {
    LoginLine(Modifier.padding(start = RAIL, top = 16.dp, bottom = 16.dp),
        (if (c.d.names.isEmpty()) Modifier.focusRequester(c.below) else Modifier).onFocusChanged { if (it.isFocused) { c.focus.row = c.d.names.size; c.scrollTo(c.d.names.size) } }
            .then(c.focus.start(LOGIN_START)), c.onLogin)
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
            val state = remember(list) { LazyListState().also { c.focus.rows[at] = it } }
            WithTvScroll { Shelf(label, inset = RAIL, state = state) {
                itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                    val start = c.d.names.isEmpty() && c.d.loggedIn && r == 0 && i == 0
                    Poster(s.poster, shortTitle(s.shown), levelLine(s), null,
                        (if (start) Modifier.focusRequester(c.below) else Modifier).onFocusChanged { if (it.isFocused) { c.focus.row = row; c.scrollTo(at) } }
                            .then(c.focus.card(at, s.id)).then(if (i == 0) c.focus.start(label) else Modifier)) { c.focus.leaving(at, i, s.id); c.onShow(s) }
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
