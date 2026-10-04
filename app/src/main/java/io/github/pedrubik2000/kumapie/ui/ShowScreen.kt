package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
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
import io.github.pedrubik2000.kumapie.data.Show

/** A show's poster and its episodes. Focus starts on the episode watched last (or the first one). */
@Composable
fun ShowScreen(api: Api, initial: Show, onEpisode: (Show, Episode) -> Unit) {
    var show by remember { mutableStateOf(initial) }
    LaunchedEffect(initial.id) { // fresh progress after coming back from the player
        runCatching { api.shows() }.getOrNull()?.firstOrNull { it.id == initial.id }?.let { show = it }
    }
    val start = show.episodes.indexOfLast { it.resume != null }.coerceAtLeast(0)
    val focus = remember { FocusRequester() }

    Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(260.dp)) {
            AsyncImage(model = show.poster, contentDescription = show.title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)))
            Text(show.title, fontSize = 24.sp, color = Colors.text, modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp)) { // room for the focused card's 10%
            itemsIndexed(show.episodes, key = { _, e -> e.id }) { i, ep ->
                EpisodeCard(ep, onClick = { onEpisode(show, ep) },
                    modifier = if (i == start) Modifier.focusRequester(focus) else Modifier)
            }
        }
    }
    LaunchedEffect(show.id) { runCatching { focus.requestFocus() } }
}

@Composable
private fun EpisodeCard(ep: Episode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = ep.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(200.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.padding(start = 20.dp).weight(1f)) {
                Text(ep.title, fontSize = 20.sp, color = Colors.text)
                Text("${minutes(ep.duration)} min · ${ep.scenes} scenes · ${ep.easy} easy today" +
                    if (ep.seen > 0) " · ${ep.seen} seen" else "", fontSize = 14.sp, color = Colors.dim,
                    modifier = Modifier.padding(vertical = 6.dp))
                if (ep.resume != null && ep.duration > 0) {
                    ProgressBar((ep.resume / ep.duration).toFloat(), Modifier.fillMaxWidth(0.6f))
                }
            }
        }
    }
}

private fun minutes(seconds: Double) = (seconds / 60).toInt().coerceAtLeast(1)
