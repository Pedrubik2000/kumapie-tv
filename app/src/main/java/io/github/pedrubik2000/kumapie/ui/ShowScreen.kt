package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.i18n.tr
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import androidx.compose.ui.draw.blur
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Button
import kotlinx.coroutines.launch

/**
 * A show's poster and its episodes. Focus starts on the episode played most recently (or the first one). Holding OK on
 * an episode: mark it, or its whole season, watched or not watched (every device).
 */
@Composable
fun ShowScreen(api: Api, settings: Settings, initial: Show, onEpisode: (Show, Episode) -> Unit) {
    var show by remember { mutableStateOf(initial) }
    var menu by remember { mutableStateOf<Episode?>(null) }
    var marked by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(initial.id, marked) { // fresh progress after coming back from the player, or after a mark
        runCatching { api.shows() }.getOrNull()?.firstOrNull { it.id == initial.id }?.let { show = it }
    }
    val episodes = show.episodes.filter { it.scenes > 0 } // nothing to play scene by scene (a reel without speech)
    val start = episodes.indices.filter { episodes[it].resume != null }.maxByOrNull { episodes[it].resumedAt } ?: 0
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()

    Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(300.dp)) {
            Box(Modifier.fillMaxWidth(if (show.description.isBlank()) 0.87f else 0.55f).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
                NeutralPoster(show.title, Modifier.fillMaxSize(), fontSize = 22.sp)
                AsyncImage(model = show.poster, contentDescription = show.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
            }
            Text(show.title, fontSize = 22.sp, color = Colors.text, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            if (show.description.isNotBlank()) Text(show.description, fontSize = 14.sp, color = Colors.dim, maxLines = 8,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp)) { // room for the focused card's 10%
            itemsIndexed(episodes, key = { _, e -> e.id }) { i, ep ->
                EpisodeCard(ep, blur = settings.blurUnwatched && !ep.watched, onClick = { onEpisode(show, ep) },
                    onLongClick = { menu = ep }, modifier = if (i == start) Modifier.focusRequester(focus) else Modifier)
            }
        }
    }
    LaunchedEffect(show.id, start) { // the episode must be laid out before it can take the focus
        if (episodes.isNotEmpty()) { list.scrollToItem(start); focus.focusSoon() }
    }
    menu?.let { ep ->
        val season = episodes.filter { it.season == ep.season }
        MarkMenu(ep, season.size, onClose = { menu = null }) { whole, watched ->
            menu = null
            scope.launch {
                runCatching { api.mark((if (whole) season else listOf(ep)).map { it.id }, watched) }
                marked++
            }
        }
    }
}

/** Mark an episode, or every episode of its season ([count]), watched or not watched. Back closes it. */
@Composable
private fun MarkMenu(ep: Episode, count: Int, onClose: () -> Unit, onMark: (whole: Boolean, watched: Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    val season = ep.season?.let { tr("Season %d", it) } ?: tr("All episodes")
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).onBackKey(onBack = onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).background(Colors.surface, RoundedCornerShape(16.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ep.title, fontSize = 24.sp, color = Colors.text)
            Button(onClick = { onMark(false, !ep.watched) }, modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                Text(if (ep.watched) tr("Mark as not watched") else tr("Mark as watched"))
            }
            if (count > 1) {
                Button(onClick = { onMark(true, true) }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("%1\$s: mark all %2\$d as watched", season, count))
                }
                Button(onClick = { onMark(true, false) }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("%1\$s: mark all %2\$d as not watched", season, count))
                }
            }
        }
    }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun EpisodeCard(ep: Episode, blur: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = ep.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                // No spoilers: an episode's picture stays blurred until it is watched (as on the phone).
                modifier = Modifier.width(200.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                    .then(if (blur) Modifier.blur(16.dp) else Modifier))
            Column(Modifier.padding(start = 20.dp).weight(1f)) {
                // The episode's own title only once watched (titles can spoil).
                Text(if (ep.watched && ep.episodeTitle.isNotBlank()) "${ep.title} · ${ep.episodeTitle}" else ep.title,
                    fontSize = 20.sp, color = Colors.text)
                Text((if (ep.easy > 0) tr("%1\$d min · %2\$d scenes · %3\$d easy today", minutes(ep.duration), ep.scenes, ep.easy)
                    else tr("%1\$d min · %2\$d scenes", minutes(ep.duration), ep.scenes)) +
                    if (ep.seen > 0) tr(" · %d seen", ep.seen) else "", fontSize = 14.sp, color = Colors.dim,
                    modifier = Modifier.padding(vertical = 6.dp))
                val resume = ep.resume
                if (resume != null && ep.duration > 0) {
                    ProgressBar((resume / ep.duration).toFloat(), Modifier.fillMaxWidth(0.6f))
                }
            }
        }
    }
}

private fun minutes(seconds: Double) = (seconds / 60).toInt().coerceAtLeast(1)
