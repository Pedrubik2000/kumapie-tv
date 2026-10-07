package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.clickable
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

/** A show's episodes: progress, how many scenes are easy today, and a download button for each. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowScreen(library: Library, initial: Show, onPlay: (Episode) -> Unit, onBack: () -> Unit) {
    val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(show.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(show.episodes, key = { it.id }) { ep ->
                val state = states[ep.id] ?: DownloadState.None
                val onDevice = io.github.pedrubik2000.kumapie.mobile.local.LocalEpisodes.isLocal(ep.id) // made here
                val playable = onDevice || !offline || state == DownloadState.Done
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = playable) { onPlay(ep) }
                        .alpha(if (playable) 1f else 0.4f).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(library.thumb(ep.id, ep.thumb), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(if (narrow) 96.dp else 128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(ep.title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (onDevice) tr("%s · on this device", minutes(ep.duration)) else tr("%1\$s · %2\$d of %3\$d scenes easy", minutes(ep.duration), ep.easy, ep.scenes) +
                            if (ep.seen > 0) tr(" · %d seen", ep.seen) else "", fontSize = 12.sp, color = Colors.dim)
                        val resume = ep.resume
                        if (resume != null && ep.duration > 0) ProgressBar((resume / ep.duration).toFloat(), Modifier.fillMaxWidth())
                    }
                    // Condensed: only the speech, in the background (screen off), like the PC's condensed audio.
                    IconButton(enabled = playable, onClick = {
                        val art = library.thumb(ep.id, ep.thumb).let { if (it is java.io.File) android.net.Uri.fromFile(it).toString() else it.toString() }
                        io.github.pedrubik2000.kumapie.mobile.listen.CondensedService.play(context, ep.id, art)
                    }) { Icon(Icons.Default.Headphones, tr("Listen condensed")) }
                    if (!onDevice) DownloadButton(state, enabled = !offline || state == DownloadState.Done,
                        onStart = { library.downloads.start(show, ep) },
                        onCancel = { library.downloads.cancel(ep.id) },
                        onDelete = { deleting = ep })
                }
            }
        }
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
