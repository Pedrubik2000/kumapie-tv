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
import io.github.pedrubik2000.kumapie.lang.Miner
import io.github.pedrubik2000.kumapie.lang.SensePick
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch
import io.github.pedrubik2000.kumapie.i18n.tr

/** One meaning to choose for a word card. */
private data class Choice(val label: String, val gloss: String, val example: Pair<String, String>?)

/** A word card's meanings (kumapie's own first, then the dictionaries'), the one picked at first, and monolingual definitions. */
private class WordChoices(val choices: List<Choice>, val chosen: Int, val monos: List<Choice>)

private suspend fun wordChoices(library: Library, ctl: SceneController, line: Int, surface: String, key: String): WordChoices {
    val scene = ctl.scene
    val word = ctl.words[key]
    val def = scene.def(line, key)
    val lemma = def?.lemma?.takeIf { it.isNotBlank() } ?: word?.lemma?.takeIf { it.isNotBlank() }
    val own = (def?.english?.takeIf { it.isNotBlank() } ?: (scene.meanings[key] ?: word?.meaning)?.substringAfter(" = "))
        ?.takeIf { it.isNotBlank() }
    val list = mutableListOf<Choice>()
    own?.let { list += Choice("kumapie: $it", it, null) }
    val all = library.languages.of(ctl.lang).entries(io.github.pedrubik2000.kumapie.lang.Tap(surface, 0, surface.length, key, lemma))
    // Monolingual dictionaries (wty-de-de, wty-en-en) give the card's monolingual definition, not its meaning.
    val isMono = { e: io.github.pedrubik2000.kumapie.lang.DictEntry -> library.yomitan.groupOf(ctl.lang, e.dict) == "Monolingual" }
    val entries = all.filterNot(isMono).ifEmpty { all }
    val monos = all.filter(isMono).flatMap { e -> e.senses.take(3).map { Choice("${e.word} (${e.pos}): ${it.gloss}", it.gloss, null) } }
        .distinctBy { it.gloss }.take(8)
    val fits = SensePick.best(entries, SensePick.english(scene, line))
    var fitting = -1
    entries.forEachIndexed { i, e ->
        e.senses.forEachIndexed { j, s ->
            if (fits == i to j) fitting = list.size
            list += Choice("${e.word} (${e.pos}): ${s.gloss}" + if (fits == i to j) tr("  · fits this line") else "", s.gloss,
                s.examples.firstOrNull())
        }
    }
    return WordChoices(list, if (own == null && fitting >= 0) fitting else 0, monos)
}

/**
 * The word card the mine sheet would make with what it picks at first (rating a word without a card of its own makes
 * it): this line, the scene clip, the word's audio, the meaning that fits the line, the first monolingual definition.
 */
internal suspend fun autoWordCard(library: Library, ctl: SceneController, line: Int, surface: String, key: String): Miner.Request? {
    val c = wordChoices(library, ctl, line, surface, key)
    val pick = c.choices.getOrNull(c.chosen) ?: return null
    val def = ctl.scene.def(line, key)
    val lemma = def?.lemma?.takeIf { it.isNotBlank() } ?: ctl.words[key]?.lemma?.takeIf { it.isNotBlank() }
    return Miner.Request(ctl.episode, ctl.scene, line, Miner.Word(surface, key, lemma, pick.gloss, pick.example, def, mono = c.monos.firstOrNull()?.gloss))
}

/**
 * A Japanese headword's meanings: from the bilingual dictionaries (else every one but monolingual and forms ones), the
 * one that fits [english] (the line's English, when there is a line) chosen and marked; and monolingual definitions,
 * one per dictionary, encyclopedias (Pixiv, Wikipedia…) last so a 国語 dictionary leads.
 */
private fun japaneseChoices(library: Library, hw: io.github.pedrubik2000.kumapie.lang.Headword?, english: String?): WordChoices {
    val ja = io.github.pedrubik2000.kumapie.data.Lang.JAPANESE
    val glossaries = hw?.terms?.flatMap { it.glossaries }.orEmpty()
    fun group(dict: String) = library.yomitan.groupOf(ja, dict)
    val isMono = { g: io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.Glossary -> group(g.dict).contains("mono", ignoreCase = true) }
    val isBilingual = { g: io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.Glossary -> group(g.dict).contains("bilingual", ignoreCase = true) }
    val meaningDicts = glossaries.filter(isBilingual).ifEmpty {
        glossaries.filterNot { isMono(it) || group(it.dict).contains("form", ignoreCase = true) || it.dict.contains("form", ignoreCase = true) }
    }
    fun choice(g: io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.Glossary, m: String) = Choice("${g.dict.substringBefore(" (").take(24)}: $m", m, null)
    var choices = meaningDicts.flatMap { g ->
        g.senses.flatMap { io.github.pedrubik2000.kumapie.lang.JapaneseLookup.bilingualMeanings(it) }.distinct().take(6).map { choice(g, it) }
    }.distinctBy { it.gloss }.take(40)
    var chosen = 0
    english?.let { SensePick.bestMeaning(choices.map { c -> c.gloss }, it) }?.let { i ->
        choices = choices.mapIndexed { k, c -> if (k == i) c.copy(label = c.label + tr("  · fits this line")) else c }
        chosen = i
    }
    val encyclopedia = Regex("pixiv|wiki|ニコ|百科", RegexOption.IGNORE_CASE)
    val monos = glossaries.filter(isMono).sortedBy { if (encyclopedia.containsMatchIn(it.dict)) 1 else 0 }
        .mapNotNull { g -> g.senses.firstNotNullOfOrNull { io.github.pedrubik2000.kumapie.lang.JapaneseLookup.monolingualDefinition(it) }?.let { choice(g, it) } }
        .distinctBy { it.gloss }.take(8)
    return WordChoices(choices, chosen, monos)
}

/**
 * A Japanese rating row's card ([io.github.pedrubik2000.kumapie.lang.JapaneseLookup.forms]): the form with its reading,
 * its headword's meaning that fits the line ([english]; null: the first), "form of 気にする" for a conjugated form, pitch
 * only for the headword itself; null when the headword has no meaning.
 */
internal fun japaneseWord(library: Library, f: io.github.pedrubik2000.kumapie.lang.JapaneseLookup.Form, english: String?): Miner.Word? {
    val hw = f.head ?: return null
    val c = japaneseChoices(library, hw, english)
    val pick = c.choices.getOrNull(c.chosen) ?: return null
    val own = f.written == hw.expression
    return Miner.Word(f.written, null, f.written, pick.gloss, reading = f.reading,
        pitch = if (own) hw.pitches.flatMap { it.second }.firstOrNull() else null, mono = c.monos.firstOrNull()?.gloss,
        formOf = hw.expression.takeIf { !own })
}

/** Japanese rows ([io.github.pedrubik2000.kumapie.lang.JapaneseLookup.forms]) as [RateForm]s; [make] adds a missing form's card from its [Miner.Word]. */
internal fun japaneseRateForms(library: Library, forms: List<io.github.pedrubik2000.kumapie.lang.JapaneseLookup.Form>, english: String?,
                               make: suspend (Miner.Word, (String) -> Unit) -> String) = forms.map { f ->
    val kana = io.github.pedrubik2000.kumapie.lang.JapaneseLookup.hiragana(f.written) == f.reading
    RateForm(f.key, f.written + if (kana) "" else " [${f.reading}]") { p ->
        make(japaneseWord(library, f, english) ?: error(tr("No meaning to put on the card.")), p)
    }
}

/**
 * "Add to Anki": the line as a sentence card, or the picked word as a word card with the meaning chosen from
 * kumapie's own meaning and every dictionary sense. Without kumapie's own meaning, the sense that fits the line's
 * English ([SensePick]) starts chosen. Adds to kuma3 Anki's Deutsch::Mined ([Miner]).
 */
@Composable
fun MineSheet(library: Library, episode: EpisodeDetail, ctl: SceneController, picker: WordPicker, onDone: () -> Unit,
              /** The player's ratings, queue scope and busy flag ([RatingRows]): Undo works in the word card too. */
              ratings: Ratings, queueScope: kotlinx.coroutines.CoroutineScope, queueBusy: androidx.compose.runtime.MutableState<Boolean>) {
    if (library.languages.of(ctl.lang) is io.github.pedrubik2000.kumapie.lang.Japanese) return JapaneseMineSheet(library, episode, ctl, picker, onDone, ratings, queueScope, queueBusy)
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
    var monos by remember { mutableStateOf<List<Choice>>(emptyList()) }
    var monoChosen by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val known = library.languages.of(ctl.lang).known

    LaunchedEffect(segment?.text, key) {
        if (segment == null || key == null) return@LaunchedEffect
        val c = wordChoices(library, ctl, line, segment.text, key)
        monos = c.monos
        chosen = c.chosen
        choices = c.choices
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
            if (monos.isNotEmpty() && def?.targetText.isNullOrBlank()) {
                Text(tr("Monolingual definition"), color = Colors.accent, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                (monos + Choice(tr("None"), "", null)).forEachIndexed { i, c ->
                    Row(Modifier.fillMaxWidth().clickable { monoChosen = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = monoChosen == i, onClick = { monoChosen = i })
                        Text(c.label, color = Colors.text, fontSize = 15.sp)
                    }
                }
            }
        } else {
            Text(tr("The sentence, a video clip of the whole scene and the English."), color = Colors.dim, fontSize = 14.sp)
        }
        // Rate the exact form here too (the word card's row); without a card, the meanings picked above make it.
        if (segment != null && key != null) RatingRows(known, library.settings.prefs, listOf(RateForm(key, null) { p ->
            val pick = choices.getOrNull(chosen) ?: error(tr("No meaning to put on the card."))
            library.miner.mine(Miner.Request(episode, scene, line, Miner.Word(segment.text, key, lemma, pick.gloss, pick.example, def,
                mono = monos.getOrNull(monoChosen)?.gloss)), p)
        }), ratings, queueScope, queueBusy) { repaintWords(ctl, known) }
        Button(enabled = !busy && !done && (!wordCard || choices.isNotEmpty()), onClick = {
            busy = true
            val pick = choices.getOrNull(chosen)
            val request = Miner.Request(episode, scene, line, if (wordCard && segment != null && pick != null) {
                Miner.Word(segment.text, key, lemma, pick.gloss, pick.example, def, mono = monos.getOrNull(monoChosen)?.gloss)
            } else null)
            scope.launch {
                runCatching { library.miner.mine(request) { status = it } }
                    .onSuccess {
                        status = it; done = true
                        // The new card: its word gets its colour and the rating row now.

                        known.reloadCards()
                        key?.let { k -> ctl.words[k]?.let { w -> ctl.words[k] = known.paint(k, w) } }
                    }
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
private fun JapaneseMineSheet(library: Library, episode: EpisodeDetail, ctl: SceneController, picker: WordPicker, onDone: () -> Unit,
                              ratings: Ratings, queueScope: kotlinx.coroutines.CoroutineScope, queueBusy: androidx.compose.runtime.MutableState<Boolean>) {
    val ja = io.github.pedrubik2000.kumapie.data.Lang.JAPANESE
    val scope = rememberCoroutineScope()
    val scene = ctl.scene
    val line = picker.line
    val cue = scene.cues.getOrNull(line) ?: return
    val segment = picker.selected
    val offset = cue.segments.take(picker.seg).sumOf { it.text.length }
    var wordCard by remember { mutableStateOf(segment?.word != null) }
    var headword by remember { mutableStateOf<io.github.pedrubik2000.kumapie.lang.Headword?>(null) }
    var choices by remember { mutableStateOf<List<Choice>>(emptyList()) }
    var monos by remember { mutableStateOf<List<Choice>>(emptyList()) }
    var monoChosen by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableIntStateOf(0) }
    var looked by remember { mutableStateOf(false) }
    var forms by remember { mutableStateOf<List<io.github.pedrubik2000.kumapie.lang.JapaneseLookup.Form>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val known = library.languages.of(ctl.lang).known

    LaunchedEffect(segment?.text) {
        if (segment?.word == null) return@LaunchedEffect
        val found = runCatching {
            library.languages.japanese.lookup(io.github.pedrubik2000.kumapie.lang.Tap(cue.text, offset, segment.text.length))
        }.getOrDefault(emptyList()).firstOrNull()
        headword = found
        val c = japaneseChoices(library, found, SensePick.english(scene, line))
        monos = c.monos
        chosen = c.chosen
        choices = c.choices
        looked = true
        forms = runCatching { library.languages.japanese.forms(cue.text, offset) }.getOrDefault(emptyList())
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
        // Rate each form here too (the word card's rows): a missing one's card is made from its own headword.
        if (forms.isNotEmpty()) RatingRows(known, library.settings.prefs, japaneseRateForms(library, forms, SensePick.english(scene, line)) { w, p ->
            library.miner.mine(Miner.Request(episode, scene, line, w), p)
        }, ratings, queueScope, queueBusy) { repaintWords(ctl, known) }
        Button(enabled = !busy && !done && (!wordCard || choices.isNotEmpty()), onClick = {
            busy = true
            val pick = choices.getOrNull(chosen)
            val w = if (wordCard && segment != null && pick != null && hw != null) Miner.Word(segment.text, segment.word, hw.expression,
                pick.gloss, reading = hw.reading.takeIf { it.isNotBlank() }, pitch = hw.pitches.flatMap { it.second }.firstOrNull(), mono = monos.getOrNull(monoChosen)?.gloss)
            else null
            scope.launch {
                runCatching { library.miner.mine(Miner.Request(episode, scene, line, w)) { status = it } }
                    .onSuccess { status = it; done = true; known.reloadCards(); repaintWords(ctl, known) }
                    .onFailure { status = it.message ?: it.toString() }
                busy = false
            }
        }) { Text(if (done) tr("Added") else tr("Add")) }
        if (status.isNotEmpty()) Text(status, color = if (done) Colors.levelZero else Colors.dim, fontSize = 14.sp)
        if (done) LaunchedEffect(Unit) { kotlinx.coroutines.delay(1200); onDone() }
    }
}
