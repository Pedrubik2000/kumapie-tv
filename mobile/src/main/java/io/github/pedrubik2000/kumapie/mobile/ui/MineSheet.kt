package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.mobile.german.Miner
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch

/** One meaning to choose for a word card. */
private data class Choice(val label: String, val gloss: String, val example: Pair<String, String>?)

/**
 * "Add to Anki": the line as a sentence card, or the picked word as a word card with the meaning chosen from
 * kumapie's own meaning and every dictionary sense. Adds to kuma3 Anki's Deutsch::Mined ([Miner]).
 */
@Composable
fun MineSheet(library: Library, episode: EpisodeDetail, ctl: SceneController, picker: WordPicker, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scene = ctl.scene
    val line = picker.line
    val cue = scene.cues.getOrNull(line) ?: return
    val segment = picker.selected
    val key = segment?.word
    val word = key?.let { ctl.words[it] }
    val def = key?.let { scene.def(line, it) }
    val lemma = def?.lemma?.takeIf { it.isNotBlank() } ?: word?.lemma?.takeIf { it.isNotBlank() }

    var wordCard by remember { mutableStateOf(segment?.word != null) }
    var choices by remember { mutableStateOf<List<Choice>>(emptyList()) }
    var chosen by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(segment?.text, key) {
        if (segment == null || key == null) return@LaunchedEffect
        val own = (def?.english?.takeIf { it.isNotBlank() } ?: (scene.meanings[key] ?: word?.meaning)?.substringAfter(" = "))
            ?.takeIf { it.isNotBlank() }
        val list = mutableListOf<Choice>()
        own?.let { list += Choice("kumapie: $it", it, null) }
        library.dictionary.lookup(segment.text, key, lemma).forEach { e ->
            e.senses.forEach { s -> list += Choice("${e.word} (${e.pos}): ${s.gloss}", s.gloss, s.examples.firstOrNull()) }
        }
        choices = list
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()
        .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add to Anki · ${Miner.DECK}", color = Colors.accent, fontSize = 18.sp)
        Text(cue.text, color = Colors.text, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !wordCard, onClick = { wordCard = false }, label = { Text("Sentence card") })
            if (segment?.word != null) FilterChip(selected = wordCard, onClick = { wordCard = true },
                label = { Text("Word card: ${segment.text}") })
        }
        if (wordCard) {
            if (choices.isEmpty()) Text("Looking up meanings…", color = Colors.dim, fontSize = 14.sp)
            choices.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth().clickable { chosen = i }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen == i, onClick = { chosen = i })
                    Column {
                        Text(c.label, color = Colors.text, fontSize = 15.sp)
                        c.example?.let { Text("${it.first} — ${it.second}", color = Colors.dim, fontSize = 13.sp) }
                    }
                }
            }
        } else {
            Text("The line, a clip of the scene and its English.", color = Colors.dim, fontSize = 14.sp)
        }
        Button(enabled = !busy && !done && (!wordCard || choices.isNotEmpty()), onClick = {
            busy = true
            val pick = choices.getOrNull(chosen)
            val request = Miner.Request(episode, scene, line, if (wordCard && segment != null && pick != null) {
                Miner.Word(segment.text, key, lemma, pick.gloss, pick.example, def)
            } else null)
            scope.launch {
                runCatching { library.miner.mine(request) { status = it } }
                    .onSuccess { status = it; done = true }
                    .onFailure { status = it.message ?: it.toString() }
                busy = false
            }
        }) { Text(if (done) "Added" else "Add") }
        if (status.isNotEmpty()) Text(status, color = if (done) Colors.levelZero else Colors.dim, fontSize = 14.sp)
        if (done) LaunchedEffect(Unit) { kotlinx.coroutines.delay(1200); onDone() }
    }
}
