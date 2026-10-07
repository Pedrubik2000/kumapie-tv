package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.mobile.offline.DownloadState
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
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

    LaunchedEffect(Unit) { // fresh progress after watching
        runCatching { library.shows() }.onSuccess { (shows, off) ->
            offline = off
            shows.firstOrNull { it.id == initial.id }?.let { show = it }
        }
    }

    fun playable(ep: Episode) = io.github.pedrubik2000.kumapie.mobile.local.LocalEpisodes.isLocal(ep.id) || !offline ||
        states[ep.id] == DownloadState.Done
    // Play: the episode left partway, else the first not watched to the end, else the first.
    val next = show.episodes.filter(::playable).let { eps ->
        eps.firstOrNull { e -> (e.resume ?: 0.0).let { it > 20 && it < e.duration * 0.95 } }
            ?: eps.firstOrNull { it.seen < it.scenes } ?: eps.firstOrNull()
    }
    val header = @Composable { modifier: Modifier ->
        ShowHeader(library, show, next, wide, onBack, onPlay, modifier)
    }
    val row = @Composable { ep: Episode ->
        EpisodeRow(library, ep, states[ep.id] ?: DownloadState.None, playable(ep), offline, wide,
            onPlay = { onPlay(ep) }, onDownload = { library.downloads.start(show, ep) },
            onCancel = { library.downloads.cancel(ep.id) }, onDelete = { deleting = ep },
            onListen = {
                val art = library.thumb(ep.id, ep.thumb).let { if (it is java.io.File) android.net.Uri.fromFile(it).toString() else it.toString() }
                io.github.pedrubik2000.kumapie.mobile.listen.CondensedService.play(context, ep.id, art)
            })
    }
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Colors.background, contentColor = Colors.text) {
        if (wide) Row(Modifier.fillMaxSize()) {
            header(Modifier.weight(0.42f).fillMaxHeight())
            LazyColumn(Modifier.weight(0.58f).fillMaxHeight(), contentPadding = PaddingValues(vertical = 24.dp)) {
                items(show.episodes, key = { it.id }) { row(it) }
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") { header(Modifier.fillMaxWidth()) }
            items(show.episodes, key = { it.id }) { row(it) }
        }
    }

    deleting?.takeIf { io.github.pedrubik2000.kumapie.mobile.local.LocalEpisodes.isLocal(it.id) }?.let { ep ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Delete from this device?")) },
            text = { Text(tr("%s stays on the PC and your other devices, and won't download here again.", ep.title)) },
            confirmButton = { TextButton(onClick = {
                io.github.pedrubik2000.kumapie.mobile.local.Sync(context).forget(ep.id)
                show = show.copy(episodes = show.episodes.filter { it.id != ep.id })
                deleting = null
            }) { Text(tr("Delete")) } },
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

/** The show's picture (a frame on a phone, the poster on a tablet) fading into the page, title, info and Play. */
@Composable
private fun ShowHeader(library: Library, show: Show, next: Episode?, wide: Boolean, onBack: () -> Unit,
                       onPlay: (Episode) -> Unit, modifier: Modifier) {
    val image: Any = if (wide) library.poster(show.id, show.poster)
        else show.episodes.firstOrNull()?.let { library.thumb(it.id, it.thumb) } ?: library.poster(show.id, show.poster)
    Box(modifier) {
        AsyncImage(image, null, contentScale = ContentScale.Crop,
            modifier = if (wide) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 10f))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent, 0.45f to Colors.background.copy(alpha = 0.35f), 1f to Colors.background)))
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)
            .background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), tint = Color.White)
        }
        Column(Modifier.align(Alignment.BottomStart).let { if (wide) it.navigationBarsPadding() else it }.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(show.title, fontSize = if (wide) 30.sp else 24.sp, lineHeight = if (wide) 36.sp else 30.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val n = show.episodes.size
            val total = show.episodes.sumOf { it.duration }
            val seen = show.episodes.sumOf { it.seen }
            val scenes = show.episodes.sumOf { it.scenes }
            Text(listOfNotNull(if (n == 1) tr("1 episode") else tr("%d episodes", n), minutes(total).takeIf { total > 0 },
                tr("%1\$d of %2\$d scenes seen", seen, scenes).takeIf { seen > 0 }).joinToString(" · "),
                fontSize = 14.sp, color = Colors.dim)
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

@Composable
private fun EpisodeRow(library: Library, ep: Episode, state: DownloadState, playable: Boolean, offline: Boolean, wide: Boolean,
                       onPlay: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit, onListen: () -> Unit) {
    val onDevice = io.github.pedrubik2000.kumapie.mobile.local.LocalEpisodes.isLocal(ep.id) // made here
    Row(
        Modifier.fillMaxWidth().clickable(enabled = playable, onClick = onPlay)
            .alpha(if (playable) 1f else 0.4f).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(if (wide) 160.dp else 120.dp)) {
            AsyncImage(library.thumb(ep.id, ep.thumb), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
            val resume = ep.resume
            if (resume != null && ep.duration > 0) ProgressBar((resume / ep.duration).toFloat(),
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(ep.title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (onDevice) tr("%s · on this device", minutes(ep.duration)) else tr("%1\$s · %2\$d of %3\$d scenes easy", minutes(ep.duration), ep.easy, ep.scenes) +
                if (ep.seen > 0) tr(" · %d seen", ep.seen) else "", fontSize = 12.sp, color = Colors.dim)
        }
        // Condensed: only the speech, in the background (screen off), like the PC's condensed audio.
        IconButton(enabled = playable, onClick = onListen) { Icon(Icons.Default.Headphones, tr("Listen condensed")) }
        if (onDevice) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, tr("Delete")) }
        else DownloadButton(state, enabled = !offline || state == DownloadState.Done, onStart = onDownload, onCancel = onCancel, onDelete = onDelete)
    }
}
