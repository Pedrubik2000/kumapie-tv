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
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.tv.material3.Button
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
fun DiscoverScreen(api: io.github.pedrubik2000.kumapie.data.Discovery, start: Found? = null) {
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
        if (start == null) runCatching { api.board() }.onSuccess { rows = it }.onFailure { error = it.message ?: it.toString() }
    }
    LaunchedEffect(searched, attempt) {
        found = null
        if (searched.isEmpty()) return@LaunchedEffect
        error = null
        runCatching { api.discover(searched) }.onSuccess { found = it }.onFailure { error = it.message ?: it.toString() }
    }
    open?.let { title ->
        if (start == null) BackHandler { open = null }
        return TitleScreen(api, title)
    }
    val board = rows
    // onBackKey, not BackHandler: the first Back left Discover (Chromecast, 2026-11-17).
    Box(Modifier.fillMaxSize().onBackKey(searched.isNotEmpty()) { searched = ""; query = "" }) {
        val shown = focused ?: board?.firstOrNull()?.second?.firstOrNull()
        if (searched.isEmpty() && shown != null) Backdrop(shown.background.ifEmpty { shown.poster })
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title(tr("Discover"))
                Spacer(Modifier.width(32.dp))
                TvTextField(query, { query = it }, onDone = { searched = query.trim() }, Modifier.width(420.dp), placeholder = tr("Search"))
            }
            val list = found
            when {
                error != null -> ErrorBox(error!!, onRetry = { attempt++ }, onSettings = null, server = api is Api)
                searched.isNotEmpty() && list == null || searched.isEmpty() && board == null -> Text(tr("Loading…"), color = Colors.dim)
                searched.isNotEmpty() && list!!.isEmpty() -> Text(tr("Nothing found"), color = Colors.dim, fontSize = 20.sp, modifier = Modifier.padding(top = 32.dp, start = 16.dp))
                searched.isNotEmpty() -> {
                    Heading(searched, Modifier.padding(top = 16.dp, start = 16.dp))
                    LazyVerticalGrid(GridCells.Adaptive(130.dp), contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        itemsIndexed(list!!, key = { _, f -> f.id }) { i, f ->
                            Poster(f.poster, f.name, f.year, null, if (i == 0) Modifier.focusRequester(first) else Modifier) { open = f }
                        }
                    }
                }
                else -> {
                    // The focused title, Stremio's way: its logo (else name), rating, genres and the start of its text.
                    Column(Modifier.fillMaxWidth(0.5f).height(160.dp).padding(top = 8.dp), verticalArrangement = Arrangement.Bottom) { // 200 dp cut the row's years off on a 540 dp tall TV
                        shown?.let { f ->
                            TitleLogo(f)
                            Text(f.line, color = Colors.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text(f.description.replace(Regex("""\s+"""), " "), color = Colors.dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // The top edge fades out: a row scrolled up peeked its years out under the text (Chromecast).
                    LazyColumn(Modifier.fillMaxSize().fadeTop(0.06f), contentPadding = PaddingValues(top = 14.dp)) { // the fade ends above the first heading (it hid "ANILIST · PLANNING")
                        board!!.forEachIndexed { r, (name, titles) ->
                            item(key = "row-$name") {
                                Heading(tr(name), Modifier.padding(start = 16.dp, top = 12.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                                    itemsIndexed(titles, key = { _, f -> f.id }) { i, f ->
                                        Poster(f.poster, f.name, f.year, null, (if (r == 0 && i == 0) Modifier.focusRequester(first) else Modifier)
                                            .onFocusChanged { if (it.isFocused) focused = f }) { open = f }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(found, rows) { if (!found.isNullOrEmpty() || !rows.isNullOrEmpty()) first.focusSoon() }
}

/** A backdrop over the whole screen, dark where the text and rows are (left and bottom). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Backdrop(image: String) {
    AsyncImage(image, null, contentScale = ContentScale.Crop, alignment = Alignment.TopEnd, modifier = Modifier.matchParentSize())
    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Colors.background, 0.6f to Colors.background.copy(alpha = 0.55f), 1f to Color.Transparent)))
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to Colors.background)))
}

/** The title's logo (fanart.tv), else its name. */
@Composable
private fun TitleLogo(f: Found) {
    if (f.logo.isNotEmpty()) AsyncImage(f.logo, f.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        modifier = Modifier.height(80.dp).widthIn(max = 360.dp))
    else Text(f.name, color = Colors.text, fontSize = 28.sp, style = Arcade.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/**
 * A title, Stremio's way: its backdrop, logo, rating, runtime and genres, ▶ (the episode after the last watched, else
 * the first the PC got ready, else the first one's files), + HOME and its text on the left; its episodes (or the picked
 * one's files) on the right. Holding OK on an episode offers other files and watched / not watched (✓).
 */
@Composable
private fun TitleScreen(api: io.github.pedrubik2000.kumapie.data.Discovery, title: Found) {
    val context = LocalContext.current
    var meta by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Pair<String, String>?>(null) } // (Stremio id, its name)
    var files by remember { mutableStateOf<List<JSONObject>?>(null) }
    var kept by remember { mutableStateOf<Boolean?>(null) } // on Home (null: not known yet)
    var menu by remember { mutableStateOf<JSONObject?>(null) } // the episode OK was held on
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(title.id) { kept = runCatching { api.saved().any { it.id == title.id } }.getOrNull() }
    LaunchedEffect(title.id, reload) {
        runCatching { api.discoverMeta(title.type, title.id) }.onSuccess { meta = it }.onFailure { error = it.message ?: it.toString() }
    }
    LaunchedEffect(picking) {
        files = null
        val (id, _) = picking ?: return@LaunchedEffect
        runCatching { api.discoverStreams(title.type, id).objects() }.onSuccess { files = it }.onFailure { error = it.message ?: it.toString() }
    }
    if (picking != null) BackHandler { picking = null; error = null }
    if (menu != null && picking == null) BackHandler { menu = null }
    val t = meta?.let { api.found(it) } ?: title
    val eps = meta?.optJSONArray("episodes")?.objects().orEmpty()
    fun play(e: JSONObject) {
        val name = "${title.name} ${e.optInt("number")}"
        // The PC got a file ready for it: no file list, it starts at once.
        if (e.optBoolean("ready")) context.startActivity(Intent(context, StreamActivity::class.java).setData(Uri.parse("kumapie://watch").buildUpon()
            .appendQueryParameter("lang", "ja").appendQueryParameter("type", title.type).appendQueryParameter("id", e.getString("id"))
            .appendQueryParameter("title", name).appendQueryParameter("show", title.name).build()))
        else picking = e.getString("id") to name
    }
    Box(Modifier.fillMaxSize()) {
    Backdrop(t.background.ifEmpty { t.poster })
    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            TitleLogo(t)
            Text(t.line, color = Colors.text, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (meta != null) {
                    val next = io.github.pedrubik2000.kumapie.data.nextEpisode(eps)
                    Button(onClick = { if (next != null) play(next) else picking = title.id to title.name }) {
                        Text(("▶ " + if (next != null) tr("Episode %1\$d", next.optInt("number")) +
                            (if (next.optBoolean("ready") && next.optString("source").isNotEmpty()) " · " + next.optString("source") else "") else tr("Watch")))
                    }
                }
                kept?.let { on ->
                    Button(onClick = { scope.launch { runCatching { api.save(title, !on) }.onSuccess { l -> kept = l.any { it.id == title.id } } } }) {
                        Text(if (on) "✓ " + tr("On Home") else "+ " + tr("Home"))
                    }
                }
            }
            Text(t.description.replace(Regex("""\s+"""), " "), color = Colors.dim, fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        val m = meta
        val pick = picking
        when {
            error != null -> ErrorBox(error!!, onRetry = { error = null; reload++ }, onSettings = null, server = api is Api)
            m == null -> Text(tr("Loading…"), color = Colors.dim)
            pick == null && menu != null -> {
                val e = menu!!
                val watched = e.optBoolean("watched")
                Column {
                    Heading(tr("Episode %1\$d", e.optInt("number")))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(12.dp)) {
                        item { Line(0, tr("Other files"), "", null) { menu = null; picking = e.getString("id") to "${title.name} ${e.optInt("number")}" } }
                        item {
                            Line(1, if (watched) tr("Mark not watched") else tr("Mark watched"), "", null) {
                                menu = null; scope.launch { runCatching { api.seen(e.getString("id"), !watched) }; reload++ }
                            }
                        }
                    }
                }
            }
            pick != null -> {
                // Files this device can't decode (by their names: HEVC 10 bit, AV1…) go last, saying why. A name never says the level: "may".
                val list = remember(files) {
                    files?.map { f -> f to io.github.pedrubik2000.kumapie.data.Playable.missing(f.optString("label") + " " + f.optString("about") + " " + f.optString("file")) }
                        ?.sortedBy { it.second.isNotEmpty() }
                        ?.map { (f, no) -> if (no.isEmpty()) f else JSONObject(f.toString()).put("label", "✗ " + tr("May not play here: %1\$s", no.joinToString(", ")) + " · " + f.optString("label")) }
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
                                    if (f.has("url") && kept != true) scope.launch { runCatching { api.save(title, true) }.onSuccess { l -> kept = l.any { it.id == title.id } } }
                                    if (f.has("url")) return@Line context.startActivity(Intent(context, StreamActivity::class.java).setData(
                                        Uri.parse("kumapie://play").buildUpon().apply {
                                            listOf("url" to f.getString("url"), "type" to title.type, "id" to pick.first, "title" to pick.second,
                                                "show" to title.name, "file" to f.optString("file")).forEach { (k, v) -> appendQueryParameter(k, v) }
                                        }.build()))
                                    val link = Uri.parse("kumapie://watch").buildUpon().apply {
                                        listOf("lang" to "ja", "type" to title.type, "id" to pick.first, "hash" to f.getString("hash"),
                                            "file" to f.getString("file"), "idx" to f.optInt("idx", -1).toString(), "title" to pick.second, "show" to title.name,
                                            "label" to f.optString("short"))
                                            .forEach { (k, v) -> appendQueryParameter(k, v) }
                                    }.build()
                                    context.startActivity(Intent(context, StreamActivity::class.java).setData(link))
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(12.dp)) {
                    if (eps.isEmpty()) item { Line(0, tr("Watch"), m.optString("year"), null) { picking = title.id to title.name } }
                    itemsIndexed(eps, key = { _, e -> e.getString("id") }) { i, e ->
                        val ready = e.optBoolean("ready") // the PC got a file ready for it: no file list, it starts at once
                        val mark = if (e.optBoolean("watched")) "✓ " else if (ready) "▶ " else ""
                        // The ▶ file's quality goes under the title: on its line it wrapped ("Episode 1 · 1080p ·" / "15.7 Mbps").
                        val source = if (ready) e.optString("source") else ""
                        Line(i, mark + tr("Episode %1\$d", e.optInt("number")), listOf(source, e.optString("title")).filter { it.isNotEmpty() }.joinToString(" · "), e.optString("thumb"),
                            onLongClick = { menu = e }) { play(e) }
                    }
                }
            }
        }
    }
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
