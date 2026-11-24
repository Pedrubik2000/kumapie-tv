package io.github.pedrubik2000.kumapie.mobile.ui

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import io.github.pedrubik2000.kumapie.player.LiveLines
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.watchReady
import io.github.pedrubik2000.kumapie.data.StreamStart
import io.github.pedrubik2000.kumapie.data.next
import io.github.pedrubik2000.kumapie.data.nextQuery
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
    val (column, grid, rowStates) = remember { Triple(androidx.compose.foundation.lazy.LazyListState(), androidx.compose.foundation.lazy.grid.LazyGridState(), mutableMapOf<Int, androidx.compose.foundation.lazy.LazyListState>()) }
    LaunchedEffect(Unit) {
        if (start == null) {
            if (rows == null) rows = (library.discovery as? io.github.pedrubik2000.kumapie.data.Addons)?.lastBoard() // last time's rows at once (as the TV)
            runCatching { library.discovery.board() }.onSuccess { rows = it }.onFailure { if (rows == null) error = it.message ?: it.toString() }
        }
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
    val wide = wide()
    val search = @Composable { m: Modifier -> DiscoverSearch(query, { query = it }, { searched = query.trim() }, m) }
    val body = @Composable { m: Modifier ->
        Box(m) {
            val list = found
            val board = rows
            val top = if (wide) 96.dp else 12.dp
            when {
                error != null -> Box(Modifier.padding(top = top)) { Message(error!!) }
                searched.isNotEmpty() && list == null || searched.isEmpty() && board == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Colors.accent) }
                searched.isNotEmpty() && list!!.isEmpty() -> Box(Modifier.padding(top = top)) { Message(tr("Nothing found")) }
                searched.isNotEmpty() -> LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), grid, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = top, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list!!.distinctBy { it.id }, key = { it.id }) { f -> PosterCard(f, Modifier.fillMaxWidth()) { open = f } }
                }
                else -> {
                    // The TV's hero: the first row's first title (your newest plans when you have an AniList list).
                    val hero = board!!.firstOrNull()?.second?.firstOrNull()
                    hero?.let { DiscoverBackdrop(library, it) }
                    LazyColumn(Modifier.fillMaxSize(), column, contentPadding = PaddingValues(bottom = 24.dp)) {
                        hero?.let { h -> item(key = "hero") { DiscoverHero(library, h, board[0].first) { open = h } } }
                        // Two addons may name a catalog alike, and two AniList entries may share a kitsu id: keys unique.
                        board.forEachIndexed { index, (name, titles) ->
                            item(key = "row-$index") {
                                Text(tr(name), color = Colors.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 10.dp))
                                LazyRow(state = rowStates.getOrPut(index) { androidx.compose.foundation.lazy.LazyListState() }, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    items(titles.distinctBy { it.id }, key = { it.id }) { f -> PosterCard(f, Modifier.width(if (wide) 140.dp else 112.dp)) { open = f } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (wide) Box(Modifier.fillMaxSize().background(Colors.background)) {
        body(Modifier.fillMaxSize())
        search(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 16.dp, end = 24.dp).width(360.dp))
    } else Scaffold(containerColor = Colors.background, topBar = {
        TopAppBar(title = { Text(tr("Discover")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
            actions = { search(Modifier.width(200.dp).padding(end = 8.dp)) })
    }) { padding -> body(Modifier.fillMaxSize().padding(padding)) }
}

/** A poster with its name and year under it. */
@Composable
private fun PosterCard(f: Found, modifier: Modifier, onClick: () -> Unit) = Column(modifier.clickable(onClick = onClick)) {
    AsyncImage(f.poster, f.name, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Colors.surface))
    Text(f.shownName, color = Colors.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    Text(f.year, color = Colors.dim, fontSize = 12.sp)
}

/**
 * A title, Stremio's way: its backdrop with logo, rating, runtime and genres; ▶ (the episode after the last watched,
 * else the first the PC got ready, else the first one's files) and its text; then the episodes. An episode
 * (or the movie) opens its files; a long press offers other files and watched / not watched (✓).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TitleScreen(library: Library, title: Found, onWatch: (Map<String, String>) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current; val page = remember(title.id) { io.github.pedrubik2000.kumapie.data.TitlePage(library.discovery, title, fresh = io.github.pedrubik2000.kumapie.data.SavedMeta.saver(context, title.id)) }
    var more by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var menu by remember { mutableStateOf<String?>(null) } // the long-pressed episode's id
    LaunchedEffect(page) { page.load() }
    LaunchedEffect(page) { page.loadKept() }
    var media by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(title.id, aniListLogins.intValue) {
        if (library.settings.anilistToken.isNotEmpty() && title.id.startsWith("kitsu:"))
            media = runCatching { io.github.pedrubik2000.kumapie.data.AniList.idOfKitsu(title.id, library.settings.prefs) }.getOrNull()
    }
    fun play(e: JSONObject?) { page.play(e)?.let(onWatch) }
    Box(Modifier.fillMaxSize().background(Colors.background)) {
        val m = page.meta
        val error = page.error
        val t = page.shown
        val eps = page.episodes
        val wide = wide()
        // The logo, line, ▶, the text, then "Episodes". A tablet: left column over the dimmed backdrop.
        val info = @Composable {
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (m != null) {
                    val first = page.next
                    Pill(if (first != null) tr("Episode %1\$d", first.optInt("number")) +
                        (if (first.optBoolean("ready") && first.optString("source").isNotEmpty()) " · " + first.optString("source") else "") else tr("Watch"),
                        { play(first) }, primary = true, icon = Icons.Default.PlayArrow)
                }
                AniListPill(library.settings, media)
            }
            Text(t.description, color = Colors.text, fontSize = 15.sp, maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { more = !more }.padding(horizontal = 16.dp, vertical = 4.dp))
            when {
                error != null -> Message(error)
                m == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Colors.accent) }
            }
        }
        val episode = @Composable { e: JSONObject ->
                val ready = e.optBoolean("ready")
                val watched = e.optBoolean("watched")
                Box {
                    WideTile(e.optString("thumb"), tr("Episode %1\$d", e.optInt("number")), listOf(if (ready) e.optString("source") else "", e.optString("title")).filter { it.isNotEmpty() }.joinToString(" · "),
                        null, if (wide) 296.dp else 240.dp, if (watched) "✓" else if (ready) "▶" else "", onLongClick = { menu = e.getString("id") }) { play(e) }
                DropdownMenu(expanded = menu == e.getString("id"), onDismissRequest = { menu = null }) {
                    DropdownMenuItem(text = { Text(tr("Other files")) }, onClick = { menu = null; page.others(e) })
                    DropdownMenuItem(text = { Text(if (watched) tr("Mark not watched") else tr("Mark watched")) }, onClick = { menu = null; scope.launch { page.mark(e) } })
                }
                }
        }
        // The episodes as the TV's shelf of stills, scrolled to ▶'s episode.
        val at = eps.indexOf(page.next).coerceAtLeast(0)
        val row = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(at) { row.scrollToItem(at) }
        val shelf = @Composable {
            if (eps.isNotEmpty()) Text(tr("Episodes"), color = Colors.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            LazyRow(state = row, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(eps, key = { it.getString("id") }) { episode(it) }
            }
        }
        if (wide) {
            val haze = dev.chrisbanes.haze.rememberHazeState() // the pills blur it
            Box(Modifier.matchParentSize().hazeSource(haze)) {
                Backdrop(t.background.ifEmpty { t.poster })
                Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Colors.background, 0.6f to Colors.background.copy(alpha = 0.55f), 1f to Color.Transparent)))
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalHaze provides haze) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = 24.dp)) {
                    Column(Modifier.weight(1f).fillMaxWidth(0.6f).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 72.dp)) {
                        Box(Modifier.padding(horizontal = 16.dp)) { TitleLogo(t) }
                        Text(t.line, color = Colors.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        info()
                    }
                    shelf()
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
            item(key = "episodes") { shelf() }
        }
        // Over the backdrop, like Stremio's: only the back arrow.
        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp).statusBarsPadding().background(Color(0x59000000), CircleShape).border(1.dp, Glass.line, CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), tint = Color.White)
        }
    }
    LaunchedEffect(page.picking) { page.loadFiles() }
    page.picking?.let { at ->
        ModalBottomSheet(properties = DarkSheet, onDismissRequest = { page.closeFiles() }, containerColor = Colors.surface) {
            Files(library, page) { f ->
                page.closeFiles()
                // An addon's link (no PC): it plays as it is, its Japanese lines live, its words made on the phone.
                scope.launch { onWatch(page.pick(at, f)) }
            }
        }
    }
}

/** Torrentio's files for the picked episode, best first; asking the indexers takes up to a minute. */
@Composable
private fun Files(library: Library, page: io.github.pedrubik2000.kumapie.data.TitlePage, onPick: (JSONObject) -> Unit) {
    val list = page.files
    val error = page.error
    when {
        error != null -> Message(error)
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
    var ctl by remember { mutableStateOf<io.github.pedrubik2000.kumapie.player.SceneController?>(null) }
    val player = remember {
        io.github.pedrubik2000.kumapie.player.videoPlayer(context, query["lang"] ?: "ja").apply {
            // As ScenePlayer's own: the episode's audio language, the file's own subtitle tracks off.
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } } // after ScenePlayer's own dispose (ownsPlayer = false)
    // An addon's link (no PC, kumapie for other people): no polling; Jimaku is asked on the phone with the user's key.
    val alone = remember { StreamStart.of(query, library.settings.prefs) as? StreamStart.Alone }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // No PC: lines, words at once, the late fit, the shift and the place, shared with the TV (AloneStream).
    val s = remember {
        alone?.let { io.github.pedrubik2000.kumapie.lang.AloneStream(library.settings.prefs,
            library.discovery as? io.github.pedrubik2000.kumapie.data.Addons, player, query["id"].orEmpty(), query["type"] ?: "series",
            query["show"].orEmpty(), query["title"].orEmpty(), it.url, file) { e -> error = e } }
    }
    val live = remember { s?.live ?: LiveLines(player) { library.api.discoverSubs(query["type"].orEmpty(), query["id"].orEmpty(), file) } }
    DisposableEffect(Unit) { onDispose { s?.leave() } }
    LaunchedEffect(live) { live.run() }
    LaunchedEffect(live.noLines) { if (live.noLines && error == null) error = tr("No Japanese subtitles: none in this file, none on Jimaku") }
    DisposableEffect(Unit) {
        val ended = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                // No PC: the next episode's file from the addons (core data/Streaming.kt, shared with the TV); with a PC, its next.
                if (alone == null) { nextQuery(query, next)?.let(onNext); return }
                val addons = library.discovery as? io.github.pedrubik2000.kumapie.data.Addons ?: return
                scope.launch {
                    runCatching { addons.next(query) { step = tr("Next: episode %1\$d…", it) } }.onSuccess { it?.let(onNext) }.onFailure { error = it.message ?: it.toString() }
                }
            }

            // Plain playback had no word of it: the video just stood still under "Words coming".
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                if (!io.github.pedrubik2000.kumapie.player.DecodeRetry.recovered(player, e)) error = tr("The video stopped: %1\$s", e.cause?.message ?: e.message ?: e.errorCodeName)
            }
        }
        player.addListener(ended)
        onDispose { player.removeListener(ended) }
    }
    BackHandler(onBack = onBack)
    if (s != null) {
        LaunchedEffect(Unit) {
            // Where it was left on this device (no PC to keep it), as the TV does.
            player.setMediaItem(MediaItem.fromUri(s.video), ((alone?.resumeAt ?: 0.0) * 1000).toLong())
            player.prepare()
            player.play()
            playing = true
            step = tr("Japanese lines from Jimaku…")
            s.keepPlace() // saved as it plays: the word player takes this player over, and releases it
        }
        // Jimaku's lines are all there: the episode is made at once ([AloneStream.build]); the late fit moves its lines.
        LaunchedEffect(live.lines) {
            if (live.lines.isEmpty()) return@LaunchedEffect
            detail = runCatching {
                s.build(context, library.languages, (player.duration.takeIf { it > 0 } ?: 0L) / 1000.0, { player.currentPosition / 1000.0 }) {
                    step = tr("Words: downloading the Japanese dictionary (once)…")
                }
            }.getOrElse { error = it.message; return@LaunchedEffect }
        }
        LaunchedEffect(live.fitted, live.pieces, live.english, live.englishOpen, ctl, ctl?.scenes) { ctl?.let { s.fitLanded(it::shift); s.piecesLanded(it::shift); s.englishLanded(it) } }
    } else LaunchedEffect(Unit) {
        runCatching {
            // The polling is shared with the TV (core data/Streaming.kt).
            val r = library.api.watchReady(query, step = { step = it }, url = { u ->
                if (!playing) {
                    player.setMediaItem(MediaItem.fromUri(u))
                    player.prepare()
                    player.play()
                    playing = true
                }
            }, next = { next = it }, file = { file = it })
            val d = library.episode(r.getString("id"))
            live.stop() // the scenes draw the lines now
            detail = d.copy(video = r.getString("url"), resume = io.github.pedrubik2000.kumapie.data.resumeAt(player.currentPosition / 1000.0, d.resume))
        }.onFailure { error = it.message ?: it.toString() }
    }
    val d = detail
    if (playing) FullScreen( // one call for both: the bars stay hidden from plain to scenes
        if (library.settings.upright) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
    if (d != null) {
        return Box(Modifier.fillMaxSize().background(Color.Black)) {
            // A new [d] (another Jimaku file fitted: AloneStream) is a new ScenePlayer on the same player.
            androidx.compose.runtime.key(d) { ScenePlayer(library, library.settings, library.backend(d.lang), d, startPaused = false, onBack, preloaded = player, ownsPlayer = false, onController = { ctl = it }) }
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
            Text(tr("Jimaku %1\$s s", "%+.1f".format(live.offset)) + (io.github.pedrubik2000.kumapie.lang.AloneStream.fitNote(live)?.let { " · $it" } ?: ""), color = Color.White, fontSize = 13.sp)
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
