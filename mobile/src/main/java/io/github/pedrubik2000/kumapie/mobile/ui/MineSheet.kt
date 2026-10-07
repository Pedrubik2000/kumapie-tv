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
import io.github.pedrubik2000.kumapie.mobile.lang.Miner
import io.github.pedrubik2000.kumapie.mobile.lang.SensePick
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch
import io.github.pedrubik2000.kumapie.i18n.tr

/** One meaning to choose for a word card. */
private data class Choice(val label: String, val gloss: String, val example: Pair<String, String>?)

/**
 * "Add to Anki": the line as a sentence card, or the picked word as a word card with the meaning chosen from
 * kumapie's own meaning and every dictionary sense. Without kumapie's own meaning, the sense that fits the line's
 * English ([SensePick]) starts chosen. Adds to kuma3 Anki's Deutsch::Mined ([Miner]).
 */
@Composable
fun MineSheet(library: Library, episode: EpisodeDetail, ctl: SceneController, picker: WordPicker, onDone: () -> Unit) {
    if (ctl.lang == io.github.pedrubik2000.kumapie.data.Lang.JAPANESE) return JapaneseMineSheet(library, episode, ctl, picker, onDone)
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
        val entries = library.dictionary.lookup(segment.text, key, lemma, lang = ctl.lang)
        val fits = SensePick.best(entries, SensePick.english(scene, line))
        var fitting = -1
        entries.forEachIndexed { i, e ->
            e.senses.forEachIndexed { j, s ->
                if (fits == i to j) fitting = list.size
                list += Choice("${e.word} (${e.pos}): ${s.gloss}" + if (fits == i to j) tr("  · fits this line") else "", s.gloss,
                    s.examples.firstOrNull())
            }
        }
        if (own == null && fitting >= 0) chosen = fitting
        choices = list
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()
        .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Add to Anki · %s", ctl.lang.deck), color = Colors.accent, fontSize = 18.sp)
        Text(Miner.sentence(scene, line).first, color = Colors.text, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !wordCard, onClick = { wordCard = false }, label = { Text(tr("Sentence card")) })
            if (segment?.word != null) FilterChip(selected = wordCard, onClick = { wordCard = true },
                label = { Text(tr("Word card: %s", segment.text)) })
        }
        if (wordCard) {
            if (choices.isEmpty()) Text(tr("Looking up meanings…"), color = Colors.dim, fontSize = 14.sp)
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
            Text(tr("The sentence, a video clip of the whole scene and the English."), color = Colors.dim, fontSize = 14.sp)
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
        }) { Text(if (done) tr("Added") else tr("Add")) }
        if (status.isNotEmpty()) Text(status, color = if (done) Colors.levelZero else Colors.dim, fontSize = 14.sp)
        if (done) LaunchedEffect(Unit) { kotlinx.coroutines.delay(1200); onDone() }
    }
}

/**
 * "Add to Anki" for Japanese episodes ([Miner] writes the 🐻 Japanese card): the line as a sentence card, or the tapped
 * word with a meaning picked from the bilingual dictionaries (the first one chosen) and a monolingual definition picked
 * from the monolingual ones (国語 dictionaries before encyclopedias; or none). Into Japanese::Mined.
 */
@Composable
private fun JapaneseMineSheet(library: Library, episode: EpisodeDetail, ctl: SceneController, picker: WordPicker, onDone: () -> Unit) {
    val ja = io.github.pedrubik2000.kumapie.data.Lang.JAPANESE
    val scope = rememberCoroutineScope()
    val scene = ctl.scene
    val line = picker.line
    val cue = scene.cues.getOrNull(line) ?: return
    val segment = picker.selected
    val offset = cue.segments.take(picker.seg).sumOf { it.text.length }
    var wordCard by remember { mutableStateOf(segment?.word != null) }
    var headword by remember { mutableStateOf<io.github.pedrubik2000.kumapie.mobile.lang.JapaneseLookup.Headword?>(null) }
    var choices by remember { mutableStateOf<List<Choice>>(emptyList()) }
    var monos by remember { mutableStateOf<List<Choice>>(emptyList()) }
    var monoChosen by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableIntStateOf(0) }
    var looked by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(segment?.text) {
        if (segment?.word == null) return@LaunchedEffect
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                io.github.pedrubik2000.kumapie.mobile.lang.JapaneseLookup(library.knownJa.japanese, library.yomitan)
                    .lookup(cue.text, offset, segment.text.length)
            }.getOrDefault(emptyList())
        }.firstOrNull()
        headword = found
        val glossaries = found?.terms?.flatMap { it.glossaries }.orEmpty()
        fun group(dict: String) = library.yomitan.groupOf(ja, dict)
        fun meanings(g: io.github.pedrubik2000.kumapie.mobile.lang.YomitanDictionaries.Glossary) =
            g.senses.flatMap { io.github.pedrubik2000.kumapie.mobile.lang.JapaneseLookup.meanings(it) }.distinct()
        val isMono = { g: io.github.pedrubik2000.kumapie.mobile.lang.YomitanDictionaries.Glossary -> group(g.dict).contains("mono", ignoreCase = true) }
        val isBilingual = { g: io.github.pedrubik2000.kumapie.mobile.lang.YomitanDictionaries.Glossary -> group(g.dict).contains("bilingual", ignoreCase = true) }
        // Meanings from the bilingual group (else every dictionary but monolingual and forms ones).
        val meaningDicts = glossaries.filter(isBilingual).ifEmpty {
            glossaries.filterNot { isMono(it) || group(it.dict).contains("form", ignoreCase = true) || it.dict.contains("form", ignoreCase = true) }
        }
        fun choice(g: io.github.pedrubik2000.kumapie.mobile.lang.YomitanDictionaries.Glossary, m: String) = Choice("${g.dict.substringBefore(" (").take(24)}: $m", m, null)
        choices = meaningDicts.flatMap { g ->
            g.senses.flatMap { io.github.pedrubik2000.kumapie.mobile.lang.JapaneseLookup.bilingualMeanings(it) }.distinct().take(6).map { choice(g, it) }
        }.distinctBy { it.gloss }.take(40)
        // The meaning that fits the line's English starts chosen, marked.
        SensePick.bestMeaning(choices.map { it.gloss }, SensePick.english(scene, line))?.let { i ->
            choices = choices.mapIndexed { k, c -> if (k == i) c.copy(label = c.label + tr("  · fits this line")) else c }
            chosen = i
        }
        // Monolingual definitions: one per dictionary; encyclopedias (Pixiv, Wikipedia…) last, so a 国語 dictionary leads.
        val encyclopedia = Regex("pixiv|wiki|ニコ|百科", RegexOption.IGNORE_CASE)
        monos = glossaries.filter(isMono).sortedBy { if (encyclopedia.containsMatchIn(it.dict)) 1 else 0 }
            .mapNotNull { g -> g.senses.firstNotNullOfOrNull { io.github.pedrubik2000.kumapie.mobile.lang.JapaneseLookup.monolingualDefinition(it) }?.let { choice(g, it) } }
            .distinctBy { it.gloss }.take(8)
        looked = true
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()
        .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Add to Anki · %s", ja.deck), color = Colors.accent, fontSize = 18.sp)
        Text(cue.text, color = Colors.text, fontSize = 20.sp)
        val hw = headword
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !wordCard, onClick = { wordCard = false }, label = { Text(tr("Sentence card")) })
            if (segment?.word != null) FilterChip(selected = wordCard, onClick = { wordCard = true },
                label = { Text(tr("Word card: %s", hw?.let { it.expression + if (it.reading.isNotBlank() && it.reading != it.expression) " [${it.reading}]" else "" } ?: segment.text)) })
        }
        if (wordCard) {
            when {
                !looked -> Text(tr("Looking up meanings…"), color = Colors.dim, fontSize = 14.sp)
                choices.isEmpty() -> Text(tr("Not in your Japanese dictionaries: a sentence card is still possible."), color = Colors.dim, fontSize = 14.sp)
            }
            choices.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth().clickable { chosen = i }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen == i, onClick = { chosen = i })
                    Text(c.label, color = Colors.text, fontSize = 15.sp)
                }
            }
            if (monos.isNotEmpty()) {
                Text(tr("Monolingual definition"), color = Colors.accent, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                (monos + Choice(tr("None"), "", null)).forEachIndexed { i, c ->
                    Row(Modifier.fillMaxWidth().clickable { monoChosen = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = monoChosen == i, onClick = { monoChosen = i })
                        Text(c.label, color = Colors.text, fontSize = 15.sp)
                    }
                }
            }
        } else {
            Text(tr("The line, its audio, a screenshot and the English."), color = Colors.dim, fontSize = 14.sp)
        }
        Button(enabled = !busy && !done && (!wordCard || choices.isNotEmpty()), onClick = {
            busy = true
            val pick = choices.getOrNull(chosen)
            val w = if (wordCard && segment != null && pick != null && hw != null) Miner.Word(segment.text, segment.word, hw.expression,
                pick.gloss, reading = hw.reading.takeIf { it.isNotBlank() }, pitch = hw.pitches.flatMap { it.second }.firstOrNull(), mono = monos.getOrNull(monoChosen)?.gloss)
            else null
            scope.launch {
                runCatching { library.miner.mine(Miner.Request(episode, scene, line, w)) { status = it } }
                    .onSuccess { status = it; done = true }
                    .onFailure { status = it.message ?: it.toString() }
                busy = false
            }
        }) { Text(if (done) tr("Added") else tr("Add")) }
        if (status.isNotEmpty()) Text(status, color = if (done) Colors.levelZero else Colors.dim, fontSize = 14.sp)
        if (done) LaunchedEffect(Unit) { kotlinx.coroutines.delay(1200); onDone() }
    }
}
