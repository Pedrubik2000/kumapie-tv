package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.mobile.offline.DownloadState
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.ui.NeutralPoster
import io.github.pedrubik2000.kumapie.i18n.tr

/**
 * A show: a picture header (title, info, Play / Continue) and its episodes with progress, easy scenes and a download
 * button each. Phone: the header above the list; tablet: the header on the left, the episodes on the right.
 */
@Composable
fun ShowScreen(library: Library, initial: Show, onPlay: (Episode) -> Unit, onBack: () -> Unit) {
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    var show by remember { mutableStateOf(initial) }
    var offline by remember { mutableStateOf(false) }
    val states by library.downloads.states().collectAsState(initial = emptyMap())
    var deleting by remember { mutableStateOf<Episode?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var reload by remember { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var fixing by remember { mutableStateOf(false) }
    val meta = remember(show.id, reload) { library.showMeta.of(show.id) }

    LaunchedEffect(reload) { // fresh progress after watching
        runCatching { library.shows() }.onSuccess { (shows, off) ->
            offline = off
            shows.firstOrNull { it.id == initial.id }?.let { show = it }
        }
    }

    val sync = remember { io.github.pedrubik2000.kumapie.mobile.local.Sync(context) }
    // A device episode streaming from the PC: its "Download" (episode id -> how far, 0..1).
    // ponytail: tied to this screen (leaving it stops the download; the .part resumes next tap); a worker if that bites.
    var fetching by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    // A device episode whose copy was deleted here streams from the PC, so it needs the connection.
    fun hasCopy(ep: Episode) = library.local.video(ep.id).exists()
    fun playable(ep: Episode) = !offline || states[ep.id] == DownloadState.Done ||
        (io.github.pedrubik2000.kumapie.mobile.local.LocalEpisodes.isLocal(ep.id) && hasCopy(ep))
    // Play: the episode left partway most recently, else the first not watched to the end, else the first.
    val next = show.episodes.filter(::playable).let { eps ->
        eps.filter { e -> !e.watched && (e.resume ?: 0.0).let { it > 20 && it < e.duration * 0.95 } }.maxByOrNull { it.resumedAt }
            ?: eps.firstOrNull { it.seen < it.scenes } ?: eps.firstOrNull()
    }
    val header = @Composable { modifier: Modifier ->
        ShowHeader(library, show, meta, next, wide, onBack, onPlay, modifier,
            onFix = { fixing = true }.takeIf { !offline }) // device shows: kept here; PC shows: on the PC
    }
    val row = @Composable { ep: Episode ->
        EpisodeRow(library, ep, (io.github.pedrubik2000.kumapie.mobile.local.ShowMeta.episodeTitle(meta, ep.season, ep.number)
            ?: ep.episodeTitle.ifBlank { null }).takeIf { ep.watched }, states[ep.id] ?: DownloadState.None, playable(ep), hasCopy(ep), offline, wide,
            onPlay = { onPlay(ep) }, onDownload = { library.downloads.start(show, ep) },
            onCancel = { library.downloads.cancel(ep.id) }, onDelete = { deleting = ep },
            fetched = fetching[ep.id],
            onFetch = fun() {
                fetching = fetching + (ep.id to 0f)
                scope.launch {
                    runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        sync.downloadVideo(library.settings.server, library.settings.owner, ep.id) { f -> fetching = fetching + (ep.id to f) } } }
                        .onFailure { android.widget.Toast.makeText(context, tr("Couldn't reach the PC: %s", it.message ?: it.toString()), android.widget.Toast.LENGTH_LONG).show() }
                    fetching = fetching - ep.id
                    reload++
                }
            }.takeIf { !hasCopy(ep) && sync.onPc(ep.id) && !offline },
            onMarkWatched = { watched ->
                scope.launch {
                    runCatching { if (watched) library.markWatched(ep.id) else library.markUnwatched(ep.id) }
                        .onSuccess { reload++ }
                        .onFailure { android.widget.Toast.makeText(context, it.message ?: it.toString(), android.widget.Toast.LENGTH_LONG).show() }
                }
            },
            onListen = {
                val art = library.thumb(ep.id, ep.thumb).let { if (it is java.io.File) android.net.Uri.fromFile(it).toString() else it.toString() }
                io.github.pedrubik2000.kumapie.mobile.listen.CondensedService.play(context, ep.id, art)
            })
    }
    // Season chips when the show has more than one; starts on the season of the episode Play would open.
    val seasons = show.episodes.mapNotNull { it.season }.distinct().sorted()
    var season by remember(show.id) { mutableStateOf(next?.season ?: seasons.firstOrNull()) }
    val episodes = if (seasons.size > 1) show.episodes.filter { it.season == season } else show.episodes
    val chips = @Composable {
        if (seasons.size > 1) Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            seasons.forEach { s ->
                androidx.compose.material3.FilterChip(selected = s == season, onClick = { season = s },
                    label = { Text(tr("Season %d", s)) })
            }
        }
    }
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Colors.background, contentColor = Colors.text) {
        if (wide) Row(Modifier.fillMaxSize()) {
            header(Modifier.weight(0.42f).fillMaxHeight())
            LazyColumn(Modifier.weight(0.58f).fillMaxHeight(), contentPadding = PaddingValues(vertical = 24.dp)) {
                item(key = "seasons") { chips() }
                items(episodes, key = { it.id }) { row(it) }
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") { header(Modifier.fillMaxWidth()) }
            item(key = "seasons") { chips() }
            items(episodes, key = { it.id }) { row(it) }
        }
    }

    if (fixing) MatchDialog(library.showMeta, show.kind, show.title, tr("Fix match"),
        onPick = { kind, c ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (show.id.startsWith("local-show-")) library.showMeta.apply(show.id, kind, c.getString("source"), c.getString("id"),
                    listOf(io.github.pedrubik2000.kumapie.data.Lang.speaker, show.lang, "en"))
                else library.api.setMeta(show.id, kind, c.getString("source"), c.getString("id")) // a PC show
            }
        },
        onDismiss = { fixing = false; reload++ })

    deleting?.takeIf { io.github.pedrubik2000.kumapie.mobile.local.LocalEpisodes.isLocal(it.id) }?.let { ep ->
        val copy = hasCopy(ep) && sync.onPc(ep.id) // else "Delete here" would remove the episode itself
        fun gone() {
            show = show.copy(episodes = show.episodes.filter { it.id != ep.id })
            deleting = null
            if (show.episodes.isEmpty()) onBack() // nothing left to show
        }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(ep.title) },
            text = { Text((if (copy) tr("Delete here: only this device's copy goes; the episode stays and plays from the PC.") + "\n\n" else "") +
                tr("Delete everywhere: off the PC and all your devices.")) },
            confirmButton = {
                Row {
                    if (copy) TextButton(onClick = { sync.removeCopy(ep.id); deleting = null; reload++ }) { Text(tr("Delete here")) }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                sync.deleteEverywhere(library.settings.server, library.settings.owner, ep.id) } }
                                .onSuccess { gone() }
                                .onFailure { android.widget.Toast.makeText(context, tr("Couldn't reach the PC: %s", it.message ?: it.toString()), android.widget.Toast.LENGTH_LONG).show() }
                        }
                    }) { Text(tr("Delete everywhere")) }
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Keep")) } },
        )
        return
    }
    deleting?.let { ep ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Remove the download?")) },
            text = { Text(tr("%s will need the PC again to play.", ep.title)) },
            confirmButton = { TextButton(onClick = { library.downloads.delete(ep.id); deleting = null }) { Text(tr("Remove")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Keep")) } },
        )
    }
}

@Composable
private fun DownloadButton(state: DownloadState, enabled: Boolean, onStart: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        when (state) {
            DownloadState.None -> IconButton(onClick = onStart, enabled = enabled) { Icon(Icons.Default.Download, tr("Download")) }
            DownloadState.Waiting -> IconButton(onClick = onCancel) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            is DownloadState.Running -> IconButton(onClick = onCancel) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { state.progress }, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Text("${(state.progress * 100).toInt()}", fontSize = 10.sp)
                }
            }
            DownloadState.Done -> IconButton(onClick = onDelete) { Icon(Icons.Default.CheckCircle, tr("Downloaded"), tint = Colors.levelZero) }
            is DownloadState.Failed -> IconButton(onClick = onStart, enabled = enabled) {
                Icon(Icons.Default.ErrorOutline, tr("Failed: %s. Tap to try again", state.message), tint = Colors.unknown)
            }
        }
    }
}

/** The show's poster (never an episode frame: no spoilers) fading into the page, title, info and Play. */
@Composable
private fun ShowHeader(library: Library, show: Show, meta: org.json.JSONObject?, next: Episode?, wide: Boolean, onBack: () -> Unit,
                       onPlay: (Episode) -> Unit, modifier: Modifier, onFix: (() -> Unit)?) {
    val image: Any = library.poster(show.id, show.poster)
    Box(modifier) {
        val art = if (wide) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 10f)
        NeutralPoster(show.title, art, showTitle = false) // the header writes the title itself
        AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = art)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent, 0.45f to Colors.background.copy(alpha = 0.35f), 1f to Colors.background)))
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)
            .background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), tint = Color.White)
        }
        if (onFix != null) TextButton(onClick = onFix, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))) { Text(tr("Fix match"), color = Color.White) }
        // Top padding: a long title must not run under the back button.
        Column(Modifier.align(Alignment.BottomStart).let { if (wide) it.navigationBarsPadding() else it }.statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(show.title, fontSize = if (wide) 30.sp else 24.sp, lineHeight = if (wide) 36.sp else 30.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val n = show.episodes.size
            val total = show.episodes.sumOf { it.duration }
            val seen = show.episodes.sumOf { it.seen }
            val scenes = show.episodes.sumOf { it.scenes }
            Text(listOfNotNull(if (n == 1) tr("1 episode") else tr("%d episodes", n), minutes(total).takeIf { total > 0 },
                tr("%1\$d of %2\$d scenes seen", seen, scenes).takeIf { seen > 0 }).joinToString(" · "),
                fontSize = 14.sp, color = Colors.dim)
            (meta?.let { io.github.pedrubik2000.kumapie.mobile.local.ShowMeta.description(it, io.github.pedrubik2000.kumapie.data.Lang.speaker) }
                ?: show.description.ifBlank { null })?.let { text ->
                var all by remember { mutableStateOf(false) }
                Text(text, fontSize = 14.sp, maxLines = if (all) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { all = !all })
            }
            if (next != null) {
                val partway = (next.resume ?: 0.0).let { it > 20 && it < next.duration * 0.95 }
                Button(onClick = { onPlay(next) }) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(if (partway || next != show.episodes.first()) tr("Continue: %s", next.title) else tr("Play"),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/**
 * Long press: "Mark as watched" (seen elsewhere: the feed may use its scenes, its picture is no longer blurred) and
 * "Mark as not watched" (back to unseen, from the beginning).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(library: Library, ep: Episode, watchedTitle: String?, state: DownloadState, playable: Boolean, copy: Boolean, offline: Boolean, wide: Boolean,
                       onPlay: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit, onListen: () -> Unit,
                       onMarkWatched: (Boolean) -> Unit, fetched: Float? = null, onFetch: (() -> Unit)? = null) {
    val onDevice = io.github.pedrubik2000.kumapie.mobile.local.LocalEpisodes.isLocal(ep.id) // made here
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(enabled = playable, onClick = onPlay, onLongClick = { menu = true })
            .alpha(if (playable) 1f else 0.4f).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(if (wide) 160.dp else 120.dp)) {
            val hide = library.settings.blurUnwatched && !ep.watched
            AsyncImage(library.thumb(ep.id, ep.thumb), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                    .then(if (hide) Modifier.blur(16.dp) else Modifier))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!ep.watched) DropdownMenuItem(text = { Text(tr("Mark as watched")) }, onClick = { menu = false; onMarkWatched(true) })
                if (ep.seen > 0 || (ep.resume ?: 0.0) > 0) DropdownMenuItem(text = { Text(tr("Mark as not watched")) }, onClick = { menu = false; onMarkWatched(false) })
            }
            val resume = ep.resume
            if (resume != null && resume > 0 && ep.duration > 0) ProgressBar((resume / ep.duration).toFloat(),
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(watchedTitle?.let { "${ep.title} · $it" } ?: ep.title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (onDevice) (if (copy) tr("%s · on this device", minutes(ep.duration)) else tr("%s · on the PC", minutes(ep.duration))) else tr("%1\$s · %2\$d of %3\$d scenes easy", minutes(ep.duration), ep.easy, ep.scenes) +
                if (ep.seen > 0) tr(" · %d seen", ep.seen) else "", fontSize = 12.sp, color = Colors.dim)
        }
        // Condensed: only the speech, in the background (screen off), like the PC's condensed audio.
        IconButton(enabled = playable, onClick = onListen) { Icon(Icons.Default.Headphones, tr("Listen condensed")) }
        if (onDevice && fetched != null) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { fetched }, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        } else if (onDevice && onFetch != null) IconButton(onClick = onFetch) { Icon(Icons.Default.Download, tr("Download")) }
        if (onDevice) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, tr("Delete")) }
        else DownloadButton(state, enabled = !offline || state == DownloadState.Done, onStart = onDownload, onCancel = onCancel, onDelete = onDelete)
    }
}

/**
 * A show's type and a title search over AniList / TVmaze / Wikidata (python/meta.py): "Fix match" on a show (item 21)
 * and the Add sheet (item 20). [onPick] gets the type and the candidate; the dialog closes when it returns. Needs internet.
 */
@Composable
internal fun MatchDialog(meta: io.github.pedrubik2000.kumapie.mobile.local.ShowMeta, startKind: String, startQuery: String, title: String,
                         onPick: suspend (String, org.json.JSONObject) -> Unit, onDismiss: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val kinds = if (startKind == "manga") listOf("manga" to tr("Manga"))
        else listOf("anime" to tr("Anime"), "shows" to tr("Shows"), "movies" to tr("Movies"), "visual_novels" to tr("Visual novels"))
    var kind by remember { mutableStateOf(startKind.takeIf { k -> kinds.any { it.first == k } } ?: "shows") }
    var query by remember { mutableStateOf(startQuery) }
    var found by remember { mutableStateOf<List<org.json.JSONObject>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun find() {
        busy = true; error = null
        scope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { meta.search(kind, query) } }
                .onSuccess { found = it }.onFailure { error = tr("Couldn't search (internet?): %s", it.message ?: "") }
            busy = false
        }
    }
    fun pick(c: org.json.JSONObject) {
        busy = true
        scope.launch {
            runCatching { onPick(kind, c) }.onSuccess { onDismiss() }.onFailure { error = it.message ?: it.toString(); busy = false }
        }
    }
    LaunchedEffect(Unit) { if (query.isNotBlank()) find() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.forEach { (k, label) ->
                        androidx.compose.material3.FilterChip(selected = kind == k, label = { Text(label) },
                            onClick = { kind = k; find() })
                    }
                }
                androidx.compose.material3.OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { TextButton(onClick = ::find, enabled = !busy) { Text(tr("Search")) } })
                error?.let { Text(it, color = Colors.unknown, fontSize = 13.sp) }
                if (busy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(found.orEmpty()) { c ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { pick(c) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(48.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp))) {
                                NeutralPoster(c.optString("title"), Modifier.fillMaxSize(), fontSize = 8.sp)
                                AsyncImage(c.optString("poster").ifBlank { null }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(c.optString("title"), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(c.optInt("year").takeIf { it > 0 }?.toString(), c.optString("source"))
                                    .joinToString(" · "), fontSize = 12.sp, color = Colors.dim)
                            }
                        }
                    }
                    if (found?.isEmpty() == true) item { Text(tr("No match found"), color = Colors.dim) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
    )
}
