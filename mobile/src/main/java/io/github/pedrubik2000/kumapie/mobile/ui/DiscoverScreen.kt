package io.github.pedrubik2000.kumapie.mobile.ui

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import io.github.pedrubik2000.kumapie.player.LiveLines
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.DisposableEffect
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.objects
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Discover (kumapie-server discover.py), laid out like Stremio's board and Nuvio's home: a hero banner that turns every
 * 8 s, then rows of posters (the person's AniList Planning list first, then Kitsu's catalogs); a search shows a grid.
 * A title opens its page; [onWatch] with a picked file: [WatchScreen] streams it from Real-Debrid, the PC makes only its
 * words. [start]: a title kept on Home, opened straight away (Back goes back to Home).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(library: Library, onWatch: (Map<String, String>) -> Unit, onBack: () -> Unit, start: Found? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Found>?>(null) }
    var rows by remember { mutableStateOf<List<Pair<String, List<Found>>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(start) }
    LaunchedEffect(Unit) {
        if (start == null) runCatching { library.discovery.board() }.onSuccess { rows = it }.onFailure { error = it.message ?: it.toString() }
    }
    LaunchedEffect(searched) {
        found = null
        if (searched.isEmpty()) return@LaunchedEffect
        error = null
        runCatching { library.discovery.discover(searched) }.onSuccess { found = it }.onFailure { error = it.message ?: it.toString() }
    }
    open?.let { title ->
        val back = { if (start != null) onBack() else open = null }
        BackHandler(onBack = back)
        return TitleScreen(library, title, onWatch, onBack = back)
    }
    BackHandler { if (searched.isNotEmpty()) { searched = ""; query = "" } else onBack() }
    Scaffold(containerColor = Colors.background, topBar = {
        TopAppBar(title = { Text(tr("Discover")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Glass: a pill with a search icon.
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(tr("Search anime")) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { searched = query.trim() }),
                shape = RoundedCornerShape(28.dp), leadingIcon = { Icon(Icons.Default.Search, null) },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Glass.fill, focusedContainerColor = Glass.fill,
                    unfocusedBorderColor = Glass.line, focusedBorderColor = Color.White.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            val list = found
            val board = rows
            when {
                error != null -> Message(error!!)
                searched.isNotEmpty() && list == null || searched.isEmpty() && board == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Colors.accent) }
                searched.isNotEmpty() && list!!.isEmpty() -> Message(tr("Nothing found"))
                searched.isNotEmpty() -> LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list!!.distinctBy { it.id }, key = { it.id }) { f -> PosterCard(f, Modifier.fillMaxWidth()) { open = f } }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    // The hero: the first row's first few (your newest plans when you have an AniList list).
                    val featured = board!!.firstOrNull()?.second.orEmpty().filter { it.background.isNotEmpty() }.take(6)
                    if (featured.isNotEmpty()) item(key = "hero") { Hero(featured) { open = it } }
                    // Two addons may name a catalog alike, and two AniList entries may share a kitsu id: keys unique.
                    board.forEachIndexed { index, (name, titles) ->
                        item(key = "row-$index") {
                            Text(tr(name), color = Colors.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 10.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(titles.distinctBy { it.id }, key = { it.id }) { f -> PosterCard(f, Modifier.width(if (wide()) 140.dp else 112.dp)) { open = f } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A poster with its name and year under it. */
@Composable
private fun PosterCard(f: Found, modifier: Modifier, onClick: () -> Unit) = Column(modifier.clickable(onClick = onClick)) {
    AsyncImage(f.poster, f.name, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Colors.surface))
    Text(f.name, color = Colors.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    Text(f.year, color = Colors.dim, fontSize = 12.sp)
}

/** Nuvio's hero: a title's backdrop with its logo, rating and genres and a line of its text; the next one every 8 s. */
@Composable
private fun Hero(titles: List<Found>, onOpen: (Found) -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(titles) { while (titles.size > 1) { delay(8_000); i = (i + 1) % titles.size } }
    androidx.compose.animation.Crossfade(titles[i % titles.size], label = "hero") { f ->
        // Glass: inset with round corners and the thin border.
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(if (wide()) 280.dp else 240.dp)
            .clip(RoundedCornerShape(22.dp)).border(1.dp, Glass.line, RoundedCornerShape(22.dp)).clickable { onOpen(f) }) {
            Backdrop(f.background.ifEmpty { f.poster })
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp).fillMaxWidth(0.85f)) {
                TitleLogo(f)
                Text(f.line, color = Colors.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(f.description, color = Colors.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                titles.indices.forEach { n -> Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (n == i % titles.size) Color.White else Color.White.copy(alpha = 0.35f))) }
            }
        }
    }
}

/** A backdrop fading into the page at the bottom. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Backdrop(image: String) {
    AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().background(Colors.surface))
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Colors.background)))
}

/** The title's logo (fanart.tv), else its name. */
@Composable
private fun TitleLogo(f: Found) {
    if (f.logo.isNotEmpty()) AsyncImage(f.logo, f.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        modifier = Modifier.height(64.dp).widthIn(max = 260.dp))
    else Text(f.name, style = MaterialTheme.typography.headlineSmall, color = Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/**
 * A title, Stremio's way: its backdrop with logo, rating, runtime and genres; ▶ (the episode after the last watched,
 * else the first the PC got ready, else the first one's files), + Home and its text; then the episodes. An episode
 * (or the movie) opens its files; a long press offers other files and watched / not watched (✓).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TitleScreen(library: Library, title: Found, onWatch: (Map<String, String>) -> Unit, onBack: () -> Unit) {
    var meta by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Pair<String, String>?>(null) } // (Stremio id, its name)
    var kept by remember { mutableStateOf<Boolean?>(null) } // on Home (null: not known yet)
    var more by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf<String?>(null) } // the long-pressed episode's id
    LaunchedEffect(title.id, reload) {
        runCatching { library.discovery.discoverMeta(title.type, title.id) }.onSuccess { meta = it }.onFailure { error = it.message ?: it.toString() }
    }
    LaunchedEffect(title.id) { kept = runCatching { library.discovery.saved().any { it.id == title.id } }.getOrNull() }
    fun play(e: JSONObject) {
        val name = "${title.name} ${e.optInt("number")}"
        // The PC got a file ready for it: no file list, it starts at once.
        if (e.optBoolean("ready")) onWatch(mapOf("lang" to "ja", "type" to title.type, "id" to e.getString("id"), "title" to name, "show" to title.name))
        else picking = e.getString("id") to name
    }
    Box(Modifier.fillMaxSize().background(Colors.background)) {
        val m = meta
        val t = m?.let { library.discovery.found(it) } ?: title
        val eps = m?.optJSONArray("episodes")?.objects().orEmpty()
        val wide = wide()
        // The logo, line, ▶ and + Home, the text, then "Episodes". A tablet: left column over the dimmed backdrop.
        val info = @Composable {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (m != null) {
                    val first = io.github.pedrubik2000.kumapie.data.nextEpisode(eps)
                    Pill(if (first != null) tr("Episode %1\$d", first.optInt("number")) +
                        (if (first.optBoolean("ready") && first.optString("source").isNotEmpty()) " · " + first.optString("source") else "") else tr("Watch"),
                        { if (first != null) play(first) else picking = title.id to title.name }, primary = true, icon = Icons.Default.PlayArrow)
                }
                kept?.let { on ->
                    Pill(if (on) "✓ " + tr("On Home") else "+ " + tr("Home"), {
                        scope.launch { runCatching { library.discovery.save(title, !on) }.onSuccess { l -> kept = l.any { it.id == title.id } } }
                    })
                }
            }
            Text(t.description, color = Colors.text, fontSize = 15.sp, maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { more = !more }.padding(horizontal = 16.dp, vertical = 4.dp))
            when {
                error != null -> Message(error!!)
                m == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Colors.accent) }
                eps.isNotEmpty() && !wide -> Text(tr("Episodes"), color = Colors.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            }
        }
        val episode = @Composable { e: JSONObject ->
                val ready = e.optBoolean("ready")
                val watched = e.optBoolean("watched")
                Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).glass(RoundedCornerShape(16.dp))) {
                Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = e.getString("id") }) { play(e) }.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(e.optString("thumb"), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(if (wide) 150.dp else 120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Colors.surface))
                    Column(Modifier.weight(1f)) {
                        Text((if (watched) "✓ " else if (ready) "▶ " else "") + tr("Episode %1\$d", e.optInt("number")) +
                            (if (ready && e.optString("source").isNotEmpty()) " · " + e.optString("source") else ""), // the ▶ file's quality, Mbps
                            color = if (watched) Colors.dim else if (ready) Colors.accent else Colors.text)
                        Text(e.optString("title"), color = Colors.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                DropdownMenu(expanded = menu == e.getString("id"), onDismissRequest = { menu = null }) {
                    DropdownMenuItem(text = { Text(tr("Other files")) }, onClick = {
                        menu = null; picking = e.getString("id") to "${title.name} ${e.optInt("number")}"
                    })
                    DropdownMenuItem(text = { Text(if (watched) tr("Mark not watched") else tr("Mark watched")) }, onClick = {
                        menu = null; scope.launch { runCatching { library.discovery.seen(e.getString("id"), !watched) }; reload++ }
                    })
                }
                }
        }
        if (wide) {
            // The backdrop behind everything, dimmed; info left, the episodes right (the mockup's title page).
            val haze = dev.chrisbanes.haze.rememberHazeState() // the episode cards and pills blur it
            Box(Modifier.matchParentSize().hazeSource(haze)) {
                Backdrop(t.background.ifEmpty { t.poster })
                Box(Modifier.matchParentSize().background(Colors.background.copy(alpha = 0.72f)))
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalHaze provides haze) {
                Row(Modifier.fillMaxSize().statusBarsPadding()) {
                    Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 72.dp)) {
                        Box(Modifier.padding(horizontal = 16.dp)) { TitleLogo(t) }
                        Text(t.line, color = Colors.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        info()
                    }
                    LazyColumn(Modifier.weight(0.58f).fillMaxHeight(), contentPadding = PaddingValues(top = 16.dp, bottom = 72.dp)) {
                        if (eps.isNotEmpty()) item(key = "title") {
                            Text(tr("Episodes"), color = Colors.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
                        }
                        items(eps, key = { it.getString("id") }) { episode(it) }
                    }
                }
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            item(key = "head") {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    Backdrop(t.background.ifEmpty { t.poster })
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        TitleLogo(t)
                        Text(t.line, color = Colors.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                info()
            }
            items(eps, key = { it.getString("id") }) { episode(it) }
        }
        // Over the backdrop, like Stremio's: only the back arrow.
        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp).statusBarsPadding().background(Color(0x59000000), CircleShape).border(1.dp, Glass.line, CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), tint = Color.White)
        }
    }
    picking?.let { (id, name) ->
        ModalBottomSheet(properties = DarkSheet, onDismissRequest = { picking = null }, containerColor = Colors.surface) {
            Files(library, title.type, id) { f ->
                picking = null
                // An addon's link (no PC): it plays as it is, its Japanese lines live, its words made on the phone.
                // Its title goes on Home, to come back to (as the TV).
                if (f.has("url") && kept != true) scope.launch { runCatching { library.discovery.save(title, true) } }
                if (f.has("url")) return@Files onWatch(mapOf("lang" to "ja", "type" to title.type, "id" to id, "url" to f.getString("url"),
                    "file" to f.optString("file"), "title" to name, "show" to title.name))
                onWatch(mapOf("lang" to "ja", "type" to title.type, "id" to id, "hash" to f.getString("hash"),
                    "file" to f.getString("file"), "idx" to f.optInt("idx", -1).toString(), "title" to name, "show" to title.name,
                    "label" to f.optString("short")))
            }
        }
    }
}

/** Torrentio's files for [id], best first; asking the indexers takes up to a minute. */
@Composable
private fun Files(library: Library, type: String, id: String, onPick: (JSONObject) -> Unit) {
    var files by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        runCatching { library.discovery.discoverStreams(type, id).objects() }.onSuccess { l ->
            files = l.sortedBy { io.github.pedrubik2000.kumapie.data.Playable.missing(it.optString("label") + " " + it.optString("about") + " " + it.optString("file")).isNotEmpty() }
        }.onFailure { error = it.message ?: it.toString() }
    }
    val list = files
    when {
        error != null -> Message(error!!)
        list == null -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Colors.accent)
            Text(if (library.settings.server.isEmpty()) tr("Asking your addons…") else tr("Asking Torrentio…"), color = Colors.dim, modifier = Modifier.padding(top = 12.dp))
        }
        list.isEmpty() -> Message(tr("No files found for this"))
        else -> LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
            items(list) { f ->
                Column(Modifier.fillMaxWidth().clickable { onPick(f) }.padding(16.dp, 10.dp)) {
                    Text(f.optString("label"), color = Colors.text)
                    Text(f.optString("about"), color = Colors.dim, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * A picked Discover file (or, without hash/file/idx, the one the PC got ready for that episode): the PC gets
 * Real-Debrid's link and makes the episode's words (asked every 3 s, its step shown). The video plays as soon as the
 * link exists, plain; when the words are ready the same player goes on in [ScenePlayer] (no new start: from the scene
 * it is in). Until then, SubMiner's way: the Japanese lines live as it plays ([LiveLines]: the file's own track, else
 * Jimaku's file with a ±0.5 s nudge), each one coloured on the phone. At the video's end, [onNext] with the next
 * episode (the PC gets it ready while this one plays).
 */
@Composable
fun WatchScreen(library: Library, query: Map<String, String>, onNext: (Map<String, String>) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(tr("Asking the PC…")) }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<EpisodeDetail?>(null) }
    var next by remember { mutableStateOf("") }
    var file by remember { mutableStateOf(query["file"].orEmpty()) }
    val player = remember {
        io.github.pedrubik2000.kumapie.player.videoPlayer(context, query["lang"] ?: "ja").apply {
            // As ScenePlayer's own: the episode's audio language, the file's own subtitle tracks off.
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
        }
    }
    DisposableEffect(Unit) { onDispose { if (detail == null) player.release() } } // else ScenePlayer releases it
    // An addon's link (no PC, kumapie for other people): no polling; Jimaku is asked on the phone with the user's key.
    val alone = query["url"].orEmpty()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val live = remember {
        LiveLines(player) {
            if (alone.isEmpty()) library.api.discoverSubs(query["type"].orEmpty(), query["id"].orEmpty(), file)
            else io.github.pedrubik2000.kumapie.data.Jimaku.lines(library.settings.prefs.getString("jimaku_key", "").orEmpty(), query["show"].orEmpty(),
                null, query["id"].orEmpty().substringAfterLast(':').toIntOrNull(), file).map { Triple(it.start, it.end, it.text) }
                // (empty: [LiveLines.noLines] says so once the file turned out to have no Japanese track either)
        }
    }
    LaunchedEffect(live) { live.run() }
    LaunchedEffect(live.noLines) { if (live.noLines) error = tr("No Japanese subtitles: none in this file, none on Jimaku") }
    DisposableEffect(Unit) {
        val ended = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // No PC: the next episode's file from the addons (the same release group if there is one).
                if (state == Player.STATE_ENDED && alone.isNotEmpty()) scope.launch {
                    step = tr("Next: episode %1\$d…", (query["id"].orEmpty().substringAfterLast(':').toIntOrNull() ?: 0) + 1)
                    val addons = library.discovery as? io.github.pedrubik2000.kumapie.data.Addons ?: return@launch
                    runCatching { addons.seen(query["id"].orEmpty(), true) }
                    runCatching { addons.next(query["type"].orEmpty(), query["id"].orEmpty(), file) }.onSuccess { n ->
                        if (n == null) error = tr("No files found for this") else onNext(mapOf("lang" to (query["lang"] ?: "ja"), "type" to query["type"].orEmpty(),
                            "id" to n.first, "url" to n.second.getString("url"), "file" to n.second.optString("file"), "show" to query["show"].orEmpty(),
                            "title" to "${query["show"].orEmpty()} ${n.first.substringAfterLast(':')}"))
                    }.onFailure { error = it.message ?: it.toString() }
                }
                if (state == Player.STATE_ENDED && next.isNotEmpty()) onNext(mapOf("lang" to (query["lang"] ?: "ja"),
                    "type" to query["type"].orEmpty(), "id" to next, "show" to query["show"].orEmpty(),
                    "title" to "${query["show"].orEmpty()} ${next.substringAfterLast(':')}"))
            }

            // Plain playback had no word of it: the video just stood still under "Words coming".
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                error = tr("The video stopped: %1\$s", e.cause?.message ?: e.message ?: e.errorCodeName)
            }
        }
        player.addListener(ended)
        onDispose { player.removeListener(ended) }
    }
    BackHandler(onBack = onBack)
    if (alone.isNotEmpty()) {
        val posKey = "alone_pos_" + query["id"].orEmpty()
        LaunchedEffect(Unit) {
            // Where it was left on this device (no PC to keep it), as the TV does.
            val at = library.settings.prefs.getFloat(posKey, 0f)
            player.setMediaItem(MediaItem.fromUri(alone), if (at > 5) (at * 1000).toLong() else 0L)
            player.prepare()
            player.play()
            playing = true
            step = tr("Japanese lines from Jimaku…")
            while (true) { // saved as it plays: the word player takes this player over, and releases it
                delay(5_000)
                val d = player.duration
                if (d > 0) library.settings.prefs.edit().putFloat(posKey,
                    if (player.currentPosition > d - 120_000) 0f else player.currentPosition / 1000f).apply() // near the end: from the start next time
            }
        }
        // Jimaku's lines are all there: the episode is made on the phone, after the fit had its chance (as on the TV).
        LaunchedEffect(live.lines) {
            if (live.lines.isEmpty()) return@LaunchedEffect
            kotlinx.coroutines.withTimeoutOrNull(30_000) { while (live.fitted == null) delay(500) }
            // English under the lines, from the user's subtitle addon if they added one (OpenSubtitles v3).
            val english = runCatching {
                (library.discovery as? io.github.pedrubik2000.kumapie.data.Addons)
                    ?.english(query["type"] ?: "series", query["id"].orEmpty(), live.lines.map { it.first + live.offset })
            }.getOrNull().orEmpty()
            var d: EpisodeDetail? = null
            while (d == null) {
                d = runCatching {
                    io.github.pedrubik2000.kumapie.lang.EpisodeBuilder.fromLines(context, "alone:" + query["id"].orEmpty(), query["show"].orEmpty(),
                        query["title"].orEmpty(), alone, (player.duration.takeIf { it > 0 } ?: 0L) / 1000.0, live.lines, live.offset, player.currentPosition / 1000.0, english)
                }.getOrElse { error = it.message; return@LaunchedEffect }
                if (d == null) { step = tr("Words: downloading the Japanese dictionary (once)…"); delay(5_000) }
            }
            live.stop()
            detail = d.copy(video = alone, resume = player.currentPosition / 1000.0)
        }
    } else LaunchedEffect(Unit) {
        runCatching {
            while (true) {
                val r = library.api.watch(query)
                next = r.optString("next")
                r.optString("file").takeIf { it.isNotEmpty() }?.let { file = it }
                val state = r.optString("state")
                when (state) {
                    "none" -> error(tr("No file is ready for this episode yet: pick one in its files"))
                    "failed" -> error(r.optString("error"))
                }
                if (!playing && r.has("url")) {
                    player.setMediaItem(MediaItem.fromUri(r.getString("url")))
                    player.prepare()
                    player.play()
                    playing = true
                }
                if (state == "ready") {
                    val d = library.episode(r.getString("id"))
                    live.stop() // the scenes draw the lines now
                    val at = player.currentPosition / 1000.0
                    // Where it was left when that is further in than the plain video got while its words loaded (it starts at 0:00).
                    detail = d.copy(video = r.getString("url"), resume = if (at > 1 && at >= (d.resume ?: 0.0)) at else d.resume)
                    return@runCatching
                }
                step = r.optString("step")
                delay(3_000)
            }
        }.onFailure { error = it.message ?: it.toString() }
    }
    val d = detail
    if (playing) FullScreen( // one call for both: the bars stay hidden from plain to scenes
        if (library.settings.upright) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
    if (d != null) {
        return Box(Modifier.fillMaxSize().background(Color.Black)) {
            ScenePlayer(library, library.settings, library.backend(d.lang), d, startPaused = false, onBack, preloaded = player)
            // The end of the episode still looks for the next one here (the listener above): its failure shown too.
            error?.let {
                Text(it, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).background(Color(0x99000000), RoundedCornerShape(18.dp)).border(1.dp, Glass.line, RoundedCornerShape(18.dp)).padding(14.dp, 6.dp))
            }
        }
    }
    if (playing) { // words failed: the video goes on plain, the reason on top
        return Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Glass, as the scene player: no stock controls; a tap plays / pauses, paused shows ▶ and the timeline.
            AndroidView({ PlayerView(it).apply { this.player = player; useController = false; subtitleView?.visibility = android.view.View.GONE } },
                Modifier.fillMaxSize(), onRelease = { it.player = null })
            var position by remember { androidx.compose.runtime.mutableDoubleStateOf(0.0) }
            var paused by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { while (true) { position = player.currentPosition / 1000.0; paused = !player.isPlaying; delay(250) } }
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { if (player.isPlaying) player.pause() else player.play() })
            if (paused) {
                Box(Modifier.align(Alignment.Center).size(80.dp).background(Color(0x59000000), CircleShape).border(1.dp, Glass.line, CircleShape),
                    contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, tr("Play"), tint = Color.White, modifier = Modifier.size(48.dp)) }
                player.duration.takeIf { it > 0 }?.let { Timeline(position, it / 1000.0, { s -> player.seekTo((s * 1000).toLong()) }, Modifier.align(Alignment.BottomCenter)) }
            }
            LiveLine(library, live, Modifier.align(Alignment.BottomCenter))
            Text(error ?: tr("Words coming: %1\$s", step), color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).background(Color(0x99000000), RoundedCornerShape(18.dp)).border(1.dp, Glass.line, RoundedCornerShape(18.dp)).padding(14.dp, 6.dp))
        }
    }
    Column(Modifier.fillMaxSize().background(Colors.background).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
        Text(query["title"].orEmpty(), color = Colors.text, fontSize = 18.sp, textAlign = TextAlign.Center)
        if (error == null) CircularProgressIndicator(color = Colors.accent)
        Text(error ?: step, color = if (error == null) Colors.dim else Colors.unknown, fontSize = 16.sp, textAlign = TextAlign.Center)
        Button(onClick = onBack) { Text(if (error == null) tr("Not now") else tr("Back")) }
    }
}

/** The live line, its words coloured as the phone knows them (once the splitter is loaded: plain until then). */
@Composable
private fun LiveLine(library: Library, live: LiveLines, modifier: Modifier) {
    val line = live.line
    val styled by androidx.compose.runtime.produceState<AnnotatedString?>(null, line) {
        value = line?.let { AnnotatedString(it) }
        if (line != null) value = withContext(Dispatchers.Default) { runCatching { coloured(library, line) }.getOrNull() } ?: value
    }
    Column(modifier.padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (live.source == "jimaku") Row(verticalAlignment = Alignment.CenterVertically) {
            // Jimaku's file may be timed for another release: nudged by hand, as SubMiner's subtitle delay.
            androidx.compose.material3.TextButton(onClick = { live.offset -= 0.5 }) { Text("−0.5 s", color = Color.White) }
            Text(tr("Jimaku %1\$s s", "%+.1f".format(live.offset)), color = Color.White, fontSize = 13.sp)
            androidx.compose.material3.TextButton(onClick = { live.offset += 0.5 }) { Text("+0.5 s", color = Color.White) }
        }
        styled?.let {
            Text(it, color = Color.White, fontSize = 24.sp, textAlign = TextAlign.Center, lineHeight = 32.sp,
                modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(12.dp, 6.dp))
        }
    }
}

/** [line] split as kumapie splits Japanese, its words not known yet in the unknown colour. */
private fun coloured(library: Library, line: String): AnnotatedString {
    val ja = library.languages.japanese
    val tokens = ja.model.words(listOf(line))[0]
    return buildAnnotatedString {
        var at = 0
        tokens.forEach { t ->
            if (t.begin > at) append(line.substring(at, minOf(t.begin, line.length)))
            withStyle(SpanStyle(color = if (!t.isWord || ja.known.status(t.base) == "k") Color.White else Colors.unknown)) { append(t.surface) }
            at = maxOf(at, t.begin + t.surface.length)
        }
        if (at < line.length) append(line.substring(at))
    }
}

@Composable
private fun Message(text: String) = Text(text, color = Colors.dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
