package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Scene
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.i18n.tr

/** An i+1 scene: one new (red) word, everything else known or learning. */
private data class Easy(val show: Show, val episode: Episode, val scene: Scene, val newWord: String, val english: String)

/** The list as last made and shuffled, kept while the app runs (coming back from a scene keeps the order). */
private object Kept {
    var scenes: List<Easy>? = null
}

/**
 * Every i+1 scene in every show, in random order, to mine its new word from: tap one to play it (the player opens
 * on that scene; its word card has "Add to Anki"). Levels come from the device's own known words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IPlusOneScreen(library: Library, shows: List<Show>, onPlay: (Show, Episode, Double) -> Unit, onBack: () -> Unit) {
    var scenes by remember { mutableStateOf(Kept.scenes) }
    var loading by remember { mutableStateOf("") }
    var shuffle by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        if (scenes != null) return@LaunchedEffect
        val found = ArrayList<Easy>()
        val episodes = shows.flatMap { s -> s.episodes.map { s to it } }
        episodes.forEachIndexed { i, (show, ep) ->
            loading = tr("Reading episodes %1\$d of %2\$d…", i + 1, episodes.size)
            val detail = runCatching { library.episode(ep.id) }.getOrNull() ?: return@forEachIndexed
            for (sc in detail.scenes) {
                if (!sc.target || sc.level != 1) continue
                val red = sc.cues.flatMap { it.segments }.firstOrNull { s -> s.word != null && detail.words[s.word]?.status == "u" }
                    ?: continue
                found += Easy(show, ep, sc, red.word!!, sc.english.joinToString(" ") { it.text })
            }
        }
        scenes = found.shuffled().also { Kept.scenes = it }
        loading = if (found.isEmpty()) tr("No i+1 scenes (episodes that can't be reached offline are skipped).") else ""
    }
    LaunchedEffect(shuffle) { if (shuffle > 0) scenes = scenes?.shuffled()?.also { Kept.scenes = it } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("i+1 scenes") + (scenes?.let { " · ${it.size}" } ?: "")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
            actions = { IconButton(onClick = { shuffle++ }) { Icon(Icons.Default.Shuffle, tr("Shuffle")) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading.isNotEmpty()) Text(loading, color = Colors.dim, modifier = Modifier.padding(20.dp))
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                items(scenes.orEmpty(), key = { it.scene.id }) { e ->
                    Column(Modifier.fillMaxWidth().background(Colors.surface, RoundedCornerShape(12.dp))
                        .clickable { onPlay(e.show, e.episode, e.scene.start) }.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(buildAnnotatedString {
                            e.scene.cues.forEachIndexed { i, cue ->
                                if (i > 0) append(" ")
                                cue.segments.forEach { s ->
                                    if (s.word == e.newWord) withStyle(SpanStyle(color = Colors.unknown)) { append(s.text) } else append(s.text)
                                }
                            }
                        }, color = Colors.text, fontSize = 18.sp)
                        Text(e.english, color = Colors.dim, fontSize = 14.sp)
                        Text("${e.show.title} · ${e.episode.title}", color = Colors.accent, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
