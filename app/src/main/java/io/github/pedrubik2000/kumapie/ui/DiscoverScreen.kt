package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.StreamActivity
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Found
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.launch
import io.github.pedrubik2000.kumapie.data.objects
import io.github.pedrubik2000.kumapie.i18n.tr
import org.json.JSONObject

/**
 * Discover (kumapie-server discover.py), laid out like Stremio's and Nuvio's TV board: the focused title's backdrop,
 * logo, rating, genres and text on top, rows of posters under it (the person's AniList Planning list first, then
 * Kitsu's catalogs); a search shows a grid. A title's files go to [StreamActivity] with kumapie://watch?…: the PC
 * makes the words, the TV streams Real-Debrid's link.
 */
@Composable
fun DiscoverScreen(api: io.github.pedrubik2000.kumapie.data.Discovery, start: Found? = null,
                   onHome: () -> Unit = {}, onReview: () -> Unit = {}, onSettings: () -> Unit = {}, onLibrary: () -> Unit = {}) {
    val rail: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = { DiscoverRail(onHome, onReview, onSettings, onLibrary = onLibrary) }
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Found>?>(null) }
    var rows by remember { mutableStateOf<List<Pair<String, List<Found>>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(start) } // a title kept on Home: its page at once (Back: Home)
    var focused by remember { mutableStateOf<Found?>(null) }
    val first = remember { FocusRequester() }
    var attempt by remember { mutableStateOf(0) } // Try again after a failed load (a bare message had no way out)
    LaunchedEffect(attempt) {
        error = null
        if (start == null) {
            if (rows == null) rows = (api as? io.github.pedrubik2000.kumapie.data.Addons)?.lastBoard() // last time's rows at once; the fresh ones replace them
            runCatching { api.board() }.onSuccess { rows = it }.onFailure { if (rows == null) error = it.message ?: it.toString() }
        }
    }
    LaunchedEffect(searched, attempt) {
        found = null
        if (searched.isEmpty()) return@LaunchedEffect
        error = null
        runCatching { api.discover(searched) }.onSuccess { found = it }.onFailure { error = it.message ?: it.toString() }
    }
    var row by remember { mutableIntStateOf(0) } // the focused poster's row: its name over the hero
    var from by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val rowsState = androidx.compose.foundation.lazy.rememberLazyListState()
    val rowStates = remember { mutableMapOf<Int, androidx.compose.foundation.lazy.LazyListState>() }
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    open?.let { return Box(Modifier.onBackKey(start == null) { open = null }) { TitleScreen(api, it, rail) } }
    val board = rows
    var kept by remember { mutableStateOf<Set<String>?>(null) } // the titles on Home, for the hero's + Home
    LaunchedEffect(Unit) { kept = runCatching { api.saved().map { it.id }.toSet() }.getOrNull() }
    val scope = rememberCoroutineScope()
    // onBackKey, not BackHandler: the first Back left Discover (Chromecast, 2026-11-17).
    Box(Modifier.fillMaxSize().onBackKey(searched.isNotEmpty()) { searched = ""; query = "" }) {
        val shown = focused ?: board?.firstOrNull()?.second?.firstOrNull()
        // The title page's picture (Backdrops), asked once the focus rests on a title; the row's banner meanwhile.
        val context = LocalContext.current
        var big by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(shown?.id) {
            big = shown?.let { io.github.pedrubik2000.kumapie.data.Backdrops.saved(context, it.id) }
            val s = shown ?: return@LaunchedEffect
            if (big != null) return@LaunchedEffect
            kotlinx.coroutines.delay(400)
            big = io.github.pedrubik2000.kumapie.data.Backdrops.get(context, api, s.type, s.id)
        }
        if (searched.isEmpty() && shown != null) Backdrop(big ?: shown.background.ifEmpty { shown.poster })
        // No side padding on the rows: it cut the posters with a straight line at both edges.
        Column(Modifier.fillMaxSize().padding(top = 24.dp, bottom = 4.dp)) {
            val list = found
            Column(Modifier.padding(start = RAIL, end = 32.dp)) { when {
                error != null -> ErrorBox(error!!, onRetry = { attempt++ }, onSettings = null, server = api is Api)
                searched.isNotEmpty() && list == null || searched.isEmpty() && board == null -> Text(tr("Loading…"), color = Colors.dim)
                searched.isNotEmpty() && list!!.isEmpty() -> Text(tr("Nothing found"), color = Colors.dim, fontSize = 20.sp, modifier = Modifier.padding(top = 64.dp))
                searched.isNotEmpty() -> {
                    Heading(searched, Modifier.padding(top = 56.dp))
                    val back = list!!.any { -1 to it.id == from }
                    LazyVerticalGrid(GridCells.Adaptive(130.dp), state = grid, contentPadding = PaddingValues(vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        itemsIndexed(list.distinctBy { it.id }, key = { _, f -> f.id }) { i, f ->
                            Poster(f.poster, f.shownName, f.year, null, if (if (back) -1 to f.id == from else i == 0) Modifier.focusRequester(first) else Modifier) { from = -1 to f.id; open = f }
                        }
                    }
                }
                else -> {}
            } }
            if (error == null && searched.isEmpty() && board != null) {
                // As Home's picture (mockups 2026-11-18): the focused title's row, name, rating and genres, the start of
                // its text, then ▶ (its page) and + Home.
                Column(Modifier.padding(start = RAIL, top = 16.dp).fillMaxWidth(0.55f)) {
                    shown?.let { f ->
                        Text(tr(board!!.getOrNull(row)?.first.orEmpty()).uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, letterSpacing = 3.sp, style = Arcade.label)
                        TitleLogo(f)
                        Text(f.line, color = Colors.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text(f.description.replace(Regex("""\s+"""), " "), color = Colors.dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { from = row to f.id; open = f }) { Text("▶  " + tr("Watch")) }
                            kept?.let { k ->
                                val on = f.id in k
                                Button(onClick = { scope.launch { runCatching { api.save(f, !on) }.onSuccess { l -> kept = l.map { it.id }.toSet() } } }) {
                                    Text(if (on) "✓ " + tr("On Home") else "+ " + tr("Home"))
                                }
                            }
                        }
                    }
                }
                // The top edge fades out: a row scrolled up peeked its years out under the text (Chromecast).
                // The focused row goes to the top of the list: left where the D-pad put it, the row above showed its
                // titles and years under the text, and the focused row's years were cut at the bottom.
                LaunchedEffect(row) { rowsState.animateScrollToItem(row) }
                val back = from?.let { (r, id) -> board.getOrNull(r)?.second?.any { it.id == id } } == true
                LazyColumn(Modifier.fillMaxSize().fadeTop(0.06f), state = rowsState) {
                    board!!.forEachIndexed { r, (name, titles) ->
                        item(key = "row-$r") {
                            Heading(tr(name), Modifier.padding(start = RAIL, top = 16.dp))
                            LazyRow(state = rowStates.getOrPut(r) { androidx.compose.foundation.lazy.LazyListState() }, horizontalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(start = RAIL, end = 48.dp, top = 8.dp, bottom = 8.dp)) {
                                itemsIndexed(titles.distinctBy { it.id }, key = { _, f -> f.id }) { i, f ->
                                    Poster(f.poster, f.shownName, f.year, null, (if (if (back) r to f.id == from else r == 0 && i == 0) Modifier.focusRequester(first) else Modifier)
                                        .onFocusChanged { if (it.isFocused) { focused = f; row = r } }) { from = r to f.id; open = f }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Search, top right, as the mockup's pill.
        TvTextField(query, { query = it }, onDone = { searched = query.trim() }, Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp).width(360.dp), placeholder = tr("Search"))
        rail()
    }
    LaunchedEffect(found, rows) { if (!found.isNullOrEmpty() || !rows.isNullOrEmpty()) first.focusSoon() }
}

/** Home's left edge on Discover's pages: the bear, a dark strip and the round buttons, Discover selected. */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.DiscoverRail(onHome: () -> Unit, onReview: () -> Unit, onSettings: () -> Unit,
    onLibrary: () -> Unit = {}, onDiscover: () -> Unit = {}, library: Boolean = false, focus: MutableMap<String, FocusRequester> = remember { mutableMapOf() }) {
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(io.github.pedrubik2000.kumapie.R.drawable.bear), "kumapie",
        Modifier.padding(start = 20.dp, top = 20.dp).size(64.dp))
    Box(Modifier.padding(top = 90.dp).fillMaxHeight().width(RAIL).background(Brush.horizontalGradient(
        0f to Colors.background.copy(alpha = 0.92f), 0.7f to Colors.background.copy(alpha = 0.75f), 1f to Color.Transparent)))
    Rail(listOf(
        RailItem(androidx.compose.material.icons.Icons.Default.Home, tr("Home"), back = false, onClick = onHome),
        RailItem(androidx.compose.material.icons.Icons.Default.VideoLibrary, tr("Library"), back = false, selected = library, onClick = onLibrary),
        RailItem(androidx.compose.material.icons.Icons.Default.Explore, tr("Discover"), back = false, selected = !library, onClick = onDiscover),
        RailItem(androidx.compose.material.icons.Icons.Default.Style, tr("Review"), back = false, onClick = onReview),
        RailItem(androidx.compose.material.icons.Icons.Default.Settings, tr("Settings"), back = false, onClick = onSettings),
    ), focus, null, Modifier.align(Alignment.TopStart).padding(top = 100.dp))
}

/** A backdrop over the whole screen, dark where the text and rows are (left and bottom). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Backdrop(image: String) {
    // Picture and gradients drawn once into a layer: two full-screen gradients redrawn on every frame took the
    // Chromecast's GPU 33-76 ms a frame while the stream list came in (10-30 without them, 2026-11-19).
    Box(Modifier.matchParentSize().graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)) {
        CoverPicture(image, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Colors.background, 0.6f to Colors.background.copy(alpha = 0.55f), 1f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to Colors.background)))
    }
}

/** The title's logo (fanart.tv), else its name. */
@Composable
private fun TitleLogo(f: Found) {
    if (f.logo.isNotEmpty()) AsyncImage(f.logo, f.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        modifier = Modifier.padding(top = 4.dp).height(64.dp).widthIn(max = 420.dp))
    else Text(f.shownName, color = Color.White, fontSize = 38.sp, lineHeight = 44.sp, style = Arcade.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * A title, Stremio's way: its backdrop, logo, rating, runtime and genres, ▶ (the episode after the last watched, else
 * the first the PC got ready, else the first one's files), + HOME and its text on the left; its episodes (or the picked
 * one's files) on the right. Holding OK on an episode offers other files and watched / not watched (✓).
 */
@Composable
private fun TitleScreen(api: io.github.pedrubik2000.kumapie.data.Discovery, title: Found,
                        rail: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    val context = LocalContext.current
    val page = remember(title.id) {
        io.github.pedrubik2000.kumapie.data.TitlePage(api, title) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.pedrubik2000.kumapie.data.SavedMeta.put(context, title.id, it) }
            io.github.pedrubik2000.kumapie.data.Backdrops.put(context, title.id, it.optString("background"))
        }
    }
    var menu by remember { mutableStateOf<JSONObject?>(null) } // the episode OK was held on
    val main = remember { FocusRequester() } // the ▶ button
    val toEps = remember { FocusRequester() } // Down from the hero: the next episode's card (it went to the round buttons)
    val scope = rememberCoroutineScope()
    LaunchedEffect(page) { page.loadKept() }
    LaunchedEffect(page) { page.load(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.pedrubik2000.kumapie.data.SavedMeta.get(context, title.id) }) }
    LaunchedEffect(page.picking) { page.loadFiles() }
    val meta = page.meta
    val error = page.error
    val picking = page.picking
    val files = page.files
    val t = page.shown
    val eps = page.episodes
    val next = page.next
    fun play(e: JSONObject?) { page.play(e)?.let { context.startActivity(StreamActivity.intent(context, it)) } }
    // The files or an episode's menu on the right; else the episodes as a row of wide cards at the bottom (mockups 2026-11-18).
    val side = error != null || meta == null || picking != null || menu != null
    LaunchedEffect(meta != null, side) { if (meta != null && !side) main.focusSoon() }
    Box(Modifier.fillMaxSize().onBackKey(picking != null || menu != null) { if (picking != null) page.closeFiles() else menu = null }) {
    Backdrop(t.background.ifEmpty { t.poster })
    Row(Modifier.fillMaxSize().padding(start = RAIL, top = 40.dp, end = 32.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(520.dp)) {
            Text(tr("Discover").uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, letterSpacing = 3.sp, style = Arcade.label)
            TitleLogo(t)
            Text(t.line, color = Colors.text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (meta != null) {
                    Button(onClick = { play(next) }, modifier = Modifier.focusRequester(main).focusProperties { if (!side) down = toEps }) {
                        Text(("▶ " + if (next != null) tr("Episode %1\$d", next.optInt("number")) +
                            (if (next.optBoolean("ready") && next.optString("source").isNotEmpty()) " · " + next.optString("source") else "") else tr("Watch")))
                    }
                }
                page.kept?.let { on ->
                    Button(onClick = { scope.launch { page.keep() } },
                        modifier = Modifier.focusProperties { if (!side) down = toEps }) {
                        Text(if (on) "✓ " + tr("On Home") else "+ " + tr("Home"))
                    }
                }
            }
            Text(t.description.replace(Regex("""\s+"""), " "), color = Colors.dim, fontSize = 14.sp, maxLines = if (side) 8 else 3, overflow = TextOverflow.Ellipsis)
        }
        val m = meta
        val pick = picking
        // Left from the list: the ▶ button (the D-pad picked "+ Home", nearer the top of the list).
        if (side) Box(Modifier.weight(1f).focusProperties {
            onExit = { if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Left && meta != null) { cancelFocusChange(); main.requestFocus() } }
        }.focusGroup()) { when {
            error != null -> ErrorBox(error, onRetry = { scope.launch { page.retry() } }, onSettings = null, server = api is Api)
            m == null -> Text(tr("Loading…"), color = Colors.dim)
            pick == null && menu != null -> {
                val e = menu!!
                val watched = e.optBoolean("watched")
                Column {
                    Heading(tr("Episode %1\$d", e.optInt("number")))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(12.dp)) {
                        item { Line(0, tr("Other files"), "", null) { menu = null; page.others(e) } }
                        item { Line(1, if (watched) tr("Mark not watched") else tr("Mark watched"), "", null) { menu = null; scope.launch { page.mark(e) } } }
                    }
                }
            }
            pick != null -> {
                // Files this device can't decode (by their names: HEVC 10 bit, AV1…) go last, saying why. A name never says the level: "may".
                val list = remember(files) {
                    files?.map { f -> page.missing(f).let { no -> if (no.isEmpty()) f else JSONObject(f.toString()).put("label", "✗ " + tr("May not play here: %1\$s", no.joinToString(", ")) + " · " + f.optString("label")) } }
                }
                Column {
                    Heading(pick.second)
                    when {
                        list == null -> Text(if (api is Api) tr("Asking Torrentio…") else tr("Asking your addons…"), color = Colors.dim)
                        list.isEmpty() -> Text(tr("No files found for this"), color = Colors.dim)
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(12.dp)) {
                            itemsIndexed(list) { i, f ->
                                Line(i, f.optString("label"), f.optString("about"), null) {
                                    // An addon's stream (no PC): a link that plays as it is, with its live Japanese lines.
                                    // Its title goes on Home, to come back to (no PC keeps "Continue watching").
                                    scope.launch { context.startActivity(StreamActivity.intent(context, page.pick(pick, f))) }
                                }
                            }
                        }
                    }
                }
            }
            else -> {}
        } }
    }
    meta?.let { m ->
        if (!side) Column(Modifier.align(Alignment.BottomStart).padding(bottom = 16.dp)) {
            Heading(tr("Episodes"), Modifier.padding(start = RAIL))
            // The row starts at the next episode (▶'s), which Down from the hero focuses.
            val at = remember(m) { eps.indexOf(next).coerceAtLeast(0) }
            LazyRow(state = androidx.compose.foundation.lazy.rememberLazyListState(at), horizontalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(start = RAIL, end = 32.dp, top = 8.dp, bottom = 8.dp)) {
                if (eps.isEmpty()) item { EpisodeCard(tr("Watch"), m.optString("year"), null, "", Modifier.focusRequester(toEps)) { play(null) } }
                itemsIndexed(eps, key = { _, e -> e.getString("id") }) { i, e ->
                    val ready = e.optBoolean("ready") // the PC got a file ready for it: no file list, it starts at once
                    val mark = if (e.optBoolean("watched")) "✓" else if (ready) "▶" else ""
                    val source = if (ready) e.optString("source") else ""
                    EpisodeCard(tr("Episode %1\$d", e.optInt("number")), listOf(source, e.optString("title")).filter { it.isNotEmpty() }.joinToString(" · "),
                        e.optString("thumb"), mark, if (i == at) Modifier.focusRequester(toEps) else Modifier, onLongClick = { menu = e }) { play(e) }
                }
            }
        }
    }
    rail()
    }
}

/** An episode as a wide card (Home's Continue watching look): its picture with ✓ / ▶ on it, its number and title under it. */
@Composable
private fun EpisodeCard(name: String, line: String, thumb: String?, mark: String, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.width(200.dp)) {
        Card(onClick = onClick, onLongClick = onLongClick, shape = arcadeCardShape(PosterSlab), border = arcadeCardBorder(PosterSlab),
            colors = arcadeCardColors(), modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .semantics { contentDescription = name }) { // its name is under the card: TalkBack (and the tests) read it here
            Box(Modifier.fillMaxSize().background(Colors.background.copy(alpha = 0.6f))) {
                // A picture that doesn't load (metahub has none for some seasons: 404) leaves the dark card.
                var failed by remember(thumb) { mutableStateOf(false) }
                if (!thumb.isNullOrEmpty() && !failed) AsyncImage(thumb, null, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
                if (mark.isNotEmpty()) Text(mark, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(8.dp)
                    .background(Colors.background.copy(alpha = 0.75f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        Text(name, color = Colors.text, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        Text(line, color = Colors.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One focusable line of the episode / file list ([i] 0 takes the focus first). */
@Composable
private fun Line(i: Int, text: String, line: String, thumb: String?, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val first = remember { FocusRequester() }
    Card(onClick = onClick, onLongClick = onLongClick, shape = arcadeCardShape(RowSlab), border = arcadeCardBorder(RowSlab),
        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // A picture that doesn't load (metahub has none for some seasons: 404) leaves no empty frame.
            var failed by remember(thumb) { mutableStateOf(false) }
            if (!thumb.isNullOrEmpty() && !failed) AsyncImage(thumb, null, contentScale = ContentScale.Crop, onError = { failed = true },
                modifier = Modifier.width(160.dp).aspectRatio(16f / 9f))
            Column {
                Text(text, color = Colors.text, fontSize = 18.sp)
                Text(line, color = Colors.dim, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (i == 0) LaunchedEffect(Unit) { first.focusSoon() }
}
