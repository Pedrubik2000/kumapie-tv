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

/** A show's poster and its episodes. Focus starts on the episode watched last (or the first one). */
@Composable
fun ShowScreen(api: Api, settings: Settings, initial: Show, onEpisode: (Show, Episode) -> Unit) {
    var show by remember { mutableStateOf(initial) }
    LaunchedEffect(initial.id) { // fresh progress after coming back from the player
        runCatching { api.shows() }.getOrNull()?.firstOrNull { it.id == initial.id }?.let { show = it }
    }
    val episodes = show.episodes.filter { it.scenes > 0 } // nothing to play scene by scene (a reel without speech)
    val start = episodes.indexOfLast { it.resume != null }.coerceAtLeast(0)
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()

    Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(260.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
                NeutralPoster(show.title, Modifier.fillMaxSize(), fontSize = 22.sp)
                AsyncImage(model = show.poster, contentDescription = show.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
            }
            Text(show.title, fontSize = 24.sp, color = Colors.text, modifier = Modifier.padding(top = 16.dp))
            if (show.description.isNotBlank()) Text(show.description, fontSize = 14.sp, color = Colors.dim, maxLines = 9,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp)) { // room for the focused card's 10%
            itemsIndexed(episodes, key = { _, e -> e.id }) { i, ep ->
                EpisodeCard(ep, blur = settings.blurUnwatched && !ep.watched, onClick = { onEpisode(show, ep) },
                    modifier = if (i == start) Modifier.focusRequester(focus) else Modifier)
            }
        }
    }
    LaunchedEffect(show.id, start) { // the episode must be laid out before it can take the focus
        if (episodes.isNotEmpty()) { list.scrollToItem(start); focus.focusSoon() }
    }
}

@Composable
private fun EpisodeCard(ep: Episode, blur: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
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
