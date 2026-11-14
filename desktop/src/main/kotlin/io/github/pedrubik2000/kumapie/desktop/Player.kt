package io.github.pedrubik2000.kumapie.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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
import io.github.pedrubik2000.kumapie.ui.MeaningCard
import io.github.pedrubik2000.kumapie.ui.SubtitleTap
import io.github.pedrubik2000.kumapie.ui.Subtitles
import io.github.pedrubik2000.kumapie.ui.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val STATE = mapOf("k" to "known", "u" to "new word", "d" to "due today", "b" to "mined, not reviewed yet")
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
    var transcript by remember { mutableStateOf(settings.prefs.getBoolean("desktop_transcript", false)) }
    var anchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) } // the picked word on screen
    var options by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(android.os.SystemClock.uptimeMillis()) }

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
        while (!player.seekable) delay(50) // VLC seeks once the media has started
        ctl.begin()
        reloadCards()
    }
    LaunchedEffect(Unit) { while (true) { ctl.tick(); now = android.os.SystemClock.uptimeMillis(); delay(40) } }
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
                Key.T -> { transcript = !transcript; settings.prefs.edit().putBoolean("desktop_transcript", transcript).apply() }
                Key.O -> options = !options
                Key.Escape -> if (options) options = false else if (picker.isOpen) picker.close() else return@handler false // else: leaves fullscreen
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

    // The tablet in landscape: the picture fills the window, the lines over its bottom, the word's card right above the
    // word (rate, mine and the mine form in it: the eyes stay where the word is). The transcript is a side panel (T).
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxSize()) {
            VideoFrame(player, Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (picker.isOpen) picker.close() else ctl.togglePlay() },
                    onDoubleTap = { p -> picker.close(); if (p.x < size.width / 2) ctl.replayLine() else ctl.replayScene() },
                )
            })
            if (!ctl.playing || now < ctl.bannerUntil || picker.isOpen) {
                TopRow(ctl, transcript, onTranscript = { transcript = !transcript; settings.prefs.edit().putBoolean("desktop_transcript", transcript).apply() }, onOptions = { options = !options }, onClose)
            }
            // Hover = the word's short gloss, no click, no pause (kumapie_pc_plan.md: the PC's one new gesture).
            var hover by remember { mutableStateOf<Pair<String, androidx.compose.ui.geometry.Rect>?>(null) }
            Subtitles(ctl, picker, onAnchor = { anchor = it }, onTap = { tap ->
                when (tap) {
                    is SubtitleTap.Word -> picker.tap(tap.line, tap.seg)
                    SubtitleTap.Box -> if (picker.isOpen) picker.close() else ctl.togglePlay()
                }
            }, onHover = { w, r ->
                val seg = w?.let { ctl.scene.cues.getOrNull(it.line)?.segments?.getOrNull(it.seg) }
                val word = seg?.word?.let { ctl.words[it] }
                hover = if (r == null || seg == null || word == null || word.meaning.isBlank()) null
                else "${word.lemma.ifEmpty { seg.text }}: ${word.meaning}" to r
            })
            hover?.takeIf { !picker.isOpen }?.let { (text, r) ->
                Text(text, color = Colors.text, fontSize = 15.sp,
                    modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(r.left.toInt(), (r.top - 30.dp.toPx()).toInt()) }
                        .background(Arcade.slab, Slab).padding(horizontal = 14.dp, vertical = 3.dp))
            }
            val k = key()
            if (picker.isOpen && picker.cardOpen && k != null) anchor?.let { a ->
                MeaningCard(ctl, picker, a, maxWidth = 620.dp, onTapDef = picker::tapInDef, ownMeaning = false, onClose = { picker.close() },
                    container = Modifier.arcadeCard(), heading = Arcade.label, footer = {
                    WordCard(ctl, picker, backend, k, ankiUp, canRate(), status, mining,
                        onRate = ::rate, onMine = ::startMine, onAdd = ::mine, onCancel = { mining = false },
                        onStatusAction = { act -> scope.launch { act() } })
                })
            }
            if (ctl.index == ctl.scenes.lastIndex && ctl.atSceneEnd && !picker.isOpen) {
                Column(Modifier.align(Alignment.Center).arcadePanel().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) { EpisodeDone(ctl, looked, onNext) }
            }
            if (options) Column(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp).width(340.dp).arcadePanel().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Title("Options", fontSize = 28.sp)
                val full = Modifier.fillMaxWidth()
                ArcadeButton("Subtitles: ${ctl.subtitles.label(ctl.lang)}", full) { ctl.changeSubtitles(ctl.subtitles.next()) }
                ArcadeButton("0.75×: ${if (ctl.slow) "on" else "off"}", full, selected = ctl.slow) { ctl.toggleSlow() }
                ArcadeButton("Skip nothing: ${if (ctl.newStraight) "on" else "off"}", full, selected = ctl.newStraight) { ctl.toggleNewStraight() }
                ArcadeButton("Don't stop on i+0: ${if (ctl.playThroughKnown) "on" else "off"}", full, selected = ctl.playThroughKnown) { ctl.togglePlayThroughKnown() }
                ArcadeButton("Pause at scene end: ${if (ctl.pauseAtSceneEnd) "on" else "off"}", full, selected = ctl.pauseAtSceneEnd) { ctl.togglePauseAtSceneEnd() }
                ArcadeButton("Close", full, key = "O") { options = false }
            }
            val hints = when {
                mining -> emptyList()
                picker.isOpen -> buildList {
                    add(Hint("Tab", "Word")); add(Hint("⇧Tab", "Word")); add(Hint("Enter", "Hear"))
                    if (canRate()) add(Hint("1-4", "Rate"))
                    add(Hint("M", "Mine")); add(Hint("K", "Known")); add(Hint("A", "Hear word")); add(Hint("Esc", "Close"))
                }
                ctl.atSceneEnd -> listOf(Hint("Space", "Next scene"), Hint("→", "Next scene"), Hint("R", "Line"), Hint("Tab", "Pick a word"), Hint("←", "Previous"))
                !ctl.playing -> listOf(Hint("Space", "Play"), Hint("Tab", "Pick a word"), Hint("R", "Line"), Hint("O", "Options"))
                else -> emptyList()
            }
            if (hints.isNotEmpty()) HintBar(hints, Modifier.align(Alignment.BottomCenter))
        }
        if (transcript) Transcript(ctl, Modifier.width(380.dp).fillMaxSize())
    }
}

@Composable
private fun TopRow(ctl: SceneController, transcript: Boolean, onTranscript: () -> Unit, onOptions: () -> Unit, onClose: () -> Unit) {
    val lv = ctl.levelOf(ctl.scene)
    Row(Modifier.fillMaxWidth()
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xF0000000), Color(0x99000000), Color.Transparent)))
        .padding(start = 40.dp, end = 40.dp, top = 22.dp, bottom = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ArcadeButton("Back", onClick = onClose)
        androidx.compose.foundation.text.BasicText("${io.github.pedrubik2000.kumapie.data.shortTitle(ctl.episode.show)} · ${ctl.episode.title}".uppercase(),
            Modifier.weight(1f), TextStyle(color = Colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text("Scene ${ctl.index + 1} / ${ctl.scenes.size}", color = Colors.dim, fontSize = 16.sp)
        LevelBadge(lv)
        ArcadeButton("Options", key = "O", onClick = onOptions)
        ArcadeButton("⛶") { toggleFullscreen() }
        ArcadeButton("Transcript", selected = transcript, key = "T", onClick = onTranscript)
    }
}

@Composable
private fun ColumnScope.WordCard(
    ctl: SceneController, picker: WordPicker, backend: DesktopBackend, key: String, ankiUp: Boolean, canRate: Boolean,
    status: Status?, mining: Boolean,
    onRate: (Int) -> Unit, onMine: () -> Unit, onAdd: (Boolean, String, String) -> Unit, onCancel: () -> Unit,
    onStatusAction: (suspend () -> Unit) -> Unit,
) {
    val seg = picker.selected ?: return
    val word = ctl.words[key] ?: Word("u", "")
    // The sense that fits this line first (the phone's "In this line"), then every sense from the dictionaries.
    val senses = (listOf(ctl.scene.meanings[key], word.meaning.takeIf { it.isNotBlank() }) + backend.senses[key].orEmpty())
        .filterNotNull().distinct()
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(word.lemma.ifBlank { seg.text }, fontSize = 24.sp)
        if (word.reading.isNotBlank()) Text(word.reading, color = Colors.dim, fontSize = 16.sp)
    }
    val shown = word.shownFor(seg.text)
    Text((STATE[word.card ?: shown] ?: "") + if (word.lookups > 0) "  ·  looked up ${word.lookups}×" else "", color = colorOf(shown), fontSize = 13.sp)
    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        senses.ifEmpty { listOf("No meaning in the dictionaries") }.forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 15.sp) }
    }
    val why = when { !ankiUp -> "Open kuma3 to rate and mine"; !canRate -> "No card yet: mine it to rate it"; else -> null }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RATINGS.forEachIndexed { i, l -> RateSlab(l, i + 1, listOf(Colors.unknown, Color(0xFFB9B5C6), Colors.due, Colors.mined)[i], canRate) { onRate(i + 1) } }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ArcadeButton("Mine", enabled = ankiUp, key = "M", fontSize = 12.sp, onClick = onMine)
        ArcadeButton(if (word.marked) "Not known" else "Known", key = "K", fontSize = 12.sp) { picker.toggleKnown() }
        ArcadeButton("🔊", key = "A", fontSize = 12.sp) { picker.hearWord() }
    }
    why?.let { Text(it, color = Colors.dim, fontSize = 13.sp) }
    if (mining) MineForm(ctl, picker, senses, onAdd, onCancel)
    status?.let { s ->
        Row(Modifier.fillMaxWidth().background(Arcade.band).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (s.button != null && s.action != null) ArcadeButton(s.button, fontSize = 12.sp) { onStatusAction(s.action) }
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
    Column(Modifier.fillMaxWidth().background(Arcade.band).border(1.dp, Arcade.accent).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArcadeButton("Word card", selected = wordCard, fontSize = 12.sp) { wordCard = true }
            ArcadeButton("Sentence card", selected = !wordCard, fontSize = 12.sp) { wordCard = false }
        }
        if (wordCard) senses.forEachIndexed { i, s ->
            Text((if (i == sense) "● " else "○ ") + s, fontSize = 14.sp, color = if (i == sense) Colors.text else Colors.dim,
                modifier = Modifier.clickable { sense = i })
        }
        Text("English", color = Colors.dim, fontSize = 12.sp)
        BasicTextField(english, { english = it }, textStyle = TextStyle(color = Colors.text, fontSize = 14.sp), cursorBrush = SolidColor(Colors.text),
            modifier = Modifier.fillMaxWidth().background(Colors.background, RoundedCornerShape(4.dp)).padding(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArcadeButton("Add to ${DECKS[ctl.lang.code] ?: "Mined"}", fontSize = 12.sp) { onAdd(wordCard, senses.getOrElse(sense) { "" }, english) }
            ArcadeButton("Cancel", fontSize = 12.sp, onClick = onCancel)
        }
    }
}

@Composable
private fun EpisodeDone(ctl: SceneController, looked: Int, onNext: (() -> Unit)?) {
    Title("Episode done", fontSize = 28.sp)
    Text("${ctl.scenes.count { ctl.wasSeen(it) }} of ${ctl.scenes.size} scenes watched · $looked words looked up", color = Colors.dim, fontSize = 14.sp)
    onNext?.let { ArcadeButton("Next episode", onClick = it) }
}

/** The TV's RateRow slab: a coloured slab, the label in capitals and the key cap. */
@Composable
private fun RateSlab(label: String, n: Int, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.background(color.copy(alpha = if (enabled) 1f else 0.35f), Slab)
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyCap("$n", Color.Black)
        Text(label.uppercase(), color = Color.Black, fontSize = 12.sp, style = Arcade.label)
    }
}

/** The whole episode by scene; scenes not watched yet show their number and level only (Pedro, 2026-10-15). */
@Composable
private fun Transcript(ctl: SceneController, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(ctl.index) { list.animateScrollToItem(ctl.index) }
    val current = ctl.currentCue()
    LazyColumn(modifier.fillMaxWidth().background(Arcade.band).padding(horizontal = 16.dp, vertical = 10.dp), list) {
        itemsIndexed(ctl.scenes, key = { _, s -> s.id }) { i, sc ->
            val seen = ctl.wasSeen(sc)
            val here = i == ctl.index
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                val lv = ctl.levelOf(sc)
                Text("Scene ${i + 1} · ${lv?.let { "i+$it" } ?: "no words"}".uppercase(), color = if (here) Arcade.accent else Colors.dim, fontSize = 12.sp,
                    style = Arcade.label, modifier = Modifier.clickable { ctl.playScene(i) })
                if (seen || here) sc.cues.forEachIndexed { line, cue ->
                    Text(colored(cue, ctl.words), fontSize = 15.sp,
                        color = if (seen && !here) Colors.dim else Colors.text,
                        modifier = Modifier.fillMaxWidth()
                            .background(if (here && cue == current) Arcade.slab else Color.Transparent)
                            .then(if (here && cue == current) Modifier.drawBehind { drawRect(Arcade.accent, size = Size(4.dp.toPx(), size.height)) } else Modifier)
                            .clickable { if (!here) ctl.playScene(i); ctl.replayCue(line) }.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp))
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
