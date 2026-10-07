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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Translate
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
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.offline.DownloadState
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors

/** Home's categories, in this order (the PC's library folders, and reels for YouTube shorts made here). */
private val KINDS = listOf("anime" to "Anime", "shows" to "Shows", "movies" to "Movies", "youtube" to "YouTube", "reels" to "Reels")

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
    onImprove: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit = {},
    onDownloads: () -> Unit = {},
    /** The language Home, the feed, i+1 and unlock show ([io.github.pedrubik2000.kumapie.data.Lang.code]). */
    homeLang: String = "de",
    onHomeLang: (String) -> Unit = {},
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

    // A phone has room for the title and three icons; the rest go in the ⋮ menu there (a tablet shows them all).
    val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
    var menu by remember { mutableStateOf(false) }
    val more = listOf(
        Triple(Icons.Default.Refresh, tr("Refresh")) { attempt += 1 },
        Triple(Icons.Default.School, tr("Grammar"), onGrammar),
        Triple(Icons.Default.Healing, tr("Cards that don't stick"), onImprove),
        Triple(Icons.Default.BarChart, tr("Stats"), onStats),
        Triple(Icons.Default.Translate, tr("Japanese dictionary"), onSearch),
        Triple(Icons.Default.Settings, tr("Settings"), onSettings),
    )
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (offline) tr("kumapie · offline") else "kumapie", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, tr("Add an episode")) }
                IconButton(onClick = onFeed) { Icon(Icons.Default.Swipe, tr("Feed")) }
                IconButton(onClick = onIPlusOne) { Icon(Icons.Default.AutoAwesome, tr("i+1 scenes")) }
                // Downloads: how many jobs are running or waiting.
                val jobList by remember { io.github.pedrubik2000.kumapie.mobile.local.ProcessWorker.jobs(context) }.collectAsState(initial = emptyList())
                val active = jobList.count { it.state == "running" || it.state == "waiting" }
                IconButton(onClick = onDownloads) {
                    androidx.compose.material3.BadgedBox(badge = { if (active > 0) androidx.compose.material3.Badge { Text("$active") } }) {
                        Icon(Icons.Default.Download, tr("Downloads"))
                    }
                }
                if (!narrow) more.forEach { (icon, label, go) -> IconButton(onClick = go) { Icon(icon, label) } }
                else Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, tr("More")) }
                    androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                        more.forEach { (icon, label, go) ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) },
                                onClick = { menu = false; go() })
                        }
                    }
                }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        // Language switch (only languages with shows), then categories (only those with shows in that language).
        val all = shows.orEmpty()
        val langs = io.github.pedrubik2000.kumapie.data.Lang.ALL.filter { l -> all.any { it.lang == l.code } }
        val inLang = all.filter { it.lang == homeLang }
        var kind by remember { mutableStateOf(library.settings.prefs.getString("home_kind", "") ?: "") }
        val kinds = KINDS.filter { (k, _) -> inLang.any { it.kind == k } }
        if (langs.size > 1 || kinds.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (langs.size > 1) langs.forEach { l ->
                FilterChip(selected = homeLang == l.code, onClick = { onHomeLang(l.code) }, label = { Text(tr(l.name)) })
            }
            if (langs.size > 1 && kinds.size > 1) Text("·", color = Colors.dim)
            if (kinds.size > 1) {
                FilterChip(selected = kind.isEmpty() || kinds.none { it.first == kind }, onClick = { kind = ""; library.settings.prefs.edit().putString("home_kind", "").apply() },
                    label = { Text(tr("All")) })
                kinds.forEach { (k, label) ->
                    FilterChip(selected = kind == k, onClick = { kind = k; library.settings.prefs.edit().putString("home_kind", k).apply() },
                        label = { Text(tr(label)) })
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            val list = shows?.let { _ -> inLang.filter { kind.isEmpty() || kinds.none { k -> k.first == kind } || it.kind == kind } }
            when {
                error != null && list == null -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("Can't reach the PC"), fontSize = 20.sp)
                    Text(error ?: "", color = Colors.dim, fontSize = 14.sp)
                    Button(onClick = { attempt++ }) { Text(tr("Try again")) }
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
                            Text((if (n == 1) tr("1 episode") else tr("%d episodes", n)) + if (downloaded > 0) tr(" · %d saved", downloaded) else "",
                                fontSize = 12.sp, color = Colors.dim)
                        }
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
