package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        if (runCatching { requestFocus() }.isSuccess) return
    }
}

suspend fun liveLists(settings: Settings): List<Mine>? =
    settings.withAniList { token -> AniListLists.refresh(settings.prefs, settings.anilist, token) }

@Composable
fun HomeScreen(api: Discovery, settings: Settings, profile: String, who: String, onShow: (Show) -> Unit,
               onPlay: (Show, Episode) -> Unit, onSettings: () -> Unit, onStats: () -> Unit, onProfiles: () -> Unit,
               onReview: (String) -> Unit = {}, onDiscover: () -> Unit = {}, onDiscovered: (Found) -> Unit = {}, onLibrary: () -> Unit = {},
               jiten: JitenQueue? = null, lists: suspend (Settings) -> List<Mine>? = ::liveLists) {
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
    val due by produceState<Int?>(null, lang) { value = lang?.let { runCatching {
        if (api is Api) api.srsDue(it).let { d -> d.due + d.new } else io.github.pedrubik2000.kumapie.lang.ownDueCount(context, it) }.getOrNull() } }
    val data = rememberHomeData(settings, lists, shows, lang, streams)
    val focus = remember(data != null) { HomeFocus(data?.resume) }
    val railFocus = remember { mutableMapOf<String, FocusRequester>() }
    val first = remember { FocusRequester() }
    val rail = listOfNotNull(
        RailItem(Icons.Default.Home, tr("Home"), selected = true) {},
        RailItem(Icons.Default.VideoLibrary, tr("Library"), onClick = onLibrary),
        RailItem(Icons.Default.Explore, tr("Discover"), onClick = onDiscover).takeIf { lang == "ja" },
        due?.let { n -> RailItem(Icons.Default.Style, tr("Review"), badge = n) { lang?.let(onReview) } },
        RailItem(Icons.Default.BarChart, tr("Stats"), onClick = onStats),
        RailItem(Icons.Default.Person, who, back = false, onClick = onProfiles).takeIf { api is Api },
        RailItem(Icons.Default.Settings, tr("Settings"), onClick = onSettings),
    )
    val settle = remember(data) { { data?.resume?.let { focus.target = it }; Unit } }
    Box(Modifier.fillMaxSize()) {
        when {
            error != null -> Box(Modifier.padding(start = RAIL, end = 32.dp)) { ErrorBox(error, onRetry = { attempt++ }, onSettings = onSettings) }
            shows == null || streams == null -> Text(tr("Loading…"), color = Colors.dim, modifier = Modifier.padding(start = RAIL, top = 100.dp))
            lang == null -> Text(tr("No shows on the server yet."), color = Colors.dim, modifier = Modifier.padding(start = RAIL, top = 100.dp))
            data != null -> HomeBody(data, focus, api, rememberLevels(rememberQueue(jiten)), first, railFocus, onShow, onPlay, onDiscovered, onSettings)
        }
        HomeHeader(totals, langs, lang) { next -> picked = next; settings.prefs.edit().putString("home_lang_$profile", next).apply() }
        Box(Modifier.padding(top = 90.dp).fillMaxHeight().width(RAIL).fadeTop(0.1f).background(Brush.horizontalGradient(
            0f to Colors.background.copy(alpha = 0.92f), 0.7f to Colors.background.copy(alpha = 0.75f), 1f to Color.Transparent)))
        Rail(rail, railFocus, first.takeIf { data != null }, Modifier.align(Alignment.TopStart).padding(top = 100.dp), onFocused = settle)
    }
}

@Composable
private fun rememberQueue(given: JitenQueue?): JitenQueue? {
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
internal fun Rail(items: List<RailItem>, focus: MutableMap<String, FocusRequester>, toRows: FocusRequester?, modifier: Modifier, onFocused: () -> Unit = {}) {
    Column(modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items.forEach { item -> androidx.compose.runtime.key(item.label) {
            var focused by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    androidx.tv.material3.IconButton(onClick = { if (item.back && !item.selected) railLast = item.label; item.onClick() },
                        modifier = Modifier.size(56.dp).focusRequester(focus.getOrPut(item.label) { FocusRequester() })
                            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }.focusProperties { toRows?.let { right = it } },
                        colors = androidx.tv.material3.IconButtonDefaults.colors(
                            containerColor = Colors.background.copy(alpha = 0.55f), contentColor = Color.White,
                            focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                        border = androidx.tv.material3.IconButtonDefaults.border(border = androidx.tv.material3.Border(
                            androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))))) {
                        androidx.tv.material3.Icon(item.icon, item.label, Modifier.size(26.dp))
                    }
                    if (item.badge > 0) Text(if (item.badge > 99) "99+" else "${item.badge}", color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopEnd).background(Colors.unknown, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 5.dp))
                }
                if (focused) Text(item.label, color = Color.White, fontSize = 16.sp, style = Arcade.label,
                    modifier = Modifier.padding(start = 12.dp).background(Colors.background.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
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
    val all by produceState(emptyList<Mine>(), user, loggedIn) {
        if (!loggedIn || user.isEmpty()) return@produceState
        value = withContext(Dispatchers.IO) { AniListLists.cached(settings.prefs, user) }
        runCatching { lists(settings) }.getOrNull()?.let { value = it }
    }
    if (shows == null || streams == null || lang == null) return null
    return remember(shows, streams, lang, all, loggedIn) {
        val mine = shows.filter { it.lang == lang }
        HomeData(continueRow(mine, streams), all, loggedIn, libraryRows(mine).map { (l, list) -> tr(l) to list })
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, onSettings: (() -> Unit)?, server: Boolean = true) {
    val retry = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Title(if (server) tr("Can't reach the dojo server") else tr("That didn't work"), fontSize = 26.sp)
            Text(if (server) plainError(message) else message, color = Colors.dim, modifier = Modifier.padding(vertical = 12.dp))
            if (server) Text(tr("Is the PC on, and Tailscale connected on the TV?"), color = Colors.dim)
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onRetry, modifier = Modifier.focusRequester(retry)) { Text(tr("Try again")) }
                if (onSettings != null) Button(onClick = onSettings) { Text(tr("Settings")) }
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
