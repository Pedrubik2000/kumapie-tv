package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.mobile.german.Improve
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screen was showing, kept while a scene plays (back from the player lands here again). */
private object ImproveKept {
    var cards: List<Improve.Struggling>? = null
    var card: Improve.Struggling? = null
    var candidates: List<Improve.Candidate>? = null
    var chosen: Improve.Candidate? = null
    var sentence = ""
    var bilingual = ""
    var monolingual = ""
    var recording = false
}

/**
 * Cards that don't stick (home: the bandage icon): pick one, then another scene with its word (▶ to watch it first),
 * edit the sentence and the definition, take the word's recording; Save changes the note and flags it orange.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImproveScreen(library: Library, shows: List<Show>, onPlay: (Show, Episode, Double) -> Unit, onBack: () -> Unit) {
    val improve = remember { Improve(library) }
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf(ImproveKept.cards) }
    var card by remember { mutableStateOf(ImproveKept.card) }
    var candidates by remember { mutableStateOf(ImproveKept.candidates) }
    var chosen by remember { mutableStateOf(ImproveKept.chosen) }
    var sentence by remember { mutableStateOf(ImproveKept.sentence) }
    var bilingual by remember { mutableStateOf(ImproveKept.bilingual) }
    var monolingual by remember { mutableStateOf(ImproveKept.monolingual) }
    var recording by remember { mutableStateOf(ImproveKept.recording) }
    var said by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun keep() {
        ImproveKept.cards = cards; ImproveKept.card = card; ImproveKept.candidates = candidates; ImproveKept.chosen = chosen
        ImproveKept.sentence = sentence; ImproveKept.bilingual = bilingual; ImproveKept.monolingual = monolingual; ImproveKept.recording = recording
    }
    fun leave() { ImproveKept.cards = null; ImproveKept.card = null; onBack() }

    LaunchedEffect(Unit) {
        if (cards != null) return@LaunchedEffect
        said = "Reading Anki…"
        runCatching { withContext(Dispatchers.IO) { improve.struggling() } }
            .onSuccess { cards = it; said = if (it.isEmpty()) "No cards are struggling right now." else "" }
            .onFailure { said = it.message ?: it.toString() }
    }
    LaunchedEffect(card) {
        val c = card ?: return@LaunchedEffect
        if (candidates != null) return@LaunchedEffect
        sentence = c.sentence; bilingual = c.bilingual; monolingual = c.monolingual; chosen = null
        candidates = withContext(Dispatchers.IO) { runCatching { improve.candidates(c, shows) { said = it } }.getOrDefault(emptyList()) }
        said = ""
    }
    val hasRecording by androidx.compose.runtime.produceState(false, card) {
        value = card?.let { s -> withContext(Dispatchers.IO) { runCatching { library.dictionary.recording(s.word) != null }.getOrDefault(false) } } ?: false
    }
    BackHandler { if (card != null) { card = null; candidates = null; said = "" } else leave() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(card?.let { "Improve: ${it.word}" } ?: "Cards that don't stick") },
            navigationIcon = { IconButton(onClick = { if (card != null) { card = null; candidates = null; said = "" } else leave() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        val c = card
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (said.isNotEmpty()) item { Text(said, color = Colors.dim) }
            if (c == null) {
                items(cards.orEmpty(), key = { it.note.id }) { s ->
                    Column(Modifier.fillMaxWidth().background(Colors.surface, RoundedCornerShape(12.dp))
                        .clickable { card = s; candidates = null }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s.word, fontSize = 18.sp, color = Colors.accent)
                        Text(s.sentence, fontSize = 15.sp)
                        Text("${s.lapses} lapses" + (s.stability?.let { " · stability %.1f days".format(it) } ?: ""), fontSize = 12.sp, color = Colors.dim)
                    }
                }
                return@LazyColumn
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Now: ${c.sentence}", fontSize = 15.sp, color = Colors.dim)
                    OutlinedTextField(sentence, { sentence = it }, label = { Text("Sentence") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(bilingual, { bilingual = it }, label = { Text("Definition (bilingual)") },
                        modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
                    OutlinedTextField(monolingual, { monolingual = it }, label = { Text("Definition (monolingual)") },
                        modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
                    if (hasRecording) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use a person's recording of \"${c.word}\" (Wiktionary)", modifier = Modifier.weight(1f))
                        Switch(recording, { recording = it })
                    }
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            said = runCatching { improve.apply(c, chosen, sentence, bilingual, monolingual, recording) { said = it } }.getOrElse { it.message ?: it.toString() }
                            busy = false
                            cards = cards?.filter { it.note.id != c.note.id }
                            card = null; candidates = null
                        }
                    }) { Text("Save and flag orange") }
                    Text(if (candidates == null) "Looking for scenes with \"${c.key ?: c.word}\"…"
                        else "${candidates?.size ?: 0} other scenes with \"${c.key ?: c.word}\" (easiest first). Tap one to use it, ▶ to watch it.",
                        fontSize = 13.sp, color = Colors.dim)
                }
            }
            items(candidates.orEmpty(), key = { it.scene.id }) { k ->
                val on = chosen === k
                Row(Modifier.fillMaxWidth().background(Colors.surface, RoundedCornerShape(12.dp))
                    .border(if (on) 2.dp else 0.dp, if (on) Colors.accent else Colors.surface, RoundedCornerShape(12.dp))
                    .clickable { chosen = k; sentence = k.sentence }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(k.sentence, fontSize = 16.sp)
                        Text(k.english, fontSize = 13.sp, color = Colors.dim)
                        Text("${k.show.title} · ${k.episode.title}" + " · i+${k.unknown}", fontSize = 12.sp, color = Colors.accent)
                    }
                    IconButton(onClick = { keep(); onPlay(k.show, k.episode, k.scene.start) }) { Icon(Icons.Default.PlayArrow, "Watch the scene") }
                }
            }
        }
    }
}
