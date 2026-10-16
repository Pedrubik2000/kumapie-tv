package io.github.pedrubik2000.kumapie.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Backend
import io.github.pedrubik2000.kumapie.data.Cue
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.ui.SubtitleTap
import io.github.pedrubik2000.kumapie.ui.Subtitles
import io.github.pedrubik2000.kumapie.ui.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val STATE = mapOf("k" to "known", "u" to "new word", "d" to "due today", "b" to "mined, not reviewed yet", "n" to "learning")
private val DECKS = mapOf("de" to "Deutsch Mined", "en" to "English Mined", "ja" to "Japanese Mined")
private val RATINGS = listOf("Again", "Hard", "Good", "Easy")

fun colorOf(state: String) = when (state) { "u" -> Colors.unknown; "d" -> Colors.due; "b" -> Colors.mined; else -> Colors.text }

/** The server, with each lookup's senses kept for the word card (one request counts the lookup and brings them). */
private class DesktopBackend(private val api: Api, private val bridge: Bridge) : Backend by api {
    val senses = mutableStateMapOf<String, List<String>>()
    override suspend fun lookup(word: String, scene: String): Int = bridge.lookup(word, scene).let { (n, s) -> senses[word] = s; n }
}

@Composable
fun PlayerScreen(api: Api, bridge: Bridge, settings: Settings, show: Show, episode: Episode, onNext: (Episode) -> Unit, onClose: () -> Unit) {
    var detail by remember(episode.id) { mutableStateOf<EpisodeDetail?>(null) }
    var error by remember(episode.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(episode.id) {
        runCatching { api.episode(episode.id) }.onSuccess { detail = it.copy(show = show.title) }.onFailure { error = it.message ?: "$it" }
    }
    val next = show.episodes.getOrNull(show.episodes.indexOfFirst { it.id == episode.id } + 1)
    when {
        error != null -> Text(error!!, color = Colors.unknown, modifier = Modifier.padding(24.dp).clickable(onClick = onClose))
        detail == null -> Text("${show.title} · ${episode.title}", color = Colors.dim, modifier = Modifier.padding(24.dp))
        else -> androidx.compose.runtime.key(episode.id) {
            ScenePlayer(api, bridge, settings, detail!!, onNext = next?.let { { onNext(it) } }, onClose)
        }
    }
}

/** What the word card shows under its buttons: a line, and the button that goes with it. */
private class Status(val text: String, val button: String? = null, val action: (suspend () -> Unit)? = null)

@Composable
private fun ScenePlayer(api: Api, bridge: Bridge, settings: Settings, episode: EpisodeDetail, onNext: (() -> Unit)?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val player = remember { VlcPlayer() }
    val backend = remember { DesktopBackend(api, bridge) }
    val ctl = remember { SceneController(episode, player, settings, backend) }
    val picker = remember { WordPicker(ctl, backend, scope) }
    val lang = episode.lang
    val deck = DECKS[lang] ?: "Mined"
    var ankiUp by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf<Bridge.Cards?>(null) }
    var status by remember { mutableStateOf<Status?>(null) }
    var mining by remember { mutableStateOf(false) }
    var looked by remember { mutableIntStateOf(0) }

    /** kuma3's card states onto the episode's words (KnownWords.paint): a word with its own card gets its state. */
    suspend fun reloadCards() {
        ankiUp = bridge.up()
        cards = if (ankiUp) runCatching { bridge.cards(lang) }.getOrNull() else null
        for ((k, w) in ctl.words.toMap()) {
            val c = cards?.forms?.get(k)
            if (w.card != c) ctl.words[k] = w.copy(card = c)
        }
    }

    fun key(): String? = picker.selected?.word
    fun canRate() = ankiUp && key()?.let { cards?.forms?.containsKey(it) } == true
    fun rate(ease: Int) {
        val k = key() ?: return
        if (!canRate()) return
        scope.launch {
            runCatching { bridge.rate(lang, k, ease) }.onSuccess { n ->
                reloadCards()
                status = Status("Answered: ${RATINGS[ease - 1]}" + if (n > 1) " ($n cards)" else "", "Undo") {
                    runCatching { repeat(n) { bridge.undo() } }
                        .onSuccess { reloadCards(); status = Status("Rating undone") }
                        .onFailure { status = Status("Couldn't undo: Ctrl+Z in kuma3") }
                }
            }.onFailure { status = Status("Couldn't rate: ${it.message}") }
        }
    }
    fun startMine() {
        val k = key() ?: return
        if (!ankiUp) return
        val note = cards?.notes?.get(k)
        if (note != null) { status = Status("Already in $deck", "Open in Anki") { bridge.browse(note) }; return }
        status = null
        mining = true
    }
    fun mine(word: Boolean, gloss: String, english: String) {
        val seg = picker.selected ?: return
        val line = picker.line
        mining = false
        status = Status(if (lang == "ja") "Cutting the line's audio…" else "Cutting the clip…")
        scope.launch {
            runCatching { bridge.mine(episode.id, ctl.scene.id, line, if (word) seg.word else null, seg.text, gloss, english) }
                .onSuccess { m ->
                    reloadCards()
                    val added = Status("Added to ${m.deck}", "Undo") {
                        runCatching { bridge.delete(m.note) }; reloadCards(); status = Status("Card removed")
                    }
                    status = added
                    delay(10_000)
                    if (status === added) status = Status("Added to ${m.deck}") // Undo for 10 s
                }
                .onFailure { status = Status("Couldn't mine: ${it.message}") }
        }
    }

    LaunchedEffect(Unit) {
        player.open(api.url(episode.video))
        while (!player.component.mediaPlayer().status().isSeekable) delay(50) // VLC seeks once the media has started
        ctl.begin()
        reloadCards()
    }
    LaunchedEffect(Unit) { while (true) { ctl.tick(); delay(40) } }
    // A new word picked: the card starts clean.
    LaunchedEffect(picker.line, picker.seg, picker.cardOpen) { status = null; mining = false; if (picker.cardOpen) looked++ }
    DisposableEffect(Unit) {
        keys = handler@{ e ->
            if (e.type != KeyEventType.KeyDown) return@handler false
            if (mining) return@handler if (e.key == Key.Escape) { mining = false; true } else false // typing in the form
            when (e.key) {
                Key.Spacebar -> ctl.togglePlay()
                Key.Enter, Key.NumPadEnter -> if (picker.isOpen && !picker.cardOpen) picker.hearAgain() else ctl.nextScene()
                Key.DirectionRight -> ctl.nextScene()
                Key.DirectionLeft -> if (e.isShiftPressed) ctl.replayScene() else ctl.previousScene()
                Key.R -> ctl.replayLine()
                Key.S -> ctl.changeSubtitles(ctl.subtitles.next())
                Key.Tab -> if (!picker.isOpen) picker.open() else if (e.isShiftPressed) picker.left() else picker.right()
                Key.One, Key.NumPad1 -> rate(1)
                Key.Two, Key.NumPad2 -> rate(2)
                Key.Three, Key.NumPad3 -> rate(3)
                Key.Four, Key.NumPad4 -> rate(4)
                Key.M -> startMine()
                Key.K -> picker.toggleKnown()
                Key.A -> picker.hearWord()
                Key.Escape -> picker.close()
                else -> return@handler false
            }
            true
        }
        onDispose {
            keys = null
            ctl.tick()
            ctl.report()
            picker.release()
            player.release()
        }
    }

    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxSize()) {
            TopRow(ctl, onClose)
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                SwingPanel(factory = { player.component }, modifier = Modifier.fillMaxSize())
            }
            // Under the video, never over it: nothing covers the picture or the lines.
            Box(Modifier.fillMaxWidth().height(260.dp).background(Colors.background)) {
                Subtitles(ctl, picker, onAnchor = {}, onTap = { tap ->
                    when (tap) {
                        is SubtitleTap.Word -> picker.tap(tap.line, tap.seg)
                        SubtitleTap.Box -> ctl.togglePlay()
                    }
                })
            }
        }
        Column(Modifier.width(400.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(8.dp)).background(Colors.surface)
                .verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val last = ctl.index == ctl.scenes.lastIndex && ctl.atSceneEnd
                when {
                    last && !picker.cardOpen -> EpisodeDone(ctl, looked, onNext)
                    picker.isOpen && picker.cardOpen && key() != null ->
                        WordCard(ctl, picker, backend, key()!!, ankiUp, canRate(), status, mining, scope,
                            onRate = ::rate, onMine = ::startMine, onAdd = ::mine, onCancel = { mining = false },
                            onStatusAction = { a -> scope.launch { a() } })
                    else -> Text("Click a coloured word  ·  Tab picks one, Enter opens it", color = Colors.dim, fontSize = 14.sp)
                }
            }
            Transcript(ctl, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TopRow(ctl: SceneController, onClose: () -> Unit) {
    val lv = ctl.levelOf(ctl.scene)
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("←", fontSize = 20.sp, modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 6.dp))
        androidx.compose.foundation.text.BasicText("${io.github.pedrubik2000.kumapie.data.shortTitle(ctl.episode.show)} · ${ctl.episode.title}",
            Modifier.weight(1f), TextStyle(color = Colors.text, fontSize = 16.sp), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text("Scene ${ctl.index + 1}/${ctl.scenes.size}", color = Colors.dim, fontSize = 14.sp)
        Text(lv?.let { "i+$it" } ?: "–", fontSize = 13.sp, color = when (lv) { 0 -> Colors.levelZero; 1 -> Colors.levelOne; else -> Colors.unknown },
            modifier = Modifier.background(Colors.surface, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        Chip("Subtitles: ${ctl.subtitles.label(ctl.lang)}") { ctl.changeSubtitles(ctl.subtitles.next()) }
        Chip("0.75×", on = ctl.slow) { ctl.toggleSlow() }
        Chip("Skip nothing", on = ctl.newStraight) { ctl.toggleNewStraight() }
        Chip("Don't stop on i+0", on = ctl.playThroughKnown) { ctl.togglePlayThroughKnown() }
        Chip("Pause at scene end", on = ctl.pauseAtSceneEnd) { ctl.togglePauseAtSceneEnd() }
    }
}

@Composable
private fun ColumnScope.WordCard(
    ctl: SceneController, picker: WordPicker, backend: DesktopBackend, key: String, ankiUp: Boolean, canRate: Boolean,
    status: Status?, mining: Boolean, scope: CoroutineScope,
    onRate: (Int) -> Unit, onMine: () -> Unit, onAdd: (Boolean, String, String) -> Unit, onCancel: () -> Unit,
    onStatusAction: (suspend () -> Unit) -> Unit,
) {
    val seg = picker.selected ?: return
    val word = ctl.words[key] ?: Word("u", "")
    val cue = ctl.scene.cues.getOrNull(picker.line)
    // The sense that fits this line first (the phone's "In this line"), then every sense from the dictionaries.
    val senses = (listOf(ctl.scene.meanings[key], word.meaning.takeIf { it.isNotBlank() }) + backend.senses[key].orEmpty())
        .filterNotNull().distinct()
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(word.lemma.ifBlank { seg.text }, fontSize = 24.sp)
        if (word.reading.isNotBlank()) Text(word.reading, color = Colors.dim, fontSize = 16.sp)
    }
    val shown = word.shownFor(seg.text)
    Text((STATE[word.card ?: shown] ?: "") + if (word.lookups > 0) "  ·  looked up ${word.lookups}×" else "", color = colorOf(shown), fontSize = 13.sp)
    senses.ifEmpty { listOf("No meaning in the dictionaries") }.forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 15.sp) }
    cue?.let { c ->
        Text(buildAnnotatedString {
            c.segments.forEachIndexed { i, sg -> if (i == picker.seg) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Colors.text)) { append(sg.text) } else append(sg.text) }
        }, color = Colors.dim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp))
    }
    val why = when { !ankiUp -> "Open kuma3 to rate and mine"; !canRate -> "No card yet: mine it to rate it"; else -> null }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RATINGS.forEachIndexed { i, l -> Chip("${i + 1} $l", enabled = canRate) { onRate(i + 1) } }
        Chip("M Mine", enabled = ankiUp, onClick = onMine)
        Chip(if (word.marked) "K Not known" else "K Known") { picker.toggleKnown() }
        Chip("A 🔊") { picker.hearWord() }
    }
    why?.let { Text(it, color = Colors.dim, fontSize = 13.sp) }
    if (mining) MineForm(ctl, picker, senses, onAdd, onCancel)
    status?.let { s ->
        Row(Modifier.fillMaxWidth().background(Colors.background, RoundedCornerShape(6.dp)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (s.button != null && s.action != null) Chip(s.button) { onStatusAction(s.action) }
        }
    }
}

/** M in the card: word or sentence card, the sense, the English; Add (or Enter in the English) mines it. */
@Composable
private fun MineForm(ctl: SceneController, picker: WordPicker, senses: List<String>, onAdd: (Boolean, String, String) -> Unit, onCancel: () -> Unit) {
    var wordCard by remember { mutableStateOf(true) }
    var sense by remember { mutableIntStateOf(0) }
    val cue = ctl.scene.cues.getOrNull(picker.line)
    var english by remember(cue) {
        mutableStateOf(cue?.let { c -> ctl.scene.english.filter { it.end > c.start && it.start < c.end }.joinToString(" ") { it.text } } ?: "")
    }
    Column(Modifier.fillMaxWidth().border(1.dp, Colors.accent, RoundedCornerShape(6.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Word card", on = wordCard) { wordCard = true }
            Chip("Sentence card", on = !wordCard) { wordCard = false }
        }
        if (wordCard) senses.forEachIndexed { i, s ->
            Text((if (i == sense) "● " else "○ ") + s, fontSize = 14.sp, color = if (i == sense) Colors.text else Colors.dim,
                modifier = Modifier.clickable { sense = i })
        }
        Text("English", color = Colors.dim, fontSize = 12.sp)
        BasicTextField(english, { english = it }, textStyle = TextStyle(color = Colors.text, fontSize = 14.sp), cursorBrush = SolidColor(Colors.text),
            modifier = Modifier.fillMaxWidth().background(Colors.background, RoundedCornerShape(4.dp)).padding(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Add to ${DECKS[ctl.lang.code] ?: "Mined"}") { onAdd(wordCard, senses.getOrElse(sense) { "" }, english) }
            Chip("Cancel", onClick = onCancel)
        }
    }
}

@Composable
private fun EpisodeDone(ctl: SceneController, looked: Int, onNext: (() -> Unit)?) {
    Text("Episode done", fontSize = 22.sp)
    Text("${ctl.scenes.count { ctl.wasSeen(it) }} of ${ctl.scenes.size} scenes watched · $looked words looked up", color = Colors.dim, fontSize = 14.sp)
    onNext?.let { Chip("Next episode", onClick = it) }
}

/** The whole episode by scene; scenes not watched yet show their number and level only (Pedro, 2026-10-15). */
@Composable
private fun Transcript(ctl: SceneController, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(ctl.index) { list.animateScrollToItem(ctl.index) }
    val current = ctl.currentCue()
    LazyColumn(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Colors.surface).padding(horizontal = 12.dp, vertical = 6.dp), list) {
        itemsIndexed(ctl.scenes, key = { _, s -> s.id }) { i, sc ->
            val seen = ctl.wasSeen(sc)
            val here = i == ctl.index
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                val lv = ctl.levelOf(sc)
                Text("Scene ${i + 1} · ${lv?.let { "i+$it" } ?: "no words"}", color = Colors.dim, fontSize = 12.sp,
                    modifier = Modifier.clickable { ctl.playScene(i) })
                if (seen || here) sc.cues.forEachIndexed { line, cue ->
                    Text(colored(cue, ctl.words), fontSize = 15.sp,
                        color = if (seen && !here) Colors.dim else Colors.text,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(if (here && cue == current) Color(0xFF3A3546) else Color.Transparent)
                            .clickable { if (!here) ctl.playScene(i); ctl.replayCue(line) }.padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
        }
    }
}

private fun colored(cue: Cue, words: Map<String, Word>) = buildAnnotatedString {
    for (s in cue.segments) {
        val w = s.word?.let { words[it] }
        if (w == null) append(s.text) else withStyle(SpanStyle(color = colorOf(w.shownFor(s.text)))) { append(s.text) }
    }
}
