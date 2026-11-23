package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.StreamActivity
import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Backdrops
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.SavedMeta
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.TitlePage
import io.github.pedrubik2000.kumapie.data.partway
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.JitenQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class TitleFocus {
    val main = FocusRequester()
    val status = FocusRequester()
    val score = FocusRequester()
    val episodes = FocusRequester()
}

@Composable
private fun rememberAniState(settings: Settings, kitsu: String?, lists: suspend (Settings) -> List<Mine>?): AniState {
    val user = settings.anilist
    val on = settings.anilistToken.isNotEmpty() && user.isNotEmpty() && kitsu != null
    val state by produceState(AniState(null, !on, false), kitsu, on) {
        if (!on) return@produceState
        val cached = withContext(Dispatchers.IO) { AniListLists.cached(settings.prefs, user) }
        if (cached.isNotEmpty()) value = AniState(cached.firstOrNull { it.kitsuId == kitsu }, true, false)
        val fresh = runCatching { lists(settings) }.getOrNull()
        if (fresh != null) value = AniState(fresh.firstOrNull { it.kitsuId == kitsu }, true, false)
        else if (!value.ready) value = AniState(null, false, true)
    }
    return state
}

private fun titleFinished(mine: Mine?, eps: List<JSONObject>): Boolean =
    mine?.let { it.status == "COMPLETED" || it.episodes?.let { n -> it.progress >= n } == true } == true ||
        eps.isNotEmpty() && eps.all { it.optBoolean("watched") }

@Composable
private fun TitleBackdrop(api: Discovery, title: Found) {
    val context = LocalContext.current
    var picture by remember(title.id) { mutableStateOf(Backdrops.saved(context, title.id) ?: title.background.ifEmpty { title.poster }) }
    LaunchedEffect(title.id) { Backdrops.get(context, api, title.type, title.id)?.let { picture = it } }
    Box(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        Cover(picture, "", Modifier.fillMaxSize(), showTitle = false, alignment = Alignment.TopCenter, description = null)
        Box(Modifier.fillMaxSize().background(SCRIM_LEFT))
        Box(Modifier.fillMaxSize().background(SCRIM_BOTTOM))
        Box(Modifier.fillMaxSize().background(SCRIM_TOP))
    }
}

@Composable
internal fun TitleScreen(api: Discovery, title: Found, rail: @Composable BoxScope.() -> Unit, jiten: JitenQueue?,
                         lists: suspend (Settings) -> List<Mine>?, sync: AniListSync?) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val page = remember(title.id) {
        TitlePage(api, title) {
            withContext(Dispatchers.IO) { SavedMeta.put(context, title.id, it) }
            Backdrops.put(context, title.id, it.optString("background"))
        }
    }
    val writer = sync ?: remember { (api as? Addons)?.sync ?: AniListSync(settings.prefs) }
    val focus = remember { TitleFocus() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(page) { page.loadKept() }
    LaunchedEffect(page) { page.load(withContext(Dispatchers.IO) { SavedMeta.get(context, title.id) }) }
    LaunchedEffect(page.picking) { page.loadFiles() }
    val kitsu = AniListSync.titleOf(title.id)?.removePrefix("kitsu:")
    val loggedIn = settings.anilistToken.isNotEmpty()
    val ani = rememberAniState(settings, kitsu, lists)
    val side = page.error != null || page.meta == null || page.picking != null
    val finished = titleFinished(ani.mine, page.episodes)
    val cardOn = kitsu != null && !side
    LaunchedEffect(page.meta != null, side, ani.ready) {
        if (side || !ani.ready) return@LaunchedEffect
        (if (finished && ani.mine != null) focus.score else focus.main).focusSoon()
    }
    val play: (JSONObject?) -> Unit = { e -> page.play(e)?.let { context.startActivity(StreamActivity.intent(context, it)) } }
    Box(Modifier.fillMaxSize().onBackKey(page.picking != null) { page.closeFiles() }) {
        TitleBackdrop(api, title)
        Row(Modifier.fillMaxSize().padding(start = RAIL, top = 40.dp, end = 32.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            TitleInfo(page, ani.mine, rememberLevels(rememberQueue(jiten)), kitsu, finished, side, cardOn && loggedIn, focus, play)
            if (side) TitleSide(api, page, focus, Modifier.weight(1f))
        }
        if (cardOn) AniListCard(loggedIn, ani, settings.anilistFormat, finished, page.next?.optInt("number")?.takeIf { it > 0 }, focus,
            { writer.status(title.id, it) }, { writer.score(title.id, it) }, Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 32.dp))
        if (page.meta != null && !side) EpisodeRow(page, settings.blurUnwatched, focus, play, { e -> scope.launch { page.mark(e) } },
            Modifier.align(Alignment.BottomStart).padding(bottom = 16.dp))
        rail()
    }
}

@Composable
private fun TitleInfo(page: TitlePage, mine: Mine?, levels: Levels?, kitsu: String?, finished: Boolean, side: Boolean, toCard: Boolean,
                      focus: TitleFocus, onPlay: (JSONObject?) -> Unit) {
    val t = page.shown
    val next = page.next
    val scope = rememberCoroutineScope()
    LaunchedEffect(levels, kitsu) { if (kitsu != null) withContext(Dispatchers.Default) { levels?.ask(listOf(kitsu), true) } }
    val cardRight = if (!side && toCard) focus.status else FocusRequester.Default
    Column(Modifier.width(520.dp)) {
        Eyebrow(factLine(kindText(mine), (mine?.year?.takeIf { it > 0 }?.toString() ?: t.year).ifEmpty { null },
            episodesText(mine?.episodes ?: page.episodes.size)).uppercase())
        TitleLogo(t)
        Text(factLine(t.genres.ifEmpty { mine?.genres?.joinToString(", ") }), color = WHITE_DIM, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp))
        levels?.of(kitsu)?.let { Box(Modifier.padding(top = 10.dp)) { DifficultyLine(it) } }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page.meta != null) Button(onClick = { onPlay(next) }, modifier = Modifier.focusRequester(focus.main)
                .focusProperties { if (!side) down = focus.episodes; if (page.kept == null) right = cardRight }) { Text("▶ " + playLabel(page, next, finished)) }
            page.kept?.let { on ->
                Button(onClick = { scope.launch { page.keep() } }, modifier = Modifier.focusProperties { if (!side) down = focus.episodes; right = cardRight }) {
                    Text(if (on) "✓ " + tr("On Home") else "+ " + tr("Home"))
                }
            }
        }
    }
}

private fun kindText(mine: Mine?): String? = mine?.format?.takeIf { it.isNotEmpty() }?.let { if (it == "MOVIE") tr("Movie") else it.replace('_', ' ') }

private fun playLabel(page: TitlePage, next: JSONObject?, finished: Boolean): String {
    val n = next?.optInt("number") ?: return tr("Watch")
    val going = page.resuming?.let { it.id == next.optString("id") && partway(it.resume, it.duration) } == true
    return when {
        finished -> tr("Watch again from Ep %d", n)
        going -> tr("Resume Ep %d", n)
        else -> tr("Episode %1\$d", n) + (if (next.optBoolean("ready") && next.optString("source").isNotEmpty()) " · " + next.optString("source") else "")
    }
}

@Composable
private fun TitleSide(api: Discovery, page: TitlePage, focus: TitleFocus, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val pick = page.picking
    Box(modifier.focusProperties {
        onExit = { if (requestedFocusDirection == FocusDirection.Left && page.meta != null) { cancelFocusChange(); focus.main.requestFocus() } }
    }.focusGroup()) {
        when {
            page.error != null -> ErrorBox(page.error.orEmpty(), onRetry = { scope.launch { page.retry() } }, onSettings = null, server = api is Api)
            page.meta == null -> Text(tr("Loading…"), color = Colors.dim)
            pick != null -> PickFiles(api, page, pick)
        }
    }
}

@Composable
private fun PickFiles(api: Discovery, page: TitlePage, pick: Pair<String, String>) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val list = remember(page.files) {
        page.files?.map { f -> page.missing(f).let { no -> if (no.isEmpty()) f else JSONObject(f.toString()).put("label", "✗ " + tr("May not play here: %1\$s", no.joinToString(", ")) + " · " + f.optString("label")) } }
    }
    Column {
        Heading(pick.second)
        when {
            list == null -> Text(if (api is Api) tr("Asking Torrentio…") else tr("Asking your addons…"), color = Colors.dim)
            list.isEmpty() -> Text(tr("No files found for this"), color = Colors.dim)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(12.dp)) {
                itemsIndexed(list) { i, f ->
                    Line(i, f.optString("label"), f.optString("about")) {
                        scope.launch { context.startActivity(StreamActivity.intent(context, page.pick(pick, f))) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(i: Int, text: String, line: String, onClick: () -> Unit) {
    val first = remember { FocusRequester() }
    Card(onClick = onClick, shape = arcadeCardShape(RowSlab), border = arcadeCardBorder(RowSlab), modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)) {
        Column(Modifier.padding(12.dp)) {
            Text(text, color = Colors.text, fontSize = 18.sp)
            Text(line, color = Colors.dim, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
    if (i == 0) LaunchedEffect(Unit) { first.focusSoon() }
}
