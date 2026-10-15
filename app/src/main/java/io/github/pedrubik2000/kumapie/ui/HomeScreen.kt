package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.github.pedrubik2000.kumapie.data.resumeLine
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.ahead
import io.github.pedrubik2000.kumapie.data.easyPercent
import io.github.pedrubik2000.kumapie.data.levelLine
import io.github.pedrubik2000.kumapie.data.shortTitle

/** Home's rows, in this order (the phone's); reels are feed-only, so the TV never lists them. */
private val KINDS = listOf("anime" to "Anime", "shows" to "Shows", "movies" to "Movies", "youtube" to "YouTube", "visual_novels" to "Visual novels")

/**
 * Asks for the focus until the target is laid out: a lazy list composes its items only when it lays them out, so a
 * plain requestFocus() right after the first composition throws (item 13c).
 */
suspend fun FocusRequester.focusSoon() {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.isSuccess) return
    }
}

/**
 * The language switch (only with 2+ languages, saved per profile), then "Continue watching" and one row of posters per
 * media type. OK on a show opens its episodes; on "Continue watching", the player where it was left.
 */
@Composable
fun HomeScreen(api: Api, settings: Settings, profile: String, who: String, onShow: (Show) -> Unit,
               onPlay: (Show, Episode) -> Unit, onSettings: () -> Unit, onStats: () -> Unit, onProfiles: () -> Unit) {
    var shows by remember { mutableStateOf<List<Show>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { api.shows() }
            .onSuccess { all -> shows = all.filter { it.kind != "reels" }.map { s -> s.copy(episodes = s.episodes.filter { it.scenes > 0 }) }
                .filter { it.episodes.isNotEmpty() } } // 0 scenes: nothing to play scene by scene
            .onFailure { error = it.message ?: it.toString() }
    }
    val langs = Lang.ALL.map { it.code }.filter { c -> shows.orEmpty().any { it.lang == c } }
    var picked by remember(profile) { mutableStateOf(settings.prefs.getString("home_lang_$profile", null)) }
    val lang = picked?.takeIf { it in langs } ?: langs.firstOrNull()

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("kumapie", style = MaterialTheme.typography.headlineLarge, color = Colors.text)
            Spacer(Modifier.width(32.dp))
            if (langs.size > 1) langs.forEach { c ->
                Button(onClick = { picked = c; settings.prefs.edit().putString("home_lang_$profile", c).apply() },
                    colors = if (c == lang) ButtonDefaults.colors(containerColor = Colors.accent, contentColor = Colors.background)
                    else ButtonDefaults.colors(),
                    modifier = Modifier.padding(end = 12.dp)) { Text(Lang.of(c).displayName) }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onProfiles) { Text(who) } // back to "Who's watching?"
            Spacer(Modifier.width(16.dp))
            Button(onClick = onStats) { Text(tr("Stats")) }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onSettings) { Text(tr("Settings")) }
        }
        Spacer(Modifier.padding(8.dp))
        when {
            error != null -> ErrorBox(error!!, onRetry = { attempt++ }, onSettings = onSettings)
            shows == null -> Text(tr("Loading…"), color = Colors.dim)
            lang == null -> Text(tr("No shows on the server yet."), color = Colors.dim)
            else -> {
                LangCard(api, lang)
                Rows(shows!!.filter { it.lang == lang }, lang, onShow, onPlay)
            }
        }
    }
}

/** Hours in this language (TV + phones) and words known, above everything (user, 2026-10-15), as on the phone. */
@Composable
private fun LangCard(api: Api, lang: String) {
    var t by remember { mutableStateOf<io.github.pedrubik2000.kumapie.data.LangTotal?>(null) }
    LaunchedEffect(lang) { t = runCatching { api.stats().langs[lang] }.getOrNull() }
    val s = t ?: return
    Row(Modifier.padding(bottom = 16.dp).background(Colors.surface, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
        .padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        listOf(String.format(java.util.Locale.ROOT, "%.1f h", s.total / 3600) to tr("In total"), hoursMinutes(s.week) to tr("Last 7 days"),
            hoursMinutes(s.today) to tr("Today"), "${s.known}" to tr("Words known")).forEach { (v, l) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(v, fontSize = 24.sp, color = Colors.accent)
                Text(l, fontSize = 14.sp, color = Colors.dim)
            }
        }
    }
}

/** "Continue watching" (episodes left partway, as on the phone), then a row per media type. */
@Composable
private fun Rows(shows: List<Show>, lang: String, onShow: (Show) -> Unit, onPlay: (Show, Episode) -> Unit) {
    val going = io.github.pedrubik2000.kumapie.data.continueRow(shows)
    val other = shows.filter { s -> KINDS.none { it.first == s.kind } }
    val rows = KINDS.map { (k, label) -> tr(label) to shows.filter { it.kind == k } } + listOf(tr("Other") to other)
    val first = remember { FocusRequester() }
    LazyColumn {
        // As on the phone: the last episode as a big card (focused first, OK plays it), the other shows in progress
        // beside it as small cards (user, 2026-10-15: Home is for resuming fast). Novels are read: they count too.
        if (going.isNotEmpty()) item(key = "going") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom,
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 24.dp)) { // room for the focused card to grow 10%
                itemsIndexed(going, key = { _, p -> p.second.id }) { i, (s, e) ->
                    ResumeCard(s, e, if (i == 0) Modifier.focusRequester(first) else Modifier, width = if (i == 0) 360.dp else 220.dp) { onPlay(s, e) }
                }
            }
        }
        rows.forEachIndexed { r, (label, list) ->
            // Easiest first, finished last, as on the phone.
            val list = list.sortedWith(compareBy({ ahead(it).isEmpty() }, { -(easyPercent(it) ?: -1) }))
            if (list.isNotEmpty()) item(key = "kind-$r") {
                val top = going.isEmpty() && rows.take(r).all { it.second.isEmpty() }
                Shelf(label) {
                    itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                        Poster(s.poster, shortTitle(s.title), levelLine(s), null,
                            if (top && i == 0) Modifier.focusRequester(first) else Modifier) { onShow(s) }
                    }
                }
            }
        }
    }
    LaunchedEffect(lang, shows) { first.focusSoon() }
}

@Composable
private fun Shelf(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(title, color = Colors.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp))
        // The focused card grows by 10%: padding keeps it inside the screen and off its title.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

@Composable
private fun Poster(image: String, title: String, line: String, progress: Float?, modifier: Modifier, onClick: () -> Unit) {
    Column(Modifier.width(110.dp)) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(Modifier.fillMaxSize()) { // a Card lays out as a column: the box puts the image over the neutral poster
                NeutralPoster(title, Modifier.fillMaxSize()) // shows where there's no poster (never an episode frame)
                AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(title, color = Colors.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp))
        Text(line, color = Colors.dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (progress != null) ProgressBar(progress, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** An episode left partway: the show's poster cropped wide, then where it was left and how easy it is. */
@Composable
private fun ResumeCard(s: Show, e: Episode, modifier: Modifier, width: Dp, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize()) {
                NeutralPoster(s.title, Modifier.fillMaxSize(), showTitle = false)
                AsyncImage(model = s.poster, contentDescription = s.title, contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Colors.background.copy(alpha = 0.85f))))
                ProgressBar(((e.resume ?: 0.0) / e.duration).toFloat(), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp))
            }
        }
        Text(shortTitle(s.title), color = Colors.text, style = if (width > 300.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp))
        Text("${e.title} · ${resumeLine(e)}", color = Colors.dim, style = if (width > 300.dp) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    val retry = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("Can't reach the dojo server"), style = MaterialTheme.typography.headlineSmall, color = Colors.text)
            Text(message, color = Colors.dim, modifier = Modifier.padding(vertical = 12.dp))
            Text(tr("Is the PC on, and Tailscale connected on the TV?"), color = Colors.dim)
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onRetry, modifier = Modifier.focusRequester(retry)) { Text(tr("Try again")) }
                Button(onClick = onSettings) { Text(tr("Settings")) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
}
