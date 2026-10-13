package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.i18n.tr
import androidx.compose.foundation.layout.Arrangement
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
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show

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
            Text("kumapie", fontSize = 32.sp, color = Colors.text)
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
            else -> Rows(shows!!.filter { it.lang == lang }, lang, onShow, onPlay)
        }
    }
}

/** "Continue watching" (episodes left partway, as on the phone), then a row per media type. */
@Composable
private fun Rows(shows: List<Show>, lang: String, onShow: (Show) -> Unit, onPlay: (Show, Episode) -> Unit) {
    val going = shows.flatMap { s -> s.episodes.map { s to it } }.filter { (_, e) ->
        val at = e.resume ?: 0.0
        !e.watched && e.duration > 0 && at > 20 && at < e.duration * 0.95
    }.sortedByDescending { it.second.resumedAt }
    val other = shows.filter { s -> KINDS.none { it.first == s.kind } }
    val rows = KINDS.map { (k, label) -> tr(label) to shows.filter { it.kind == k } } + listOf(tr("Other") to other)
    val first = remember { FocusRequester() }
    LazyColumn {
        if (going.isNotEmpty()) item(key = "going") {
            Shelf(tr("Continue watching")) {
                itemsIndexed(going, key = { _, p -> p.second.id }) { i, (s, e) ->
                    Poster(s.poster, s.title, e.title, ((e.resume ?: 0.0) / e.duration).toFloat(),
                        if (i == 0) Modifier.focusRequester(first) else Modifier) { onPlay(s, e) }
                }
            }
        }
        rows.forEachIndexed { r, (label, list) ->
            if (list.isNotEmpty()) item(key = "kind-$r") {
                val top = going.isEmpty() && rows.take(r).all { it.second.isEmpty() }
                Shelf(label) {
                    itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                        val eps = s.episodes.size
                        Poster(s.poster, s.title, if (eps == 1) tr(label) else tr("%d episodes", eps), null,
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
        Text(title, color = Colors.text, fontSize = 20.sp, modifier = Modifier.padding(start = 16.dp))
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
        Text(title, color = Colors.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp))
        Text(line, color = Colors.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (progress != null) ProgressBar(progress, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    val retry = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("Can't reach the dojo server"), fontSize = 24.sp, color = Colors.text)
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
