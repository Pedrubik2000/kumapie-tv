package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.R
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.LangTotal
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.lastPlayedIds
import io.github.pedrubik2000.kumapie.data.savedEpisodeIds
import io.github.pedrubik2000.kumapie.data.streamsThatExist
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.TitleLevels
import io.github.pedrubik2000.kumapie.data.continueRow
import io.github.pedrubik2000.kumapie.data.libraryRows
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.JitenQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun FocusRequester.focusSoon() {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return
    }
}

suspend fun liveLists(settings: Settings): List<Mine>? =
    settings.withAniList { token -> AniListLists.refresh(settings.prefs, settings.anilist, token) }

@Composable
fun HomeScreen(api: Discovery, settings: Settings, profile: String, who: String, onShow: (Show) -> Unit,
               onPlay: (Show, Episode) -> Unit, onSettings: () -> Unit, onStats: () -> Unit, onProfiles: () -> Unit,
               onReview: (String) -> Unit = {}, onDiscover: () -> Unit = {}, onDiscovered: (Found) -> Unit = {}, onLibrary: () -> Unit = {},
               jiten: JitenQueue? = null, lists: suspend (Settings) -> List<Mine>? = ::liveLists, memory: HomeMemory = remember { HomeMemory() }) {
    var attempt by remember { mutableIntStateOf(0) }
    val (shows, error) = loadShows(api, attempt)
    val langs = if (api !is Api) listOf("ja") else Lang.ALL.map { it.code }.filter { c -> shows.orEmpty().any { it.lang == c } }
    var picked by remember(profile) { mutableStateOf(settings.prefs.getString("home_lang_$profile", null)) }
    val lang = picked?.takeIf { it in langs } ?: langs.firstOrNull()
    val streams = loadStreams(api, lang, shows)
    val context = LocalContext.current
    val totals by produceState<LangTotal?>(null, lang) {
        value = lang?.let { l -> runCatching { if (api is Api) api.stats().langs[l] else aloneTotal(context, settings, l, shows.orEmpty()) }.getOrNull() }
    }
    val data = rememberHomeData(settings, lists, shows, lang, streams)
    val focus = remember(data != null) { HomeFocus(data?.resume, memory) }
    val railFocus = remember { mutableMapOf<String, FocusRequester>() }
    val first = remember { FocusRequester() }
    val rail = RailNav({}, onLibrary, onDiscover, { lang?.let(onReview) }, onStats, onSettings)
    val profileItem = RailItem(Icons.Default.Person, who, back = false, onClick = onProfiles).takeIf { api is Api }
    val settle = remember(data) { { if (focus.back == null) focus.target = data?.resume; Unit } }
    var contentFocused by remember { mutableStateOf(false) }
    var railFocused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().railGate().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) false
        else when (e.key) {
            Key.DirectionLeft -> { if (contentFocused && Hints.shown == HintKind.HERO) focus.back = null; false }
            Key.DirectionRight -> railFocused && focus.returnFromRail()
            else -> false
        }
    }) {
        Box(Modifier.fillMaxSize().onFocusChanged { contentFocused = it.hasFocus }.focusGroup()) {
            when {
                error != null -> Box(Modifier.padding(start = RAIL, end = 32.dp)) { ErrorBox(error, onRetry = { attempt++ }, onSettings = onSettings) }
                shows == null || streams == null -> Text(tr("Loading…"), color = Colors.dim, modifier = Modifier.padding(start = RAIL, top = 100.dp))
                lang == null -> Text(tr("No shows on the server yet."), color = Colors.dim, modifier = Modifier.padding(start = RAIL, top = 100.dp))
                data != null -> HomeBody(data, focus, api, rememberLevels(rememberQueue(jiten)), first, railFocus, onShow, onPlay, onDiscovered, onSettings, onDiscover) { contentFocused || railFocused }
            }
        }
        RailBackdrop()
        HomeHeader(totals, langs, lang) { next -> picked = next; settings.prefs.edit().putString("home_lang_$profile", next).apply() }
        MainRail(api, lang, RailAt.HOME, rail, railFocus, first.takeIf { data != null }, profileItem, settle) { railFocused = it }
        HomeHintBar(focus)
    }
}

@Composable
internal fun rememberQueue(given: JitenQueue?): JitenQueue? {
    val context = LocalContext.current
    val queue by produceState(given) { if (value == null) value = withContext(Dispatchers.IO) { TitleLevels.of(context) } }
    return queue
}

@Composable
private fun HomeHeader(totals: LangTotal?, langs: List<String>, lang: String?, onLang: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 32.dp, top = 20.dp)) {
        Image(painterResource(R.drawable.bear), "kumapie", Modifier.align(Alignment.TopStart).size(64.dp))
        Row(Modifier.align(Alignment.TopEnd).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            totals?.let { Text(statsLine(it), color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp) }
            if (langs.size > 1) Button(padding = TAB, onClick = { onLang(langs[(langs.indexOf(lang) + 1) % langs.size]) }) {
                Text((lang?.let { Lang.of(it).displayName } ?: "") + "  ▾")
            }
        }
    }
}

internal val RAIL = 124.dp

internal class RailItem(val icon: ImageVector, val label: String, val badge: Int = 0,
                       val selected: Boolean = false, val back: Boolean = true, val onClick: () -> Unit)

private var railLast: String? = null

internal fun takeRailLast(): String? = railLast.also { railLast = null }

@Composable
private fun BoxScope.HomeHintBar(focus: HomeFocus) = HintBar(top = rowsTop(focus.tall) + 4.dp)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun Rail(items: List<RailItem>, focus: MutableMap<String, FocusRequester>, toRows: FocusRequester?, modifier: Modifier, onFocused: () -> Unit = {}, onHasFocus: (Boolean) -> Unit = {}) {
    val current = items.firstOrNull { it.selected }?.label
    Column(modifier.onFocusChanged { onHasFocus(it.hasFocus) }.padding(start = 24.dp).focusProperties {
        enter = { if (it == FocusDirection.Up || it == FocusDirection.Down) FocusRequester.Cancel else FocusRequester.Default }
    }.focusGroup(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items.forEach { item -> androidx.compose.runtime.key(item.label) {
            Box {
                androidx.tv.material3.IconButton(onClick = { railLast = item.label.takeIf { item.back && !item.selected }; item.onClick() },
                    modifier = Modifier.size(56.dp).focusRequester(focus.getOrPut(item.label) { FocusRequester() })
                        .hint(if (item.selected) HintKind.RAIL_HERE else HintKind.RAIL, item.label)
                        .onFocusChanged {
                            if (it.isFocused) {
                                onFocused()
                                if (RailGate.left && current != null && item.label != current) {
                                    RailGate.left = false
                                    runCatching { focus[current]?.requestFocus() }
                                }
                            }
                        }.focusProperties { toRows?.let { right = it } },
                    colors = androidx.tv.material3.IconButtonDefaults.colors(
                        containerColor = Colors.background.copy(alpha = 0.55f), contentColor = if (item.selected) Arcade.accent else Color.White,
                        focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                    border = androidx.tv.material3.IconButtonDefaults.border(border = androidx.tv.material3.Border(
                        if (item.selected) androidx.compose.foundation.BorderStroke(2.dp, Arcade.accent)
                        else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))))) {
                    androidx.tv.material3.Icon(item.icon, item.label, Modifier.size(26.dp))
                }
                if (item.badge > 0) Text(if (item.badge > 99) "99+" else "${item.badge}", color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.zIndex(2f).align(Alignment.TopEnd).background(Colors.unknown, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 5.dp))
            }
        } }
    }
}

internal val TAB = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

private suspend fun aloneTotal(context: android.content.Context, settings: Settings, lang: String, shows: List<Show>) = withContext(Dispatchers.IO) {
    val p = io.github.pedrubik2000.kumapie.lang.Progress
    p.homeTotal(lang, p.of(context).merged, shows, tvLanguages(context, settings).of(lang).known.knownList().size).takeIf { it.total > 0 }
}

@Composable
private fun loadShows(api: Discovery, attempt: Int): Pair<List<Show>?, String?> {
    var shows by remember { mutableStateOf<List<Show>?>(if (api is Api) null else emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { if (api is Api) api.shows() else emptyList() }
            .onSuccess { all -> shows = all.filter { it.kind != "reels" }.map { s -> s.copy(episodes = s.episodes.filter { it.scenes > 0 }) }
                .filter { it.episodes.isNotEmpty() } }
            .onFailure { error = it.message ?: it.toString() }
    }
    return shows to error
}

@Composable
private fun loadStreams(api: Discovery, lang: String?, shows: List<Show>?): List<Pair<Show, Episode>>? {
    var streams by remember { mutableStateOf(if (api is Api) null else runCatching { api.goingNow() }.getOrNull()) }
    var streamsLang by remember { mutableStateOf(if (streams != null) "ja" else null) }
    LaunchedEffect(lang, shows == null) {
        if (shows == null) return@LaunchedEffect
        if (streamsLang != lang) streams = null
        val fresh = if (lang == "ja") runCatching { api.going() }.getOrDefault(emptyList()) else emptyList()
        if (fresh != streams) streams = fresh
        streamsLang = lang
    }
    return streams
}

@Composable
private fun rememberHomeData(settings: Settings, lists: suspend (Settings) -> List<Mine>?, shows: List<Show>?, lang: String?, streams: List<Pair<Show, Episode>>?): HomeData? {
    val user = settings.anilist
    val loggedIn = settings.anilistToken.isNotEmpty()
    val noLists = !loggedIn || user.isEmpty()
    val all by produceState<List<Mine>?>(if (noLists) emptyList() else null, user, loggedIn) {
        if (noLists) { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.IO) { AniListLists.cached(settings.prefs, user) }
        runCatching { lists(settings) }.getOrNull()?.let { value = it }
    }
    val context = LocalContext.current
    val facts by produceState(streams?.takeIf { it.isEmpty() }?.let { StreamFacts(it, emptyMap(), emptyMap()) }, streams) {
        val shown = streams ?: return@produceState
        value = withContext(Dispatchers.IO) {
            StreamFacts(shown, savedEpisodeIds(context, shown), lastPlayedIds(settings.prefs.all))
        }
    }
    val everyone = all
    val known = facts?.takeIf { it.of === streams } ?: facts
    if (shows == null || streams == null || lang == null || everyone == null || known == null) return null
    return remember(shows, streams, lang, everyone, loggedIn, known) {
        val mine = shows.filter { it.lang == lang }
        HomeData(continueRow(mine, streamsThatExist(streams, everyone, known.metaIds, known.lastPlayed)), everyone, loggedIn,
            libraryRows(mine).map { (l, list) -> tr(l) to list }, known.metaIds)
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun ErrorBox(message: String, onRetry: () -> Unit, onSettings: (() -> Unit)?, server: Boolean = true, settingsLabel: String = tr("Settings"), hint: String? = null) {
    val retry = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Title(if (server) tr("Can't reach the dojo server") else tr("That didn't work"), fontSize = 26.sp)
            Text(if (server) plainError(message) else message, color = Colors.dim, modifier = Modifier.padding(vertical = 12.dp))
            if (server) Text(tr("Is the PC on, and Tailscale connected on the TV?"), color = Colors.dim)
            hint?.let { Text(it, color = Colors.dim) }
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry, modifier = Modifier.focusRequester(retry)) { Text(tr("Try again"), maxLines = 1, softWrap = false) }
                if (onSettings != null) Button(onClick = onSettings) { Text(settingsLabel, maxLines = 1, softWrap = false) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
}

internal fun plainError(m: String): String = m.lowercase().let {
    when {
        "end of stream" in it || "reset" in it || "aborted" in it -> tr("The connection to the PC was cut.")
        "timeout" in it || "timed out" in it -> tr("The PC took too long to answer.")
        "failed to connect" in it || "refused" in it || "unable to resolve" in it || "unreachable" in it -> tr("The PC didn't answer.")
        else -> m
    }
}

private class StreamFacts(val of: List<Pair<Show, Episode>>, val metaIds: Map<String, Set<String>>, val lastPlayed: Map<String, String>)
