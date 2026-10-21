package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.delay

/**
 * A kumapie://prepare link (the kumapie add-on's stream in Stremio): the PC downloads the title and makes it a library
 * episode (kumapie-server stremio.py); asked every 3 s until it's ready, then [onPlay] with the episode from the show list.
 */
@Composable
fun PrepareScreen(library: Library, query: Map<String, String>, onPlay: (Show, Episode) -> Unit, onBack: () -> Unit) {
    var step by remember { mutableStateOf(tr("Asking the PC…")) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching {
            val api = library.api
            while (true) {
                val r = api.prepare(query)
                when (r.optString("state")) {
                    "ready" -> {
                        val id = r.getString("id")
                        val found = library.shows().first.firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.id == id }?.let { s to it } }
                            ?: error(tr("The PC made it, but it isn't in the show list yet"))
                        onPlay(found.first, found.second)
                        return@runCatching
                    }
                    "failed" -> error(tr("The PC couldn't make it: %1\$s", r.optString("error")))
                    else -> step = r.optString("step").ifEmpty { tr("waiting for the job before it") }
                }
                delay(3_000)
            }
        }.onFailure { error = it.message ?: it.toString() }
    }
    Column(
        Modifier.fillMaxSize().background(Colors.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Text(listOfNotNull(query["id"], query["lang"]).joinToString("  "), color = Colors.text, fontSize = 18.sp, textAlign = TextAlign.Center)
        if (error == null) CircularProgressIndicator(color = Colors.accent)
        Text(error ?: step, color = if (error == null) Colors.dim else Colors.unknown, fontSize = 16.sp, textAlign = TextAlign.Center)
        Button(onClick = onBack) { Text(if (error == null) tr("Not now") else tr("Back")) }
    }
}
