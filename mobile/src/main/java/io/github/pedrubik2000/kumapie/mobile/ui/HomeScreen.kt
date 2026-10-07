package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.offline.DownloadState
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors

/** Home's categories, in this order (the PC's library folders, and reels for YouTube shorts made here). */
private val KINDS = listOf("anime" to "Anime", "shows" to "Shows", "movies" to "Movies", "youtube" to "YouTube", "reels" to "Reels", "novels" to "Novels")

/**
 * The languages this device learns: the ones its shows are in (saved each time Home gets the list), so a parent's phone
 * shows nothing German or Japanese. Before the first list: all.
 */
fun io.github.pedrubik2000.kumapie.data.Settings.learning(): Set<String> =
    prefs.getStringSet("learning", null)?.takeIf { it.isNotEmpty() } ?: io.github.pedrubik2000.kumapie.data.Lang.ALL.map { it.code }.toSet()

private class Dest(val icon: ImageVector, val label: String, val go: () -> Unit, val badge: Int = 0)

/**
 * Home: the language switch, then rows of posters (streaming-app style): "Continue watching", then one row per category.
 * Navigation: a bottom bar on a phone (Home, Feed, i+1, Downloads, More), a side rail on a tablet (everything).
 * Offline: the last list the PC sent, with only downloaded episodes playable.
 */
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
    /** An episode of "Continue watching": straight to the player, where it was left. */
    onPlay: (Show, Episode) -> Unit = { s, _ -> onShow(s) },
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
    var more by remember { mutableStateOf(false) }
    val states by library.downloads.states().collectAsState(initial = emptyMap())

    // A new episode finished on the tablet: show it.
    val context = androidx.compose.ui.platform.LocalContext.current
    val jobs by remember { io.github.pedrubik2000.kumapie.mobile.local.ProcessWorker.states(context) }.collectAsState(initial = emptyList())
    val finished = jobs.count { it.startsWith("Done") }
    LaunchedEffect(finished) { if (finished > 0) attempt++ }
    val jobList by remember { io.github.pedrubik2000.kumapie.mobile.local.ProcessWorker.jobs(context) }.collectAsState(initial = emptyList())
    val active = jobList.count { it.state == "running" || it.state == "waiting" }

    LaunchedEffect(attempt) {
        error = null
        runCatching { library.shows() }
            .onSuccess { (s, off) ->
                shows = s; offline = off; onShows(s)
                if (s.isNotEmpty()) library.settings.prefs.edit().putStringSet("learning", s.map { it.lang }.toSet()).apply()
            }
            .onFailure { error = it.message ?: it.toString() }
    }

    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val main = listOf(
        Dest(Icons.Default.Swipe, tr("Feed"), onFeed),
        Dest(Icons.Default.AutoAwesome, tr("i+1 scenes"), onIPlusOne),
        Dest(Icons.Default.Download, tr("Downloads"), onDownloads, badge = active),
    )
    // German grammar and cards that don't stick (German Core 1000) only with German; the Japanese dictionary with Japanese.
    val learning = remember(shows) { library.settings.learning() }
    val extra = listOfNotNull(
        Dest(Icons.Default.BarChart, tr("Stats"), onStats),
        Dest(Icons.Default.School, tr("Grammar"), onGrammar).takeIf { "de" in learning },
        Dest(Icons.Default.Healing, tr("Cards that don't stick"), onImprove).takeIf { "de" in learning },
        Dest(Icons.Default.Translate, tr("Japanese dictionary"), onSearch).takeIf { "ja" in learning },
        Dest(Icons.Default.Settings, tr("Settings"), onSettings),
    )

    val content = @Composable { padding: PaddingValues ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val all = shows.orEmpty()
            val langs = io.github.pedrubik2000.kumapie.data.Lang.ALL.filter { l -> all.any { it.lang == l.code } }
            // The chosen language without shows (a parent's phone has only English; the default is German): the first that has some.
            val lang = if (shows == null || all.any { it.lang == homeLang }) homeLang else langs.firstOrNull()?.code ?: homeLang
            LaunchedEffect(lang) { if (lang != homeLang) onHomeLang(lang) }
            if (langs.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                langs.forEach { l -> FilterChip(selected = lang == l.code, onClick = { onHomeLang(l.code) }, label = { Text(tr(l.name)) }) }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    error != null && shows == null -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tr("Can't reach the PC"), fontSize = 20.sp)
                        Text(error ?: "", color = Colors.dim, fontSize = 14.sp)
                        Button(onClick = { attempt++ }) { Text(tr("Try again")) }
                    }
                    shows == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else -> Rows(library, all.filter { it.lang == lang }, states, wide, onShow, onPlay)
                }
            }
        }
    }

    if (wide) Row(Modifier.fillMaxSize()) {
        NavigationRail(header = {
            FloatingActionButton(onClick = { adding = true }, modifier = Modifier.padding(vertical = 8.dp)) { Icon(Icons.Default.Add, tr("Add an episode")) }
        }) {
            NavigationRailItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Home, null) }, label = { Text(tr("Home")) })
            (main + extra).forEach { d ->
                NavigationRailItem(selected = false, onClick = d.go, label = { Text(d.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { BadgedBox(badge = { if (d.badge > 0) Badge { Text("${d.badge}") } }) { Icon(d.icon, d.label) } })
            }
        }
        Scaffold(topBar = { HomeBar(offline) { attempt++ } }) { content(it) }
    } else Scaffold(
        topBar = { HomeBar(offline, onAdd = { adding = true }) { attempt++ } },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Home, null) }, label = { Text(tr("Home")) })
                main.forEach { d ->
                    NavigationBarItem(selected = false, onClick = d.go, label = { Text(d.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { BadgedBox(badge = { if (d.badge > 0) Badge { Text("${d.badge}") } }) { Icon(d.icon, d.label) } })
                }
                NavigationBarItem(selected = false, onClick = { more = true }, icon = { Icon(Icons.Default.MoreHoriz, null) }, label = { Text(tr("More")) })
            }
        },
    ) { content(it) }

    if (more) ModalBottomSheet(onDismissRequest = { more = false }) {
        extra.forEach { d ->
            ListItem(headlineContent = { Text(d.label) }, leadingContent = { Icon(d.icon, null) },
                modifier = Modifier.clickable { more = false; d.go() })
        }
    }
    if (adding) {
        ModalBottomSheet(onDismissRequest = { adding = false; attempt++ }) {
            AddEpisodeSheet(library, sharedLink)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeBar(offline: Boolean, onAdd: (() -> Unit)? = null, onRefresh: () -> Unit) {
    TopAppBar(
        title = { Text(if (offline) tr("kumapie · offline") else "kumapie", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            if (onAdd != null) IconButton(onClick = onAdd) { Icon(Icons.Default.Add, tr("Add an episode")) }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, tr("Refresh")) }
        },
    )
}

/** "Continue watching" (episodes left partway), then a row per category. */
@Composable
private fun Rows(library: Library, shows: List<Show>, states: Map<String, DownloadState>, wide: Boolean,
                 onShow: (Show) -> Unit, onPlay: (Show, Episode) -> Unit) {
    val width = if (wide) 150.dp else 118.dp
    val going = shows.flatMap { s -> s.episodes.map { s to it } }.filter { (_, e) ->
        val at = e.resume ?: 0.0
        e.duration > 0 && at > 20 && at < e.duration * 0.95
    }
    val other = shows.filter { s -> KINDS.none { it.first == s.kind } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (going.isNotEmpty()) item(key = "going") {
            ShelfRow(tr("Continue watching")) {
                items(going, key = { it.second.id }) { (s, e) ->
                    Poster(library.poster(s.id, s.poster), s.title, e.title, width, progress = ((e.resume ?: 0.0) / e.duration).toFloat()) { onPlay(s, e) }
                }
            }
        }
        (KINDS + listOf("" to "Other")).forEach { (k, label) ->
            val list = if (k.isEmpty()) other else shows.filter { it.kind == k }
            if (list.isNotEmpty()) item(key = "kind-$k") {
                ShelfRow(tr(label)) {
                    items(list, key = { it.id }) { s ->
                        val saved = s.episodes.count { states[it.id] == DownloadState.Done }
                        val n = s.episodes.size
                        Poster(library.poster(s.id, s.poster), s.title,
                            (if (n == 1) tr("1 episode") else tr("%d episodes", n)) + if (saved > 0) tr(" · %d saved", saved) else "", width) { onShow(s) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(title: String, items: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = items)
    }
}

@Composable
private fun Poster(image: Any, title: String, sub: String, width: Dp, progress: Float? = null, onClick: () -> Unit) {
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        Box {
            AsyncImage(image, title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
            if (progress != null) ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
        }
        Text(title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(sub, fontSize = 12.sp, color = Colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
