package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.onFocusChanged
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
                   onStats: () -> Unit = {}, jiten: JitenQueue? = null, sync: AniListSync? = null, read: LibraryRead = AniListLists::cacheFirst,
                   onDiscover: () -> Unit = {}) {
    var open by remember { mutableStateOf(start) }
    val nav = RailNav(onHome, onLibrary, {}, onReview, onStats, onSettings)
    val titleNav = RailNav(onHome, onLibrary, if (start == null) ({ open = null }) else onDiscover, onReview, onStats, onSettings)
    val lists by rememberLists(settings, read, start != null)
    val hold = remember {
        DiscoverHold(if (settings.anilistToken.isNotEmpty()) DiscoverMode.PICK else DiscoverMode.BROWSE, PickState(PickStore(settings.prefs)),
            BrowseState(BrowseFilters(limit = settings.browseLimit), settings))
    }
    DisposableEffect(Unit) {
        onDispose {
            MissingKeyLine.from = null
            MissingKeyLine.home = null
        }
    }
    val frozen = hold.inContent || open != null || hold.restoring
    val ranked = rememberFrozen(rememberRanked(lists), frozen)
    val kept = rememberFrozen(hold.pick.loaded, frozen)
    LoadPicks(api, hold.pick, start == null)
    open?.let { f ->
        return Box(Modifier.onBackKey(start == null) { open = null }) {
            TitleScreen(api, f, { DiscoverRail(api, titleNav, null) }, jiten, sync, onSettings)
        }
    }
    DiscoverBody(api, settings, jiten, nav, lists, ranked, kept?.ids.orEmpty(), hold) { open = it }
}

@Composable
private fun DiscoverBody(api: Discovery, settings: Settings, jiten: JitenQueue?, edges: RailNav, lists: List<Mine>?, ranked: Ranked?, kept: Set<String>,
                         hold: DiscoverHold, onOpen: (Found) -> Unit) {
    val loggedIn = settings.anilistToken.isNotEmpty()
    val entry = remember { FocusRequester() }
    val rail = remember { mutableMapOf<String, FocusRequester>() }
    val live = remember { Live() }
    val link = remember { Link() }
    val returning = remember { hold.returning }
    val levels = rememberLevels(rememberQueue(jiten))
    KnowIds(levels, lists.orEmpty())
    val mode = hold.mode
    val pick = hold.pick
    val browse = hold.browse
    val field = remember(ranked, pick.hidden, kept) { ranked?.let { candidates(it.list, pick.hidden, kept) } }
    val picks = remember(field, pick.chosen, pick.ready) { if (field == null || !pick.ready) null else pickFrom(field, pick.chosen) }
    DisposableEffect(Unit) { onDispose { hold.inContent = false } }
    DisposableEffect(entry) {
        MissingKeyLine.home = entry
        onDispose { if (MissingKeyLine.home === entry) MissingKeyLine.home = null }
    }
    LaunchedEffect(live.touched.value) { if (live.touched.value) hold.dropRestore() }
    LoadBrowse(api, browse, mode == DiscoverMode.BROWSE)
    LaunchedEffect(Unit) { if (!returning) entry.focusSoon() }
    LaunchedEffect(Unit) { hold.giveUpRestoring() }
    EntryWhenIdle(mode, mode == DiscoverMode.BROWSE || hold.restoring, live, entry)
    val env = DiscoverEnv(api, settings, levels, link, entry, edges.onSettings, hold, onOpen)
    val clearSearch = mode == DiscoverMode.BROWSE && browse.filters.query.isNotEmpty()
    Box(live.track(Modifier.fillMaxSize()).onBackKey(clearSearch) { runCatching { entry.requestFocus() }; browse.pick(browse.filters.copy(query = "")) }) {
        Box(Modifier.fillMaxSize().onFocusChanged { hold.inContent = it.hasFocus }) {
            when (mode) {
                DiscoverMode.PICK -> PickContent(env, picks, ranked, pick)
                DiscoverMode.DUEL -> DuelContent(env, field.takeIf { pick.ready }, ranked, pick)
                DiscoverMode.BROWSE -> BrowseContent(env, browse, lists, live)
            }
        }
        DiscoverColumn(mode, entry, link, { rail[tr("Discover")] ?: FocusRequester.Default }, { hold.pick.modeChanged(hold.mode, it); hold.mode = it }, Modifier.padding(start = RAIL, top = 28.dp)) { sides ->
            when (mode) {
                DiscoverMode.PICK -> NextPicks(picks?.next.orEmpty(), levels, sides) { pick.chosen = it.anilistId }
                DiscoverMode.BROWSE -> BrowseFilterRows(browse, loggedIn, sides)
                DiscoverMode.DUEL -> Unit
            }
        }
        DiscoverRail(api, edges, RailAt.DISCOVER, rail, entry)
        HintBar()
    }
}

private fun pickFrom(field: List<Mine>, chosen: Int?): Picks {
    val current = field.firstOrNull { it.anilistId == chosen } ?: field.firstOrNull()
    return Picks(current, field.filter { it !== current }.take(NEXT_PICKS))
}

@Composable
private fun rememberRanked(lists: List<Mine>?): Ranked? {
    val ranked by produceState<Ranked?>(null, lists) {
        value = lists?.let { l -> withContext(Dispatchers.Default) { Ranked(planningOrder(l), bestItems(l)) } }
    }
    return ranked
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
internal fun BoxScope.DiscoverRail(api: Discovery, nav: RailNav, at: RailAt?, focus: MutableMap<String, FocusRequester> = remember { mutableMapOf() },
    toRows: FocusRequester? = null) {
    RailBackdrop()
    Image(painterResource(R.drawable.bear), "kumapie", Modifier.padding(start = 20.dp, top = 20.dp).size(64.dp))
    MainRail(api, "ja", at, nav, focus, toRows)
}

@Composable
internal fun TitleLogo(f: Found) {
    if (f.logo.isNotEmpty()) AsyncImage(f.logo, f.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        modifier = Modifier.padding(top = 4.dp).height(64.dp).widthIn(max = 420.dp))
    else Text(f.shownName, color = Color.White, fontSize = 38.sp, lineHeight = 44.sp, style = Arcade.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
