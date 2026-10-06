package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.mobile.offline.DownloadState
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors

/** The shows as a grid of posters. Offline: the last list the PC sent, with only downloaded episodes playable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    library: Library,
    onShows: (List<Show>) -> Unit,
    onShow: (Show) -> Unit,
    onStats: () -> Unit,
    onIPlusOne: () -> Unit,
    onFeed: () -> Unit,
    onGrammar: () -> Unit,
    onSettings: () -> Unit,
    /** A link shared to kumapie (YouTube): opens "Add an episode" with it. */
    sharedLink: String? = null,
) {
    var adding by remember { mutableStateOf(sharedLink != null) }
    var shows by remember { mutableStateOf<List<Show>?>(null) }
    var offline by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val states by library.downloads.states().collectAsState(initial = emptyMap())

    // A new episode finished on the tablet: show it.
    val context = androidx.compose.ui.platform.LocalContext.current
    val jobs by remember { io.github.pedrubik2000.kumapie.mobile.local.ProcessWorker.states(context) }.collectAsState(initial = emptyList())
    val finished = jobs.count { it.startsWith("Done") }
    LaunchedEffect(finished) { if (finished > 0) attempt++ }

    LaunchedEffect(attempt) {
        error = null
        runCatching { library.shows() }
            .onSuccess { (s, off) -> shows = s; offline = off; onShows(s) }
            .onFailure { error = it.message ?: it.toString() }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (offline) "kumapie · offline" else "kumapie") },
            actions = {
                IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add an episode") }
                IconButton(onClick = { attempt++ }) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(onClick = onFeed) { Icon(Icons.Default.Swipe, "Feed") }
                IconButton(onClick = onIPlusOne) { Icon(Icons.Default.AutoAwesome, "i+1 scenes") }
                IconButton(onClick = onGrammar) { Icon(Icons.Default.School, "Grammar") }
                IconButton(onClick = onStats) { Icon(Icons.Default.BarChart, "Stats") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
            },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = shows
            when {
                error != null && list == null -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Can't reach the PC", fontSize = 20.sp)
                    Text(error ?: "", color = Colors.dim, fontSize = 14.sp)
                    Button(onClick = { attempt++ }) { Text("Try again") }
                }
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyVerticalGrid(
                    GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(list, key = { it.id }) { show ->
                        val downloaded = show.episodes.count { states[it.id] == DownloadState.Done }
                        Column(Modifier.clickable { onShow(show) }) {
                            AsyncImage(library.poster(show.id, show.poster), show.title, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
                            Text(show.title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp))
                            val n = show.episodes.size
                            Text((if (n == 1) "1 episode" else "$n episodes") + if (downloaded > 0) " · $downloaded saved" else "",
                                fontSize = 12.sp, color = Colors.dim)
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { adding = false; attempt++ }) {
            AddEpisodeSheet(library, sharedLink)
        }
    }
}
