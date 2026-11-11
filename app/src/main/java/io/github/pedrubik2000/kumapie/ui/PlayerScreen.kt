package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.profile
import io.github.pedrubik2000.kumapie.i18n.tr
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.keys
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.shortTitle
import io.github.pedrubik2000.kumapie.player.Action
import io.github.pedrubik2000.kumapie.player.Binding
import io.github.pedrubik2000.kumapie.player.KeyHandler
import io.github.pedrubik2000.kumapie.player.KeyContext
import io.github.pedrubik2000.kumapie.player.keyMapOf
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import kotlinx.coroutines.delay

/** Loads the episode's scenes, then plays it scene by scene. */
@Composable
fun PlayerScreen(api: Api, settings: Settings, show: Show, episode: Episode, onClose: () -> Unit) {
    // The video loads (index, first seconds) while the scenes are fetched and parsed, not after.
    val player = rememberPlayer(api.videoUrl(episode.id), episode.resume)
    var detail by remember { mutableStateOf<EpisodeDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val personal = io.github.pedrubik2000.kumapie.lang.PersonalDictionary.get(context)
    LaunchedEffect(attempt) {
        error = null
        personal.sync(api::personalDictionary) // what the phone and tablet added
        runCatching { api.episode(episode.id) }.onSuccess { e ->
            detail = personal.join(joinNames(context, settings, e))
        }.onFailure { error = it.message ?: it.toString() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            error != null -> ErrorBox(error!!, onRetry = { attempt++ }, onSettings = onClose)
            detail == null -> Text("${show.title} · ${episode.title}", color = Colors.dim, fontSize = 22.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center))
            else -> ScenePlayer(api, settings, detail!!.copy(show = show.title), onClose, player = player) // the Home title (metadata, English)
        }
    }
}

/** A player loading [video] from [resume] seconds (the start without), released with the composition. */
@Composable
fun rememberPlayer(video: String, resume: Double?): ExoPlayer {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(video), ((resume ?: 0.0) * 1000).toLong())
            prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    return player
}

/** [onPlayer]: the player, once made (StreamActivity hands its position back to NuvioTV). */
@Composable
fun ScenePlayer(api: Api, settings: Settings, episode: EpisodeDetail, onClose: () -> Unit, onPlayer: (ExoPlayer) -> Unit = {},
                player: ExoPlayer = rememberPlayer(episode.video, episode.resume)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { onPlayer(player) }
    val ctl = remember { SceneController(episode, player, settings, api) }
    // This TV's kuma3 colours the words it has cards for (a word mined here stays blue the next day, not red).
    val rating = remember { TvRating() }
    // The saved colours at once; then kuma3 syncs (it is never opened on the TV) and its cards are read again.
    LaunchedEffect(Unit) { openCards(context, settings, ctl) }
    val picker = remember { WordPicker(ctl, api, scope) }
    var options by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) } // the selected word, in screen coordinates
    var playerView by remember { mutableStateOf<PlayerView?>(null) } // its frame is a mined Japanese card's picture
    val focus = remember { FocusRequester() }
    fun openPicker() {
        if (!picker.open()) Toast.makeText(context, tr("No words in this scene"), Toast.LENGTH_SHORT).show()
    }
    // The list of buttons opens by itself only the first time a person plays something (user, 2026-10-11); then
    // from Options > Help.
    val seenKey = "help_seen_${settings.profile}"
    var help by remember { mutableStateOf(settings.showHelp && !settings.prefs.getBoolean(seenKey, false)) }
    LaunchedEffect(help) { if (help) settings.prefs.edit().putBoolean(seenKey, true).apply() }
    var helpKey by remember { mutableStateOf<Int?>(null) } // the button that closed it: its release is ours too
    val watchBindings = remember { settings.keys(KeyContext.WATCH) }
    val pickerBindings = remember { settings.keys(KeyContext.PICKER) }
    val watchKeys = remember { keyMapOf(KeyContext.WATCH, watchBindings) }
    val pickerKeys = remember { keyMapOf(KeyContext.PICKER, pickerBindings) }
    // A rating's time runs from the word's meaning showing; another word ends the rating. Undo is offered until the
    // next word card (or rating).
    LaunchedEffect(picker.line, picker.seg, picker.cardOpen, picker.isOpen) {
        rating.since = System.currentTimeMillis()
        rating.open = false
        if (picker.cardOpen || !picker.isOpen) rating.fresh = false
    }
    val keys = remember {
        KeyHandler({ if (picker.isOpen) pickerKeys else watchKeys }) { action ->
            // Rating a due word: one arrow rates it (← Again, ↓ Hard, → Good, ↑ Easy), Back cancels, nothing else acts.
            if (rating.open) {
                when (action) {
                    Action.PICK_LEFT -> rateWord(context, settings, ctl, picker, scope, rating, 1)
                    Action.PICK_DOWN -> rateWord(context, settings, ctl, picker, scope, rating, 2)
                    Action.PICK_RIGHT -> rateWord(context, settings, ctl, picker, scope, rating, 3)
                    Action.PICK_UP -> rateWord(context, settings, ctl, picker, scope, rating, 4)
                    Action.PICK_CLOSE -> rating.open = false
                    else -> Unit
                }
                return@KeyHandler
            }
            when (action) {
                Action.PLAY_PAUSE -> ctl.togglePlay()
                Action.NEXT_SCENE -> ctl.nextScene()
                Action.PREVIOUS_SCENE -> ctl.previousScene()
                Action.REPLAY_LINE -> ctl.replayLine()
                Action.REPLAY_SCENE -> ctl.replayScene()
                // At the end of a scene ↓ opens the picker; otherwise it steps through the subtitle modes.
                Action.CYCLE_SUBTITLES -> if (ctl.atSceneEnd) openPicker() else ctl.cycleSubtitles()
                Action.TOGGLE_GERMAN -> ctl.toggleTarget()
                Action.TOGGLE_ENGLISH -> ctl.toggleEnglish()
                Action.SLOW -> ctl.toggleSlow()
                Action.PAUSE_AT_SCENE_END -> ctl.togglePauseAtSceneEnd()
                Action.OPTIONS -> options = true
                Action.HELP -> help = true
                Action.WORD_PICKER -> openPicker()
                Action.PICK_LEFT -> picker.left()
                Action.PICK_RIGHT -> picker.right()
                // A Japanese card's dictionaries scroll first; at their end ↑↓ change line as before.
                Action.PICK_UP -> if (!(picker.cardOpen && scrollTvDictionaries(-1))) picker.up()
                Action.PICK_DOWN -> if (!(picker.cardOpen && scrollTvDictionaries(+1))) picker.down()
                // A due word with its meaning shown: rate it (the pad hears with PICK_HEAR; any word once it is rated).
                Action.PICK_AUDIO -> if (picker.cardOpen && !picker.inDef && pickedCard(ctl, picker)?.second in RATEABLE) rating.open = true
                    else picker.hearAgain()
                Action.PICK_HEAR -> picker.hearAgain()
                Action.PICK_REPLAY_LINE -> picker.replayLine()
                Action.PICK_MARK_KNOWN -> picker.toggleKnown()
                // Right after a rating the "add to Anki" button undoes it, until the next word card.
                Action.PICK_MINE -> if (rating.fresh) undoRating(context, settings, ctl, scope, rating)
                    else mineWord(context, settings, ctl, picker, scope, playerView)
                Action.PICK_UNDO -> undoRating(context, settings, ctl, scope, rating)
                Action.PICK_CLOSE -> picker.close()
            }
        }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            ctl.tick()
            ctl.report()
            picker.release() // the player: rememberPlayer's dispose, after this one
            syncAnkiLater(context, settings, ctl) // what was mined and rated here, to AnkiWeb
            view.keepScreenOn = false
        }
    }
    // Home button, input switch, screensaver: pause and save where it was.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    player.pause()
                    ctl.tick()
                    ctl.report()
                    options = false
                    picker.close()
                }
                Lifecycle.Event.ON_RESUME -> runCatching { focus.requestFocus() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            ctl.tick()
            delay(40)
        }
    }
    // The episode starts when the help screen (shown first) is closed; opening help later pauses.
    var begun by remember { mutableStateOf(false) }
    LaunchedEffect(help) {
        when {
            help && begun -> ctl.pause()
            !help && !begun -> { begun = true; ctl.begin() }
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.uptimeMillis(); delay(250) } }
    BackHandler(onBack = onClose)

    Box(
        Modifier.fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent {
                val k = it.nativeKeyEvent
                when {
                    help -> { // any button closes the help screen (and does nothing else)
                        if (k.action == android.view.KeyEvent.ACTION_DOWN) { help = false; helpKey = k.keyCode }
                        true
                    }
                    helpKey == k.keyCode && k.action == android.view.KeyEvent.ACTION_UP -> { helpKey = null; true }
                    options -> false
                    else -> keys.handle(k)
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    isFocusable = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                }.also { playerView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!ctl.playing || now < ctl.bannerUntil || picker.isOpen) TopBar(ctl, title = shortTitle(ctl.episode.show))
        Subtitles(ctl, picker, onAnchor = { anchor = it }, aboveBox = true)
        if (picker.isOpen && picker.cardOpen) anchor?.let {
            val japanese = ctl.lang == Lang.JAPANESE
            // Japanese: the wide two-column card over the room above the picked line, less the screen margin and the
            // card's padding (~70 dp), so the line stays visible.
            val room = with(androidx.compose.ui.platform.LocalDensity.current) { it.top.toDp().value.toInt() } - 70
            JapaneseText(japanese) {
                if (japanese) MeaningCard(ctl, picker, it, maxWidth = 940.dp, ownMeaning = false, container = Modifier.arcadeCard(),
                    footer = {
                        TvJapaneseCard(settings, ctl, picker, room.coerceIn(220, 460) - if (rating.open) 84 else 0)
                        if (rating.open) RateRow()
                    })
                else MeaningCard(ctl, picker, it, container = Modifier.arcadeCard(), heading = Arcade.label, footer = if (rating.open) ({ RateRow() }) else null)
            }
        }
        if (options) PlayerOptions(ctl, onClose = { options = false; focus.requestFocus() },
            onHelp = { options = false; help = true; focus.requestFocus() })
        if (rating.fresh && picker.isOpen) RatedToast(rating, pickerBindings, Modifier.align(Alignment.BottomCenter).padding(bottom = 50.dp))
        if ((settings.showHelp || rating.open) && !options && !help)
            PlayerHints(ctl, picker, rating, watchBindings, pickerBindings, Modifier.align(Alignment.BottomStart))
        if (help) HelpOverlay(settings, ctl.lang)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Show, scene n/N with its level, and the modes. */
@Composable
private fun TopBar(ctl: SceneController, title: String) {
    val scene = ctl.scene
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xF0000000), Color(0x99000000), Color.Transparent)))
            .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One line, cut with "…": a long title must not push the counter and level away (item 13d).
        Text("$title · ${ctl.episode.title}", color = Colors.text, fontSize = 20.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 24.dp))
        val modes = listOfNotNull(
            if (ctl.pauseAtSceneEnd) tr("pauses at scene end") else tr("no pause at scene end"),
            if (ctl.pauseAtSceneEnd && ctl.playThroughKnown) tr("i+0 plays through") else null,
            if (ctl.slow) "0.75x" else null,
        ).joinToString(" · ")
        Text(modes, color = Colors.text.copy(alpha = 0.8f), fontSize = 16.sp, modifier = Modifier.padding(end = 24.dp))
        Text(tr("Scene %1\$d / %2\$d", scene.index + 1, ctl.scenes.size), color = Colors.text, fontSize = 20.sp)
        // No badge where the PC doesn't know the person's words (everything comes as known: a parent's episode).
        val coloured = remember(ctl.episode) { ctl.episode.words.values.any { it.status != "k" } }
        if (coloured) LevelBadge(ctl.levelOf(scene), Modifier.padding(start = 16.dp))
    }
}

/**
 * The hint bar for where the player is: the buttons as they are set (Settings > Change buttons), on the input used
 * last.
 */
@Composable
private fun PlayerHints(ctl: SceneController, picker: WordPicker, rating: TvRating, watch: Map<Binding, Action>,
                        pick: Map<Binding, Action>, modifier: Modifier) {
    fun hint(keys: Map<Binding, Action>, action: Action, label: String, on: Boolean = false): Hint? =
        forInput(keys.filterValues { it == action }.keys).firstOrNull()?.let { b -> Hint(tr(label), on) { KeyHint(b, if (on) Arcade.background else Colors.text) } }
    fun move(label: String, arrows: String) = Hint(tr(label)) { if (Pad.on) Glyph(DPAD) else KeyCap(arrows) }
    fun back(label: String) = Hint(tr(label)) { if (Pad.on) Glyph(android.view.KeyEvent.KEYCODE_BUTTON_B) else KeyCap(tr("Back")) }
    val card = pickedCard(ctl, picker)
    val hints: List<Hint?> = when {
        rating.open -> listOf(move("Rate", "← ↓ → ↑"), back("Cancel"))
        picker.isOpen && picker.inDef -> listOf(move("Word", "←→"), hint(pick, Action.PICK_DOWN, "Back to the line"),
            hint(pick, Action.PICK_AUDIO, "Hear"), hint(pick, Action.PICK_MARK_KNOWN, "Known"), back("Close"))
        // A due word with its meaning shown: the "hear" button rates (the pad hears with its own button).
        picker.isOpen && picker.cardOpen && card?.second in RATEABLE -> listOf(move("Word", "←→"), hint(pick, Action.PICK_AUDIO, "Rate card", on = true),
            hint(pick, Action.PICK_HEAR, "Hear"), hint(pick, Action.PICK_REPLAY_LINE, "Line"), hint(pick, Action.PICK_MARK_KNOWN, "Known"), back("Close"))
        picker.isOpen -> listOf(move("Word", "←→"), hint(pick, Action.PICK_AUDIO, if (picker.cardOpen) "Hear" else "Meaning"),
            hint(pick, Action.PICK_REPLAY_LINE, "Line"), hint(pick, Action.PICK_MINE, if (rating.fresh) "Undo" else "Add to Anki"),
            hint(pick, Action.PICK_MARK_KNOWN, "Known"), back("Close"))
        ctl.atSceneEnd -> listOf(hint(watch, Action.PLAY_PAUSE, "Next scene"), hint(watch, Action.REPLAY_LINE, "Line"),
            hint(watch, Action.CYCLE_SUBTITLES, "Pick a word"), hint(watch, Action.PREVIOUS_SCENE, "Previous"))
        !ctl.playing -> listOf(hint(watch, Action.PLAY_PAUSE, "Play"), hint(watch, Action.WORD_PICKER, "Pick a word"),
            hint(watch, Action.REPLAY_LINE, "Line"), hint(watch, Action.OPTIONS, "Options"))
        else -> emptyList()
    }
    hints.filterNotNull().takeIf { it.isNotEmpty() }?.let { HintBar(it, modifier) }
}

/** "Did you know it before looking?" and the four ratings, each on its arrow: the bottom of a due word's card. */
@Composable
private fun RateRow() {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Did you know it before looking?").uppercase(), color = Arcade.accent, fontSize = 13.sp, style = Arcade.label)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(Colors.unknown, Color(0xFFB9B5C6), Colors.due, Colors.mined).forEachIndexed { i, color ->
                Row(Modifier.width(160.dp).background(Arcade.slab, Slab).padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    DpadArm(i, color)
                    Text(tr(RATINGS[i]).uppercase(), color = color, fontSize = 17.sp, style = Arcade.label)
                }
            }
        }
    }
}

/** "GOOD 引き戸 · Undo" over the hint bar, while the rating can still be undone with the "add to Anki" button. */
@Composable
private fun RatedToast(rating: TvRating, pick: Map<Binding, Action>, modifier: Modifier) {
    Row(modifier.background(Color(0xF50B0A10), Slab).padding(horizontal = 24.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(rating.said.uppercase(), color = Colors.due, fontSize = 15.sp, style = Arcade.label)
        Text(rating.last?.first.orEmpty(), color = Colors.text, fontSize = 15.sp)
        forInput(pick.filterValues { it == Action.PICK_MINE || it == Action.PICK_UNDO }.keys).firstOrNull()?.let { KeyHint(it) }
        Text(tr("Undo").uppercase(), color = Colors.text, fontSize = 13.sp, style = Arcade.label)
        if (rating.today > 0) Text(tr("Reviewed from episodes today: %d", rating.today), color = Colors.dim, fontSize = 13.sp)
    }
}

@Composable
fun LevelBadge(level: Int?, modifier: Modifier = Modifier) {
    val (text, color) = when {
        level == null -> "♪" to Colors.dim
        level == 0 -> "i+0" to Colors.levelZero
        level == 1 -> "i+1" to Colors.levelOne
        else -> "i+$level" to Colors.unknown
    }
    Text(text, color = Color.Black, fontSize = 18.sp,
        modifier = modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 2.dp))
}

/** The player's modes, saved on the TV. Back or Close returns to the video. */
@Composable
private fun PlayerOptions(ctl: SceneController, onClose: () -> Unit, onHelp: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).onBackKey(onBack = onClose), contentAlignment = Alignment.CenterEnd) {
        Column(
            // Scrolls: seven buttons are taller than the screen at this size (focus brings each into view).
            Modifier.padding(48.dp).width(520.dp).arcadePanel()
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Title(tr("Options"), fontSize = 26.sp)
            Button(onClick = ctl::togglePauseAtSceneEnd, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                Text(tr("At the end of a scene: %s", if (ctl.pauseAtSceneEnd) tr("pause") else tr("play on")).uppercase())
            }
            Button(onClick = ctl::togglePlayThroughKnown, modifier = Modifier.fillMaxWidth()) {
                Text(tr("i+0 scenes (no new words): %s", if (ctl.playThroughKnown) tr("play through") else tr("pause")).uppercase())
            }
            Button(onClick = ctl::toggleNewStraight, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Skip nothing: %s", if (ctl.newStraight) tr("on, the whole episode") else tr("off, scene by scene")).uppercase())
            }
            Button(onClick = ctl::toggleSlow, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Speed: %s", if (ctl.slow) "0.75x" else tr("normal")).uppercase())
            }
            Button(onClick = { ctl.changeSubtitles(ctl.subtitles.next()) }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Subtitles: %s", ctl.subtitles.label(ctl.lang)).uppercase())
            }
            Button(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text(tr("Help: the buttons").uppercase()) }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(tr("Close").uppercase()) }
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}
