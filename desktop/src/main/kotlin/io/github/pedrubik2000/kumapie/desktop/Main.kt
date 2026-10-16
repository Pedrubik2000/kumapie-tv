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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

    Column(Modifier.fillMaxSize().background(Colors.background)) {
        if (screen !is Screen.Playing) TopBar(ankiUp, lang, shows) { l ->
            if (l != null) { lang = l; settings.prefs.edit().putString("desktop_lang", l).apply() }
            screen = Screen.Home
        }
        val list = shows
        when {
            error != null -> Text(error!!, color = Colors.unknown, modifier = Modifier.padding(24.dp))
            api == null || list == null -> Text("…", color = Colors.dim, modifier = Modifier.padding(24.dp))
            else -> when (val s = screen) {
                Screen.Home -> Home(list, lang) { sh, ep -> screen = if (ep == null) Screen.ShowPage(sh) else Screen.Playing(sh, ep) }
                is Screen.ShowPage -> ShowPage(s.show, onBack = { screen = Screen.Home }) { screen = Screen.Playing(s.show, it) }
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

/** kumapie, the language tabs (languages with shows), and whether kuma3 answers. */
@Composable
private fun TopBar(ankiUp: Boolean, lang: String, shows: List<Show>?, onHome: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("kumapie", fontSize = 20.sp, modifier = Modifier.clickable { onHome(null) })
        Spacer(Modifier.width(12.dp))
        for (l in shows.orEmpty().map { it.lang }.distinct()) {
            Chip(LANGS[l] ?: l, on = l == lang) { onHome(l) }
        }
        Spacer(Modifier.weight(1f))
        Text(if (ankiUp) "kuma3 ● connected" else "kuma3 closed: colours from your phone, no mining",
            color = if (ankiUp) Colors.due else Colors.dim, fontSize = 14.sp)
    }
}

@Composable
fun Chip(label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(label, color = when { !enabled -> Colors.dim.copy(alpha = 0.5f); on -> Colors.accent; else -> Colors.text }, fontSize = 15.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Colors.surface)
            .border(1.dp, if (on) Colors.accent else Color.Transparent, RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun Home(shows: List<Show>, pref: String, open: (Show, Episode?) -> Unit) {
    val langs = shows.map { it.lang }.distinct()
    val lang = if (pref in langs) pref else langs.firstOrNull()
    val mine = shows.filter { it.lang == lang }
    val going = mine.flatMap { sh -> sh.episodes.filter { it.resumedAt > 0 }.map { sh to it } }
        .sortedByDescending { it.second.resumedAt }.take(8)
    LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (going.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Heading("Continue watching") }
            items(going) { (sh, ep) -> EpisodeCard(sh, ep) { open(sh, ep) } }
        }
        for ((kind, group) in mine.groupBy { it.kind }) {
            item(span = { GridItemSpan(maxLineSpan) }) { Heading(kind.replaceFirstChar { it.uppercase() }) }
            items(group) { sh ->
                Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Colors.surface).clickable { open(sh, null) }) {
                    UrlImage(sh.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f)) { NeutralPoster(sh.title, Modifier.fillMaxSize()) }
                    Text(sh.title, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    Text("${sh.episodes.size} ep · ${sh.episodes.sumOf { it.easy }} easy scenes", color = Colors.dim, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))

@Composable
private fun EpisodeCard(sh: Show, ep: Episode, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Colors.surface).clickable(onClick = onClick)) {
        UrlImage(ep.thumb, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {}
        SeenBar(ep)
        Text(sh.title, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp))
        Text(ep.title, color = Colors.dim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun SeenBar(ep: Episode) {
    Box(Modifier.fillMaxWidth().height(3.dp).background(Colors.background)) {
        Box(Modifier.fillMaxWidth(if (ep.scenes > 0) ep.seen.toFloat() / ep.scenes else 0f).height(3.dp).background(Colors.accent))
    }
}

@Composable
private fun ShowPage(sh: Show, onBack: () -> Unit, play: (Episode) -> Unit) {
    val last = sh.episodes.filter { it.resumedAt > 0 }.maxByOrNull { it.resumedAt }
    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(240.dp)) {
            Text("← Back", color = Colors.dim, modifier = Modifier.clickable(onClick = onBack).padding(bottom = 12.dp))
            UrlImage(sh.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) { NeutralPoster(sh.title, Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(sh.title + (sh.year?.let { " ($it)" } ?: ""), fontSize = 24.sp)
            if (sh.description.isNotBlank()) Text(sh.description, color = Colors.dim, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            for (ep in sh.episodes) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (ep == last) Colors.surface else Color.Transparent)
                    .clickable { play(ep) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UrlImage(ep.thumb, Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp))) {}
                    Column(Modifier.weight(1f)) {
                        // The episode's own title only once it is watched (spoilers).
                        Text(ep.title + if (ep.watched && ep.episodeTitle.isNotBlank()) " · ${ep.episodeTitle}" else "", fontSize = 15.sp)
                        Spacer(Modifier.height(6.dp))
                        SeenBar(ep)
                    }
                    Text("${ep.easy} easy", color = Colors.dim, fontSize = 13.sp)
                }
            }
        }
    }
}

private val images = ConcurrentHashMap<String, ImageBitmap>()

/** A server image (poster, thumb), fetched once per run; [fallback] while loading or when there is none. */
@Composable
fun UrlImage(path: String, modifier: Modifier, fallback: @Composable () -> Unit) {
    val bitmap by produceState(images[path], path) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = URI(if (path.startsWith("http")) path else SERVER + path).toURL().readBytes()
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { images[path] = it }
            }.getOrNull()
        }
    }
    Box(modifier.background(Colors.surface)) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: fallback()
    }
}
