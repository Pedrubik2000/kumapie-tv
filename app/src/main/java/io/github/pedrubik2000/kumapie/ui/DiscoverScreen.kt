package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.R
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.JitenQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DiscoverScreen(api: Discovery, settings: Settings, start: Found? = null,
                   onHome: () -> Unit = {}, onReview: () -> Unit = {}, onSettings: () -> Unit = {}, onLibrary: () -> Unit = {},
                   jiten: JitenQueue? = null, sync: AniListSync? = null, read: LibraryRead = AniListLists::cacheFirst) {
    remember { DiscoverMemory.forget() }
    var open by remember { mutableStateOf(start) }
    open?.let { f ->
        return Box(Modifier.onBackKey(start == null) { open = null }) {
            TitleScreen(api, f, { DiscoverRail(onHome, onReview, onSettings, onLibrary = onLibrary) }, jiten, sync)
        }
    }
    DiscoverBody(api, settings, jiten, read, DiscoverEdges(onHome, onReview, onSettings, onLibrary)) { open = it }
}

internal class DiscoverEdges(val onHome: () -> Unit, val onReview: () -> Unit, val onSettings: () -> Unit, val onLibrary: () -> Unit)

@Composable
private fun DiscoverBody(api: Discovery, settings: Settings, jiten: JitenQueue?, read: LibraryRead, edges: DiscoverEdges, onOpen: (Found) -> Unit) {
    val back = remember { DiscoverMemory.take() }
    val loggedIn = settings.anilistToken.isNotEmpty()
    val entry = remember { FocusRequester() }
    val rail = remember { mutableMapOf<String, FocusRequester>() }
    val live = remember { Live() }
    val link = remember { Link() }
    val lists by rememberLists(settings, read, back != null)
    val levels = rememberLevels(rememberQueue(jiten))
    KnowIds(levels, lists.orEmpty())
    var mode by remember { mutableStateOf(back?.mode ?: if (loggedIn) DiscoverMode.PICK else DiscoverMode.BROWSE) }
    val pick = remember { PickState(PickStore(settings.prefs)) }
    val browse = remember { BrowseState(back?.filters ?: BrowseFilters(limit = settings.browseLimit), settings) }
    val ranked = rememberRanked(lists)
    val picks = rememberPicks(ranked, pick)
    LoadBrowse(api, browse, mode == DiscoverMode.BROWSE)
    LaunchedEffect(Unit) { runCatching { api.saved().map { it.id }.toSet() }.onSuccess { pick.kept = it }.onFailure { pick.failed = true } }
    LaunchedEffect(Unit) { if (back?.cover == null) entry.focusSoon() }
    EntryWhenIdle(mode, mode == DiscoverMode.BROWSE, live, entry)
    val env = DiscoverEnv(api, settings, levels, link, entry, edges.onSettings) { f, cover -> DiscoverMemory.open(mode, cover, browse.filters); onOpen(f) }
    val clearSearch = mode == DiscoverMode.BROWSE && browse.filters.query.isNotEmpty()
    Box(live.track(Modifier.fillMaxSize()).onBackKey(clearSearch) { browse.pick(browse.filters.copy(query = "")) }) {
        when (mode) {
            DiscoverMode.PICK -> PickContent(env, picks, ranked, pick)
            DiscoverMode.DUEL -> DuelContent(env, ranked, pick)
            DiscoverMode.BROWSE -> BrowseContent(env, browse, lists, live, back?.takeIf { it.mode == DiscoverMode.BROWSE }?.cover)
        }
        DiscoverColumn(mode, entry, link, { rail[tr("Discover")] ?: FocusRequester.Default }, { mode = it }, Modifier.padding(start = RAIL, top = 28.dp)) { sides ->
            when (mode) {
                DiscoverMode.PICK -> NextPicks(picks?.next.orEmpty(), levels, sides) { pick.chosen = it.anilistId }
                DiscoverMode.BROWSE -> BrowseFilterRows(browse, loggedIn, sides)
                DiscoverMode.DUEL -> Unit
            }
        }
        DiscoverRail(edges.onHome, edges.onReview, edges.onSettings, onLibrary = edges.onLibrary, focus = rail, toRows = entry)
    }
}

@Composable
private fun rememberRanked(lists: List<Mine>?): Ranked? {
    val ranked by produceState<Ranked?>(null, lists) {
        value = lists?.let { l -> withContext(Dispatchers.Default) { Ranked(planningOrder(l), bestItems(l)) } }
    }
    return ranked
}

@Composable
private fun rememberPicks(ranked: Ranked?, p: PickState): Picks? = remember(ranked, p.hidden, p.kept, p.failed, p.chosen) {
    if (ranked == null || (p.kept == null && !p.failed)) null
    else {
        val left = ranked.list.filter { it.anilistId !in p.hidden && "kitsu:${it.kitsuId}" !in p.kept.orEmpty() }
        val current = left.firstOrNull { it.anilistId == p.chosen } ?: left.firstOrNull()
        Picks(current, left.filter { it !== current }.take(NEXT_PICKS))
    }
}

@Composable
internal fun LinkWhen(link: Link, ready: Boolean) {
    DisposableEffect(ready) {
        link.ready = ready
        onDispose { link.ready = false }
    }
}

@Composable
internal fun AskTitles(levels: Levels?, first: String?, ids: List<String>) {
    val owner = remember { Any() }
    DisposableEffect(levels) { onDispose { levels?.release(owner) } }
    LaunchedEffect(levels, first, ids) {
        levels ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            levels.drop(owner)
            first?.let { levels.ask(listOf(it), true) }
            levels.ask(ids, false, owner)
        }
    }
}

@Composable
internal fun BoxScope.DiscoverRail(onHome: () -> Unit, onReview: () -> Unit, onSettings: () -> Unit,
    onLibrary: () -> Unit = {}, onDiscover: () -> Unit = {}, library: Boolean = false, focus: MutableMap<String, FocusRequester> = remember { mutableMapOf() },
    toRows: FocusRequester? = null) {
    Image(painterResource(R.drawable.bear), "kumapie", Modifier.padding(start = 20.dp, top = 20.dp).size(64.dp))
    Box(Modifier.padding(top = 90.dp).fillMaxHeight().width(RAIL).background(Brush.horizontalGradient(
        0f to Colors.background.copy(alpha = 0.92f), 0.7f to Colors.background.copy(alpha = 0.75f), 1f to Color.Transparent)))
    Rail(listOf(
        RailItem(Icons.Default.Home, tr("Home"), back = false, onClick = onHome),
        RailItem(Icons.Default.VideoLibrary, tr("Library"), back = false, selected = library, onClick = onLibrary),
        RailItem(Icons.Default.Explore, tr("Discover"), back = false, selected = !library, onClick = onDiscover),
        RailItem(Icons.Default.Style, tr("Review"), back = false, onClick = onReview),
        RailItem(Icons.Default.Settings, tr("Settings"), back = false, onClick = onSettings),
    ), focus, toRows, Modifier.align(Alignment.TopStart).padding(top = 100.dp))
}

@Composable
internal fun TitleLogo(f: Found) {
    if (f.logo.isNotEmpty()) AsyncImage(f.logo, f.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        modifier = Modifier.padding(top = 4.dp).height(64.dp).widthIn(max = 420.dp))
    else Text(f.shownName, color = Color.White, fontSize = 38.sp, lineHeight = 44.sp, style = Arcade.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
