package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.pedrubik2000.kumapie.data.resumeLine
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.TextButton
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
import io.github.pedrubik2000.kumapie.data.ahead
import io.github.pedrubik2000.kumapie.data.easyPercent
import io.github.pedrubik2000.kumapie.data.levelLine
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.offline.DownloadState
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.ui.NeutralPoster

/** Home's categories, in this order (the PC's library folders). Reels (YouTube shorts made here) are feed-only. */
private val KINDS = listOf("anime" to "Anime", "shows" to "Shows", "movies" to "Movies", "youtube" to "YouTube", "visual_novels" to "Visual novels")

/**
 * The languages this device learns: the ones its shows are in (saved each time Home gets the list), so a parent's phone
 * shows nothing German or Japanese. Before the first list: all.
 */
fun io.github.pedrubik2000.kumapie.data.Settings.learning(): Set<String> =
    prefs.getStringSet("learning", null)?.takeIf { it.isNotEmpty() } ?: io.github.pedrubik2000.kumapie.data.Lang.ALL.map { it.code }.toSet()

private class Dest(val icon: ImageVector, val label: String, val go: () -> Unit, val badge: Int = 0, val short: String = label)

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
    onImprove: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit = {},
    onDownloads: () -> Unit = {},
    /** An episode of "Continue watching": straight to the player, where it was left. */
    onPlay: (Show, Episode) -> Unit = { s, _ -> onShow(s) },
    /** The language Home, the feed and i+1 show ([io.github.pedrubik2000.kumapie.data.Lang.code]). */
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

    // The rail needs height too: a phone on its side (~410 dp tall) keeps the bottom bar, or the rail runs off-screen.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val wide = config.screenWidthDp >= 600 && config.screenHeightDp >= 560
    val main = listOf(
        Dest(Icons.Default.Swipe, tr("Feed"), onFeed),
        Dest(Icons.Default.AutoAwesome, tr("i+1 scenes"), onIPlusOne),
        Dest(Icons.Default.Download, tr("Downloads"), onDownloads, badge = active),
    )
    // Cards that don't stick (German Core 1000) only in German; the Japanese dictionary only in Japanese.
    val extra = listOfNotNull(
        Dest(Icons.Default.BarChart, tr("Stats"), onStats),
        Dest(Icons.Default.Healing, tr("Cards that don't stick"), onImprove, short = tr("Hard cards")),
        Dest(Icons.Default.Translate, tr("Japanese dictionary"), onSearch, short = tr("Dictionary")).takeIf { homeLang == "ja" },
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
                langs.forEach { l -> FilterChip(selected = lang == l.code, onClick = { onHomeLang(l.code) }, label = { Text(tr(l.name)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Colors.accent, selectedLabelColor = Color.Black)) }
            }
            if (offline) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Offline: only episodes on this device play."), color = Colors.dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { attempt++ }) { Text(tr("Try again")) }
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
                    all.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tr("No episodes yet"), fontSize = 20.sp)
                        Text(tr("Add a YouTube link or a video file."), color = Colors.dim, fontSize = 14.sp)
                        Button(onClick = { adding = true }) { Text(tr("Add an episode")) }
                    }
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
                NavigationRailItem(selected = false, onClick = d.go, label = { Text(d.short, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { BadgedBox(badge = { if (d.badge > 0) Badge { Text("${d.badge}") } }) { Icon(d.icon, d.label) } })
            }
        }
        Scaffold(topBar = { HomeBar { attempt++ } }) { content(it) }
    } else Scaffold(
        topBar = { HomeBar(onAdd = { adding = true }) { attempt++ } },
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
            AddEpisodeSheet(library, sharedLink, homeLang)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeBar(onAdd: (() -> Unit)? = null, onRefresh: () -> Unit) {
    TopAppBar(
        title = { Text("kumapie", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            if (onAdd != null) IconButton(onClick = onAdd) { Icon(Icons.Default.Add, tr("Add an episode")) }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, tr("Refresh")) }
        },
    )
}

/**
 * What you were watching owns the top: the last episode as a big card (one tap plays it where it was left), the other
 * shows in progress as small tiles. Then the library, one media type per tab, easiest first. A tablet puts the
 * library beside the resume column (user, 2026-10-15: Home is for resuming fast).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Rows(library: Library, shows: List<Show>, states: Map<String, DownloadState>, wide: Boolean,
                 onShow: (Show) -> Unit, onPlay: (Show, Episode) -> Unit) {
    val going = io.github.pedrubik2000.kumapie.data.continueRow(shows) // novels are read, not watched: they count too
    // Resume column beside the library only at expanded width (a tablet on its side); upright it's one column.
    val split = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
    val other = shows.filter { s -> s.kind != "reels" && KINDS.none { it.first == s.kind } }
    // Easiest first, finished last; no easy count yet (not synced) between them.
    val kinds = (KINDS + listOf("" to "Other")).map { (k, label) ->
        tr(label) to (if (k.isEmpty()) other else shows.filter { it.kind == k })
            .sortedWith(compareBy({ ahead(it).isEmpty() }, { -(easyPercent(it) ?: -1) }))
    }.filter { it.second.isNotEmpty() }
    var tab by rememberSaveable(shows.firstOrNull()?.lang) { mutableIntStateOf(0) }
    val picked = tab.coerceIn(0, (kinds.size - 1).coerceAtLeast(0))
    val tabs = @Composable {
        if (kinds.size > 1) PrimaryScrollableTabRow(selectedTabIndex = picked, edgePadding = 16.dp, containerColor = Color.Transparent,
            divider = { HorizontalDivider(color = Colors.surface) }) {
            kinds.forEachIndexed { i, (label, _) ->
                Tab(selected = i == picked, onClick = { tab = i }, text = { Text(label) }, unselectedContentColor = Colors.dim)
            }
        } else kinds.firstOrNull()?.let { Text(it.first, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
    }
    val grid = @Composable { width: Dp ->
        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            kinds.getOrNull(picked)?.second?.forEach { s -> Poster(library.poster(s.id, s.poster), s.title, levelLine(s), width) { onShow(s) } }
        }
    }
    val resume = @Composable {
        val (s, e) = going[0]
        ResumeCard(library.poster(s.id, s.poster), s, e) { onPlay(s, e) }
    }

    if (split && going.isNotEmpty()) Row(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(0.42f).fillMaxHeight()) {
        // A phone on its side is short: the card narrows until its art and text fit the height (~110 dp under the art).
        val cardWidth = minOf(maxWidth, (maxHeight - 126.dp) * 16f / 9f + 32.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(cardWidth)) { resume() }
            going.drop(1).forEach { (s, e) -> Tile(library.poster(s.id, s.poster), s, e, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { onPlay(s, e) } }
        }
        }
        Column(Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState())) { tabs(); grid(posterWidth(true)) }
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
        // A phone: three posters across (more on its side); a tablet with nothing in progress: the tablet's poster size.
        val across = maxOf(3, (maxWidth / 130.dp).toInt())
        val width = if (wide) posterWidth(true) else (maxWidth - 32.dp - 12.dp * (across - 1)) / across
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            if (going.isNotEmpty()) item(key = "resume") { Box(Modifier.widthIn(max = 600.dp)) { resume() } } // on its side: not a screen-tall card
            if (going.size > 1) item(key = "going") {
                Column(Modifier.widthIn(max = 600.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    going.drop(1).forEach { (s, e) -> Tile(library.poster(s.id, s.poster), s, e, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { onPlay(s, e) } }
                }
            }
            item(key = "tabs") { Column(Modifier.padding(top = if (going.isEmpty()) 0.dp else 20.dp)) { tabs() } }
            item(key = "grid-$picked") { grid(width) }
        }
    }
}

/** The episode left most recently: the show's poster cropped wide, where it was left and how easy it is. */
@Composable
private fun ResumeCard(image: Any?, s: Show, e: Episode, onPlay: () -> Unit) {
    Surface(onClick = onPlay, shape = RoundedCornerShape(16.dp), color = Colors.surface, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                NeutralPoster(s.title, Modifier.matchParentSize(), showTitle = false)
                AsyncImage(image, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.matchParentSize())
                // The art fades into the card, so the title under it reads as part of it.
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Colors.surface)))
                FilledIconButton(onClick = onPlay, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Colors.accent, contentColor = Color.Black)) {
                    Icon(Icons.Default.PlayArrow, tr("Play"), Modifier.size(36.dp))
                }
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // Two lines, so a narrow card never cuts off how easy it is.
                Text(e.title, style = MaterialTheme.typography.bodyMedium, color = Colors.dim, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Text(resumeLine(e), style = MaterialTheme.typography.bodyMedium, color = Colors.dim, maxLines = 1)
                ProgressBar(((e.resume ?: 0.0) / e.duration).toFloat(), Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
    }
}

/** Another show in progress: a small poster, the episode, time left; one tap plays it. */
@Composable
private fun Tile(image: Any?, s: Show, e: Episode, modifier: Modifier, onPlay: () -> Unit) {
    Surface(onClick = onPlay, shape = RoundedCornerShape(12.dp), color = Colors.surface, modifier = modifier) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(48.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
                NeutralPoster(s.title, Modifier.matchParentSize(), showTitle = false)
                AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.title, style = MaterialTheme.typography.bodySmall, color = Colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(resumeLine(e), style = MaterialTheme.typography.bodySmall, color = Colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ProgressBar(((e.resume ?: 0.0) / e.duration).toFloat(), Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

// A tablet: a little smaller, so Continue and a whole type's grid fit on the first screen in landscape.
internal fun posterWidth(wide: Boolean) = if (wide) 112.dp else 118.dp

/** A poster with its title and [sub] below (and [progress] over it): shows and episodes look the same. */
@Composable
internal fun Poster(image: Any?, title: String, sub: String, width: Dp, progress: Float? = null, more: String? = null,
                    onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.width(width).combinedClickable(onLongClick = onLongClick, onClick = onClick)) {
        Box {
            val art = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
            NeutralPoster(title, art, fontSize = 15.sp) // shows where there's no poster (never an episode frame)
            AsyncImage(image, title, contentScale = ContentScale.Crop, modifier = art)
            if (progress != null) ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = Colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        more?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Colors.dim, maxLines = 1) }
    }
}
