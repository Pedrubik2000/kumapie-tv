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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Show

/** The shows as a row of posters. OK opens a show's episodes. */
@Composable
fun HomeScreen(api: Api, who: String, onShow: (Show) -> Unit, onSettings: () -> Unit, onStats: () -> Unit, onProfiles: () -> Unit) {
    var shows by remember { mutableStateOf<List<Show>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { api.shows() }.onSuccess { shows = it }.onFailure { error = it.message ?: it.toString() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("kumapie", fontSize = 32.sp, color = Colors.text)
            Spacer(Modifier.weight(1f))
            Button(onClick = onProfiles) { Text(who) } // back to "Who's watching?"
            Spacer(Modifier.width(16.dp))
            Button(onClick = onStats) { Text(tr("Stats")) }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onSettings) { Text(tr("Settings")) }
        }
        Spacer(Modifier.padding(12.dp))
        when {
            error != null -> ErrorBox(error!!, onRetry = { attempt++ }, onSettings = onSettings)
            shows == null -> Text(tr("Loading…"), color = Colors.dim)
            shows!!.isEmpty() -> Text(tr("No shows on the server yet."), color = Colors.dim)
            else -> ShowRow(shows!!, onShow)
        }
    }
}

@Composable
private fun ShowRow(shows: List<Show>, onShow: (Show) -> Unit) {
    val first = remember { FocusRequester() }
    // The focused card grows by 10%: padding keeps it inside the screen and off its title.
    LazyRow(horizontalArrangement = Arrangement.spacedBy(32.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)) {
        itemsIndexed(shows, key = { _, s -> s.id }) { i, show ->
            Column(Modifier.width(200.dp)) {
                Card(
                    onClick = { onShow(show) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).let { if (i == 0) it.focusRequester(first) else it },
                ) {
                    AsyncImage(model = show.poster, contentDescription = show.title,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(show.title, color = Colors.text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 24.dp))
                val eps = show.episodes.size
                Text(if (eps == 1) show.kind.replaceFirstChar { it.uppercase() } else tr("%d episodes", eps),
                    color = Colors.dim, fontSize = 13.sp)
            }
        }
    }
    LaunchedEffect(shows) { runCatching { first.requestFocus() } }
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
