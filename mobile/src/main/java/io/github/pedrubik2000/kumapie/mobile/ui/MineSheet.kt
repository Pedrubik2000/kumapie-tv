package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.github.pedrubik2000.kumapie.lang.WordCard
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch
import io.github.pedrubik2000.kumapie.i18n.tr

// What goes on a word card is shared with the TV (lang/WordCard.kt); this file keeps the sheet.
private typealias Choice = WordCard.Choice

private suspend fun wordChoices(library: Library, ctl: SceneController, line: Int, surface: String, key: String) =
    WordCard.choices(library.languages.of(ctl.lang), library.yomitan, ctl, line, surface, key)

/** The word card the mine sheet would make with what it picks at first (rating a word without a card of its own makes it). */
internal suspend fun autoWordCard(library: Library, ctl: SceneController, line: Int, surface: String, key: String): Miner.Request =
    Miner.Request(ctl.episode, ctl.scene, line, WordCard.auto(library.languages.of(ctl.lang), library.yomitan, ctl, line, surface, key))

private fun japaneseChoices(library: Library, hw: io.github.pedrubik2000.kumapie.lang.Headword?, english: String?) =
    WordCard.japaneseChoices(library.yomitan, hw, english)

/** The meaning of [hw] that fits the line's English (the word popup's "In this line"), null when none does. */
internal fun fitsThisLine(library: Library, hw: io.github.pedrubik2000.kumapie.lang.Headword, english: String?): String? {
    val c = japaneseChoices(library, hw, english ?: return null)
    return if (c.fits) c.choices.getOrNull(c.chosen)?.gloss else null
}

/**
 * A Japanese rating row's card ([io.github.pedrubik2000.kumapie.lang.JapaneseLookup.forms]): the form with its reading,
 * its headword's meaning that fits the line ([english]; null: the first), "form of 気にする" for a conjugated form, pitch
 * only for the headword itself; no meaning (and no "form of") when the dictionaries have none.
 */
internal fun japaneseWord(library: Library, f: io.github.pedrubik2000.kumapie.lang.JapaneseLookup.Form, english: String?): Miner.Word =
    WordCard.japanese(library.yomitan, f.head, f.surface, null, f.written, f.reading, english)

/** Japanese rows ([io.github.pedrubik2000.kumapie.lang.JapaneseLookup.forms]) as [RateForm]s (only forms with a card show). */
internal fun japaneseRateForms(forms: List<io.github.pedrubik2000.kumapie.lang.JapaneseLookup.Form>) = forms.map { f ->
    val kana = f.reading.isEmpty() || io.github.pedrubik2000.kumapie.lang.JapaneseLookup.hiragana(f.written) == f.reading
    // Two rows (食べられた and 食べる): say which is which.
    val role = if (forms.size < 2) "" else if (f.written == f.head?.expression) tr(" · dictionary form") else tr(" · this form")
    RateForm(f.key, f.written + (if (kana) "" else " [${f.reading}]") + role)
}

/**
 * "Add to Anki": the line as a sentence card, or the picked word as a word card with the meaning chosen from
 * kumapie's own meaning and every dictionary sense. Without kumapie's own meaning, the sense that fits the line's
 * English ([SensePick]) starts chosen. Adds to kuma3 Anki's Deutsch Mined ([Miner]).
 */
@Composable
fun MineSheet(library: Library, episode: EpisodeDetail, ctl: SceneController, picker: WordPicker, onDone: () -> Unit) {
    if (library.languages.of(ctl.lang) is io.github.pedrubik2000.kumapie.lang.Japanese) return JapaneseMineSheet(library, episode, ctl, picker, onDone)
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
    var looked by remember { mutableStateOf(false) }
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
        looked = true
    }

    // The choices scroll; Add and its status stay at the bottom (user, 2026-10-14). A set height: the sheet sizes
    // itself on opening, before the meanings load, and doesn't grow after (Add went under the screen).
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(addTitle(library, ctl.lang), color = Colors.accent, fontSize = 18.sp)
        Text(Miner.sentence(scene, line).first, color = Colors.text, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !wordCard, onClick = { wordCard = false }, label = { Text(tr("Sentence card")) })
            if (segment?.word != null) FilterChip(selected = wordCard, onClick = { wordCard = true },
                label = { Text(tr("Word card: %s", segment.text)) })
        }
        if (wordCard) {
            // No dictionary has it (a new phone has none yet): the word card is made without a meaning.
            if (!looked) Text(tr("Looking up meanings…"), color = Colors.dim, fontSize = 14.sp)
            else if (choices.isEmpty()) Text(tr("Not in your dictionaries: the card gets no meaning."), color = Colors.dim, fontSize = 14.sp)
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
            Text(tr("The sentence, a video clip of the line and the %s.", translationName()), color = Colors.dim, fontSize = 14.sp)
        }
    }
        Button(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy && !done && (!wordCard || looked), onClick = {
            busy = true
            val pick = choices.getOrNull(chosen)
            val request = Miner.Request(episode, scene, line, if (wordCard && segment != null) {
                Miner.Word(segment.text, key, lemma, pick?.gloss.orEmpty(), pick?.example, def, mono = monos.getOrNull(monoChosen)?.gloss)
            } else null)
            mining.launch { // not the sheet's scope: going back to the video mid-mine must not cancel it or its repaint
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
 * from the monolingual ones (国語 dictionaries before encyclopedias; or none). Into Japanese Mined.
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
    var headword by remember { mutableStateOf<io.github.pedrubik2000.kumapie.lang.Headword?>(null) }
    var choices by remember { mutableStateOf<List<Choice>>(emptyList()) }
    var monos by remember { mutableStateOf<List<Choice>>(emptyList()) }
    var monoChosen by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableIntStateOf(0) }
    // The dictionary whose whole entry the card gets ([CardDefinition]); null: the meaning picked below.
    var entry by remember { mutableStateOf<String?>(null) }
    var looked by remember { mutableStateOf(false) }
    var forms by remember { mutableStateOf<List<io.github.pedrubik2000.kumapie.lang.JapaneseLookup.Form>>(emptyList()) }
    val japanese = library.languages.japanese
    var audios by remember { mutableStateOf<List<Pair<String, java.io.File>>?>(null) }
    var audioChosen by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val known = library.languages.of(ctl.lang).known

    // The word card's headwords are the rating rows' (item 25): the word's own first (がんばります: 頑張る), then the
    // longer match's (気にしない: 気にする). Not the lookup's first headword, which can be a monolingual-only form.
    val heads = forms.mapNotNull { it.head }.distinctBy { it.expression to it.reading }
    LaunchedEffect(segment?.text) {
        if (segment?.word == null) return@LaunchedEffect
        forms = runCatching { library.languages.japanese.forms(cue.text, offset) }.getOrDefault(emptyList())
        headword = forms.mapNotNull { it.head }.firstOrNull()
        if (headword == null) looked = true // no headword covers the word: a card without a meaning, or a sentence card
    }
    LaunchedEffect(headword) {
        val hw = headword ?: return@LaunchedEffect
        audios = null; audioChosen = 0
        launch { audios = japanese.audio.recordings(hw.expression, hw.reading).map { (source, f) -> sourceName(source) to f } }
        val c = japaneseChoices(library, hw, SensePick.english(scene, line))
        entry = io.github.pedrubik2000.kumapie.lang.CardDefinition.find(japanese.yomitan, hw.expression, hw.reading)?.first
        monos = c.monos
        chosen = c.chosen
        choices = c.choices
        looked = true
    }

    val hw = headword
    // The choices scroll; Add and its status stay at the bottom (user, 2026-10-14). A set height: the sheet sizes
    // itself on opening, before the meanings load, and doesn't grow after (Add went under the screen).
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(addTitle(library, ja), color = Colors.accent, fontSize = 18.sp)
        Text(cue.text, color = Colors.text, fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !wordCard, onClick = { wordCard = false }, label = { Text(tr("Sentence card")) })
            // One chip per headword the rows have (気 and 気にする), else the word as written.
            if (segment?.word != null) (heads.ifEmpty { listOf(null) }).forEach { h ->
                FilterChip(selected = wordCard && (h == null || hw == h), onClick = { wordCard = true; if (h != null && hw != h) { headword = h; looked = false } },
                    label = { Text(tr("Word card: %s", h?.let { it.expression + if (it.reading.isNotBlank() && it.reading != it.expression) " [${it.reading}]" else "" } ?: segment.text)) })
            }
        }
        if (wordCard) {
            when {
                !looked -> Text(tr("Looking up meanings…"), color = Colors.dim, fontSize = 14.sp)
                entry != null -> Text(tr("Definition: the whole %s entry", shortDictName(entry!!)), color = Colors.text, fontSize = 15.sp)
                choices.isEmpty() -> Text(tr("Not in your Japanese dictionaries: a sentence card is still possible."), color = Colors.dim, fontSize = 14.sp)
            }
            if (entry == null) choices.forEachIndexed { i, c ->
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
            Text(tr("The line, its audio, a screenshot and the %s.", translationName()), color = Colors.dim, fontSize = 14.sp)
        }
        if (wordCard && hw != null) {
            Text(tr("Word audio"), color = Colors.accent, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            if (audios == null) Text(tr("Looking for recordings…"), color = Colors.dim, fontSize = 14.sp)
            else if (audios!!.isEmpty()) Text(tr("No recordings: the device voice says it."), color = Colors.dim, fontSize = 14.sp)
            audios?.let { list ->
                list.map { it.first }.forEachIndexed { i, name ->
                    Row(Modifier.fillMaxWidth().clickable { audioChosen = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = audioChosen == i, onClick = { audioChosen = i })
                        Text(name, color = Colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            audioChosen = i
                            scope.launch { japanese.audio.play(list[i].second) }
                        }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, tr("Hear it"), tint = Colors.text) }
                    }
                }
            }
        }
    }
        Button(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy && !done && (!wordCard || choices.isNotEmpty() || entry != null), onClick = {
            busy = true
            val pick = choices.getOrNull(chosen)
            // The headword when the line has it whole (壬氏 of the piece 壬): that is what the sentence bolds.
            val w = if (wordCard && segment != null && (pick != null || entry != null) && hw != null) Miner.Word(hw.expression.takeIf { it in cue.text } ?: segment.text, segment.word, hw.expression,
                pick?.gloss.orEmpty(), reading = hw.reading.takeIf { it.isNotBlank() }, pitch = hw.pitches.flatMap { it.second }.firstOrNull(), mono = monos.getOrNull(monoChosen)?.gloss,
                audio = audios?.getOrNull(audioChosen)?.second)
            else null
            mining.launch {
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

/** A recording's source as the sheet names it. */
private fun sourceName(source: String) = when (source) {
    "nhk16" -> "NHK"
    "shinmeikai8" -> "Shinmeikai"
    "jpod" -> "JapanesePod101"
    else -> source.replaceFirstChar { it.uppercase() }
}

/** The person's translation language mid-sentence: "English", or "español" in Spanish menus. */
private fun translationName(): String = io.github.pedrubik2000.kumapie.data.Lang.GERMAN.translationDisplayName
    .let { if (io.github.pedrubik2000.kumapie.i18n.Tr.spanish) it.lowercase() else it }

/** Mining outlives the sheet: closing it before "Added" still adds the card and colours the word. */
private val mining = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

/** The sheet's title: "Add to Anki" while kuma3 holds this language's cards, else kumapie's own store (no PC, or moved there). */
@Composable
private fun addTitle(library: Library, lang: io.github.pedrubik2000.kumapie.data.Lang): String {
    val own = remember(lang) { library.languages.of(lang).known.ankiApp() == io.github.pedrubik2000.kumapie.lang.SrsCards.PKG }
    return if (own) tr("New card · %s", lang.deck) else tr("Add to Anki · %s", lang.deck)
}
