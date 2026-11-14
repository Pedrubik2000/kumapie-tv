package io.github.pedrubik2000.kumapie.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.LangTotal
import io.github.pedrubik2000.kumapie.data.ahead
import io.github.pedrubik2000.kumapie.data.continueRow
import io.github.pedrubik2000.kumapie.data.easyPercent
import io.github.pedrubik2000.kumapie.data.levelLine
import io.github.pedrubik2000.kumapie.data.resumeLine
import io.github.pedrubik2000.kumapie.data.shortTitle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.ui.NeutralPoster
import io.github.pedrubik2000.kumapie.ui.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** The PC's server (dojo-feed); KUMAPIE_SERVER overrides it. */
val SERVER: String = System.getenv("KUMAPIE_SERVER")?.trimEnd('/') ?: "http://127.0.0.1:8769"
val LANGS = mapOf("de" to "Deutsch", "en" to "English", "ja" to "日本語")

fun main() {
    Arcade.apply() // the TV's colours, before anything draws
    Thread { runCatching { Vlc.factory } }.start() // libVLC takes seconds to start the first time: not on the first episode's click
    application { KumapieWindow() }
}

@Composable
private fun androidx.compose.ui.window.ApplicationScope.KumapieWindow() {
    val state = rememberWindowState(width = 1500.dp, height = 950.dp)
    var before by remember { mutableStateOf(WindowPlacement.Floating) }
    toggleFullscreen = {
        if (state.placement == WindowPlacement.Fullscreen) state.placement = before
        else { before = state.placement; state.placement = WindowPlacement.Fullscreen }
    }
    Window(onCloseRequest = ::exitApplication, title = "kumapie", state = state, onPreviewKeyEvent = { e ->
        // The player's keys first; then F11 toggles fullscreen and Esc leaves it.
        keys?.invoke(e) == true || e.type == KeyEventType.KeyDown && when {
            e.key == Key.F11 -> { toggleFullscreen(); true } // not F: it would fire while typing in the mine form
            e.key == Key.Escape && state.placement == WindowPlacement.Fullscreen -> { toggleFullscreen(); true }
            else -> false
        }
    }) {
        App()
    }
}

/** Fullscreen on / off (F11, the player's ⛶ button); set by the window. */
var toggleFullscreen: () -> Unit = {}

/** The open player's keys (the window hands every key to it first); null elsewhere. */
var keys: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null

private sealed interface Screen {
    data object Home : Screen
    data class ShowPage(val show: Show) : Screen
    data class Playing(val show: Show, val episode: Episode) : Screen
}

@Composable
fun App() {
    val settings = remember { Settings(android.content.Context()) }
    val bridge = remember { Bridge(SERVER) }
    // The person: the server's first sync person (Pedro), as the page does.
    val person by produceState<String?>(null) { value = runCatching { Api(SERVER).people().first().id }.getOrDefault("") }
    var shows by remember { mutableStateOf<List<Show>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var ankiUp by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf(settings.prefs.getString("desktop_lang", "de") ?: "de") }
    val scope = rememberCoroutineScope()
    val api = person?.let { remember(it) { Api(SERVER, it) } }
    // Fresh positions and levels on every screen but the player; kuma3 asked again too (the pill).
    LaunchedEffect(api, screen) {
        if (api == null || screen is Screen.Playing) return@LaunchedEffect
        runCatching { api.shows().filter { it.kind != "visual_novels" } }
            .onSuccess { shows = it; error = null }.onFailure { error = "Can't reach $SERVER: ${it.message}" }
        ankiUp = bridge.up()
    }
    LaunchedEffect(Unit) { if (bridge.up()) runCatching { bridge.sync() } } // the phone's latest reviews first

    Box(Modifier.fillMaxSize().background(Colors.background)) {
        val list = shows
        when {
            error != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Title("Can't reach the dojo server", fontSize = 26.sp)
                Text(error!!, color = Colors.dim, modifier = Modifier.padding(vertical = 12.dp))
            }
            api == null || list == null -> Text("LOADING…", color = Colors.dim, style = Arcade.label, modifier = Modifier.align(Alignment.Center))
            else -> when (val s = screen) {
                Screen.Home -> Home(api, list, lang, ankiUp, onLang = { l -> lang = l; settings.prefs.edit().putString("desktop_lang", l).apply() }) { sh, ep ->
                    screen = if (ep == null) Screen.ShowPage(sh) else Screen.Playing(sh, ep)
                }
                // The show as the list has it now (fresh progress after the player).
                is Screen.ShowPage -> ShowPage(list.firstOrNull { it.id == s.show.id } ?: s.show, onBack = { screen = Screen.Home }) { screen = Screen.Playing(s.show, it) }
                is Screen.Playing -> PlayerScreen(api, bridge, settings, s.show, s.episode,
                    onNext = { next -> screen = Screen.Playing(s.show, next) },
                    onClose = {
                        scope.launch { if (bridge.up()) runCatching { bridge.sync() } } // after a sitting: kuma3 -> AnkiWeb -> the phone
                        screen = Screen.ShowPage(s.show)
                    })
            }
        }
    }
}

@Composable
fun Chip(label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    ArcadeButton(label, selected = on, enabled = enabled, fontSize = 13.sp, onClick = onClick)

/** Home's rows, in the TV's order. */
private val KINDS = listOf("anime" to "Anime", "shows" to "Shows", "movies" to "Movies", "youtube" to "YouTube")

/**
 * The TV's Home: KUMAPIE, the language slabs, kuma3's state; the language's hours and words; "Continue watching" (the
 * last episode big, the others small), then a row of posters per media type, easiest first, finished last.
 */
@Composable
private fun Home(api: Api, shows: List<Show>, pref: String, ankiUp: Boolean, onLang: (String) -> Unit, open: (Show, Episode?) -> Unit) {
    val langs = shows.map { it.lang }.distinct()
    val lang = if (pref in langs) pref else langs.firstOrNull()
    val mine = shows.filter { it.lang == lang }.map { s -> s.copy(episodes = s.episodes.filter { it.scenes > 0 }) }.filter { it.episodes.isNotEmpty() }
    val going = continueRow(mine)
    val other = mine.filter { s -> KINDS.none { it.first == s.kind } }
    val rows = KINDS.map { (k, label) -> label to mine.filter { it.kind == k } } + listOf("Other" to other)
    LazyColumn(Modifier.fillMaxSize().arcadeBackdrop(), contentPadding = PaddingValues(vertical = 32.dp)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Title("kuma", "pie")
                Spacer(Modifier.width(32.dp))
                if (langs.size > 1) langs.forEach { l ->
                    ArcadeButton(LANGS[l] ?: l, selected = l == lang, modifier = Modifier.padding(end = 8.dp)) { onLang(l) }
                }
                Spacer(Modifier.weight(1f))
                Text(if (ankiUp) "KUMA3 ● CONNECTED" else "KUMA3 CLOSED: COLOURS FROM YOUR PHONE, NO MINING",
                    color = if (ankiUp) Colors.due else Colors.dim, fontSize = 13.sp, style = Arcade.label,
                    modifier = Modifier.background(Arcade.slab, Slab).padding(horizontal = 22.dp, vertical = 9.dp))
            }
        }
        if (lang != null) item(key = "stats-$lang") { LangCard(api, lang) }
        if (going.isNotEmpty()) item(key = "going") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom,
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 24.dp)) {
                itemsIndexed(going, key = { _, p -> p.second.id }) { i, (s, e) ->
                    ResumeCard(s, e, width = if (i == 0) 420.dp else 240.dp) { open(s, e) }
                }
            }
        }
        rows.forEachIndexed { r, (label, group) ->
            val sorted = group.sortedWith(compareBy({ ahead(it).isEmpty() }, { -(easyPercent(it) ?: -1) }))
            if (sorted.isNotEmpty()) item(key = "kind-$r") {
                Column(Modifier.padding(start = 32.dp, top = 8.dp)) {
                    Heading(label)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp),
                        contentPadding = PaddingValues(start = 4.dp, end = 32.dp, top = 14.dp, bottom = 14.dp)) {
                        items(sorted, key = { it.id }) { s -> Poster(s) { open(s, null) } }
                    }
                }
            }
        }
    }
}

/** Hours in this language (TV + phones) and words known, as on the TV. */
@Composable
private fun LangCard(api: Api, lang: String) {
    val t by produceState<LangTotal?>(null, lang) { value = runCatching { api.stats().langs[lang] }.getOrNull() }
    val s = t ?: return
    Row(Modifier.padding(start = 32.dp, top = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(String.format(java.util.Locale.ROOT, "%.1f h", s.total / 3600) to "In total", hoursMinutes(s.week) to "Last 7 days",
            hoursMinutes(s.today) to "Today", "${s.known}" to "Words known").forEach { (v, l) ->
            Column(Modifier.background(Arcade.slab, RowSlab).padding(horizontal = 26.dp, vertical = 8.dp)) {
                Text(v, fontSize = 22.sp, color = Arcade.accent, style = Arcade.label)
                Text(l.uppercase(), fontSize = 12.sp, color = Colors.dim, style = Arcade.label)
            }
        }
    }
}

private fun hoursMinutes(seconds: Double): String {
    val m = (seconds / 60).toInt()
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

@Composable
private fun Poster(s: Show, onClick: () -> Unit) {
    val title = shortTitle(s.title)
    Column(Modifier.width(150.dp)) {
        UrlImage(s.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f).arcadeClickable(PosterSlab, onClick)) { NeutralPoster(title, Modifier.fillMaxSize()) }
        OneLine(title.uppercase(), color = Colors.text, fontSize = 14.sp, style = Arcade.label,
            modifier = Modifier.padding(top = 10.dp))
        OneLine(levelLine(s), color = Colors.dim, fontSize = 12.sp)
    }
}

/** An episode left partway: the show's poster cropped wide, then where it was left and how easy it is. */
@Composable
private fun ResumeCard(s: Show, e: Episode, width: Dp, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).arcadeClickable(RowSlab, onClick)) {
            UrlImage(s.poster, Modifier.fillMaxSize(), alignment = Alignment.TopCenter) { NeutralPoster(s.title, Modifier.fillMaxSize(), showTitle = false) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Colors.background.copy(alpha = 0.85f))))
            ProgressBar(((e.resume ?: 0.0) / e.duration).toFloat(), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp))
        }
        OneLine(shortTitle(s.title).uppercase(), color = Colors.text, fontSize = if (width > 300.dp) 22.sp else 16.sp, style = Arcade.label,
            modifier = Modifier.padding(top = 16.dp))
        OneLine("${e.title} · ${resumeLine(e)}", color = Colors.dim, fontSize = if (width > 300.dp) 15.sp else 12.sp)
    }
}

/** The TV's show page: the poster, title and description on the left, the episodes as slanted rows. */
@Composable
private fun ShowPage(sh: Show, onBack: () -> Unit, play: (Episode) -> Unit) {
    val episodes = sh.episodes.filter { it.scenes > 0 }
    val start = episodes.indices.filter { episodes[it].resume != null }.maxByOrNull { episodes[it].resumedAt } ?: 0
    val list = rememberLazyListState()
    LaunchedEffect(sh.id) { if (episodes.isNotEmpty()) list.scrollToItem(start) }
    Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
            ArcadeButton("Back", key = "Esc", modifier = Modifier.padding(bottom = 20.dp), onClick = onBack)
            UrlImage(sh.poster, Modifier.fillMaxWidth(if (sh.description.isBlank()) 0.87f else 0.7f).aspectRatio(2f / 3f).clip(PosterSlab)) {
                NeutralPoster(sh.title, Modifier.fillMaxSize(), fontSize = 22.sp)
            }
            Text(sh.title.uppercase() + (sh.year?.let { " ($it)" } ?: ""), fontSize = 22.sp, color = Colors.text, style = Arcade.label,
                modifier = Modifier.padding(top = 12.dp))
            if (sh.description.isNotBlank()) Text(sh.description, fontSize = 14.sp, color = Colors.dim, modifier = Modifier.padding(top = 8.dp))
        }
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp)) {
            itemsIndexed(episodes, key = { _, e -> e.id }) { _, ep -> EpisodeRow(ep) { play(ep) } }
        }
    }
    // Esc goes back, as the remote's Back does.
    DisposableEffect(Unit) {
        keys = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onBack(); true } else false }
        onDispose { keys = null }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().arcadeClickable(EpisodeSlab, onClick).background(Arcade.slab).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // No spoilers: an episode's picture stays blurred until it is watched (as on the TV).
        UrlImage(ep.thumb, Modifier.width(200.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).then(if (!ep.watched) Modifier.blur(16.dp) else Modifier)) {}
        Column(Modifier.padding(start = 20.dp).weight(1f)) {
            // The episode's own title only once watched (titles can spoil).
            Text(if (ep.watched && ep.episodeTitle.isNotBlank()) "${ep.title} · ${ep.episodeTitle}" else ep.title, fontSize = 20.sp, color = Colors.text, style = Arcade.label)
            val min = (ep.duration / 60).toInt().coerceAtLeast(1)
            Text((if (ep.easy > 0) "$min min · ${ep.scenes} scenes · ${ep.easy} easy today" else "$min min · ${ep.scenes} scenes") +
                if (ep.seen > 0) " · ${ep.seen} seen" else "", fontSize = 14.sp, color = Colors.dim, modifier = Modifier.padding(vertical = 6.dp))
            val resume = ep.resume
            if (resume != null && ep.duration > 0) ProgressBar((resume / ep.duration).toFloat(), Modifier.fillMaxWidth(0.6f))
        }
    }
}

private val images = ConcurrentHashMap<String, ImageBitmap>()

/** A server image (poster, thumb), fetched once per run; [fallback] while loading or when there is none. */
@Composable
fun UrlImage(path: String, modifier: Modifier, alignment: Alignment = Alignment.Center, fallback: @Composable () -> Unit) {
    // Keyed by the path, starting empty: a card reused for another episode (a tab switch) must not keep the old picture.
    val bitmap by produceState(images[path], path) {
        value = images[path] // null while a new one loads: the fallback, not the last episode's picture
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = URI(if (path.startsWith("http")) path else SERVER + path).toURL().readBytes()
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { images[path] = it }
            }.getOrNull()
        }
    }
    Box(modifier.background(Colors.surface)) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), alignment = alignment, contentScale = ContentScale.Crop) } ?: fallback()
    }
}
