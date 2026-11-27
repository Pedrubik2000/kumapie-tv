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
fun rememberPlayer(video: String, resume: Double?, lang: String? = null): ExoPlayer {
    val context = LocalContext.current
    val player = remember {
        io.github.pedrubik2000.kumapie.player.videoPlayer(context, lang).apply { // several audio tracks: [lang]'s
            // Its own subtitle tracks off: kumapie draws the lines (a CR file's English track showed over them).
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
            setMediaItem(MediaItem.fromUri(video), ((resume ?: 0.0) * 1000).toLong())
            prepare()
        }
    }
    DisposableEffect(Unit) { io.github.pedrubik2000.kumapie.data.Playing.on = true; onDispose { io.github.pedrubik2000.kumapie.data.Playing.on = false; player.release(); io.github.pedrubik2000.kumapie.torrent.TorrentEngine.release(player) } }
    return player
}

/** [onPlayer]: the player, once made (StreamActivity hands its position back to NuvioTV). */
@Composable
fun ScenePlayer(api: io.github.pedrubik2000.kumapie.data.Backend, settings: Settings, episode: EpisodeDetail, onClose: () -> Unit, onPlayer: (ExoPlayer) -> Unit = {},
                player: ExoPlayer = rememberPlayer(episode.video, episode.resume, episode.lang),
                onController: (SceneController) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { onPlayer(player) }
    val ctl = remember { SceneController(episode, player, settings, api) }
    remember(ctl) { onController(ctl) } // StreamActivity: the late fit moves its lines ([SceneController.shift])
    // kumapie's own cards: the carded words heard in each scene played to its end (exposures).
    remember(ctl) { tvLanguages(context, settings).of(ctl.lang).known.listen(ctl, settings) }
    // This TV's kuma3 colours the words it has cards for (a word mined here stays blue the next day, not red).
    val rating = remember { TvRating() }
    // Rated words' cards move to where they were rated when the player closes ([moveRated]), not at each scene: each
    // move cuts the line's audio from the same stream, and cutting while it played broke Dandadan 9 on the Chromecast
    // (ERROR_CODE_PARSING_CONTAINER_MALFORMED two seconds after the sixth cut, 2026-11-19).
    // The saved colours at once; then kuma3 syncs (it is never opened on the TV) and its cards are read again.
    LaunchedEffect(ctl.plainWords) { openCards(context, settings, ctl) }
    val picker = remember { WordPicker(ctl, api, scope) }
    var options by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) } // the selected word, in screen coordinates
    var playerView by remember { mutableStateOf<PlayerView?>(null) } // its frame is a mined Japanese card's picture
    val focus = remember { FocusRequester() }
    fun openPicker() {
        if (!picker.open()) Toast.makeText(context, tr("No words in this line"), Toast.LENGTH_SHORT).show()
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
                    Action.PICK_LEFT -> rateWord(context, api, settings, ctl, picker, scope, rating, 1, playerView)
                    Action.PICK_DOWN -> rateWord(context, api, settings, ctl, picker, scope, rating, 2, playerView)
                    Action.PICK_RIGHT -> rateWord(context, api, settings, ctl, picker, scope, rating, 3, playerView)
                    Action.PICK_UP -> rateWord(context, api, settings, ctl, picker, scope, rating, 4, playerView)
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
                Action.CYCLE_SUBTITLES -> ctl.cycleSubtitles()
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
                Action.PICK_REPLAY_LINE, Action.PICK_READING -> if (action == Action.PICK_READING && TvReading.several(picker)) nextTvReading() else picker.replayLine()
                Action.PICK_MARK_KNOWN -> picker.toggleKnown()
                // Right after a rating the "add to Anki" button undoes it, until the next word card.
                Action.PICK_MINE -> if (rating.fresh) undoRating(context, api, settings, ctl, scope, rating)
                    else mineWord(context, api, settings, ctl, picker, scope, playerView)
                Action.PICK_UNDO -> undoRating(context, api, settings, ctl, scope, rating)
                Action.PICK_CLOSE -> picker.close()
            }
        }
    }

    // A video the TV can't decode (10-bit HEVC, AC-3 on the Chromecast) would otherwise be a silent black screen.
    var playError by remember { mutableStateOf<String?>(null) }
    var reconnecting by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (!io.github.pedrubik2000.kumapie.player.DecodeRetry.recovered(player, error)) playError = error.errorCodeName
                else reconnecting = io.github.pedrubik2000.kumapie.player.DecodeRetry.reopening(error)
            }

            override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_READY) reconnecting = false }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    val view = LocalView.current
    // Films without judder: while a video plays, the TV's display mode (same resolution) whose refresh rate is a whole
    // multiple of the video's frame rate (23.976 fps → 23.976 Hz, 25 → 50), back to the TV's own mode after. The TV
    // goes black 1-2 s at each switch (Pedro: yes, 2026-11-18). Media3's own switch only does seamless ones; 60 → 24
    // isn't. Nothing happens on a TV without such a mode (bedroom TV: 60 Hz only) or when the mode already fits.
    // The frame rate: the file's, else (MKV: Media3 leaves it unset, every stream from Discover) measured over the
    // first 75 frames shown (~3 s; MKV times are whole ms, so fewer can't tell 23.976 from 24).
    DisposableEffect(player) {
        val window = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .firstNotNullOfOrNull { it as? android.app.Activity }?.window
        fun match(fps: Float) {
            val display = view.display ?: return
            if (window == null || fps <= 0f) return
            val now = display.mode
            val same = display.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            val id = modeForFrameRate(fps, now.modeId, same.map { it.modeId to it.refreshRate })
            android.util.Log.i("kumapie", "video $fps fps: display mode ${id ?: "kept"}")
            if (id != null) window.attributes = window.attributes.apply { preferredDisplayModeId = id }
        }
        val times = ArrayList<Long>() // on the playback thread
        var done = false
        val meta = androidx.media3.exoplayer.video.VideoFrameMetadataListener { us, _, format, _ ->
            if (done) return@VideoFrameMetadataListener
            val fps = if (format.frameRate > 0f) format.frameRate else { times += us; if (times.size < 75) return@VideoFrameMetadataListener; frameRateOf(times) }
            done = true
            if (fps != null) view.post { match(fps) }
        }
        player.setVideoFrameMetadataListener(meta)
        onDispose {
            player.clearVideoFrameMetadataListener(meta)
            window?.let { it.attributes = it.attributes.apply { preferredDisplayModeId = 0 } } // the TV's own mode again
        }
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            ctl.tick()
            ctl.report()
            picker.release() // the player: rememberPlayer's dispose, after this one
            moveRated(rating) // every rated word's card, now that the stream stopped
            syncAnkiLater(context, settings, ctl) // what was mined and rated here
            view.keepScreenOn = false
            io.github.pedrubik2000.kumapie.Restart.playerDisposed()
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
                    subtitleView?.visibility = android.view.View.GONE // kumapie draws the lines; a stream's own track can be on (its fit)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                }.also { playerView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if ((!ctl.playing || picker.isOpen) && !options) TopBar(ctl, title = shortTitle(ctl.episode.show))
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
        if (!picker.isOpen && !options && !help) (playError?.let { torrentStall() ?: tr("This TV can't play this video (%s).", it) } ?: if (reconnecting) tr("Reconnecting…") else null)?.let {
            Text(it, color = Colors.text, fontSize = 24.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp).background(Colors.surface, RoundedCornerShape(8.dp)).padding(24.dp))
        }
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
        Text(ctl.episode.title.let { if (it.startsWith(title, ignoreCase = true)) it else "$title · $it" }, color = Colors.text, fontSize = 20.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 24.dp))
        val modes = listOfNotNull(
            if (ctl.pauseAtSceneEnd) tr("pauses after each line") else tr("no pause after each line"),
            if (ctl.pauseAtSceneEnd && ctl.playThroughKnown) tr("i+0 plays through") else null,
            if (ctl.slow) "0.75x" else null,
        ).joinToString(" · ")
        Text(modes, color = Colors.text.copy(alpha = 0.8f), fontSize = 16.sp, modifier = Modifier.padding(end = 24.dp))
        Text(tr("Line %1\$d / %2\$d", scene.index + 1, ctl.scenes.size), color = Colors.text, fontSize = 20.sp)
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
    fun line() = hint(pick, Action.PICK_READING, if (TvReading.several(picker)) "Reading" else "Line") ?: hint(pick, Action.PICK_REPLAY_LINE, "Line")
    fun back(label: String) = Hint(tr(label)) { if (Pad.on) Glyph(android.view.KeyEvent.KEYCODE_BUTTON_B) else KeyCap(tr("Back")) }
    val card = pickedCard(ctl, picker)
    val own = LocalContext.current.let { context -> remember(ctl.lang) { runCatching { ownCards(context, ctl.settings, ctl.lang) }.getOrDefault(false) } }
    val hints: List<Hint?> = when {
        rating.open -> listOf(move("Rate", "← ↓ → ↑"), back("Cancel"))
        picker.isOpen && picker.inDef -> listOf(move("Word", "←→"), hint(pick, Action.PICK_DOWN, "Back to the line"),
            hint(pick, Action.PICK_AUDIO, "Hear"), hint(pick, Action.PICK_MARK_KNOWN, "Known"), back("Close"))
        // A due word with its meaning shown: the "hear" button rates (the pad hears with its own button).
        picker.isOpen && picker.cardOpen && card?.second in RATEABLE -> listOf(move("Word", "←→"), hint(pick, Action.PICK_AUDIO, "Rate card", on = true),
            hint(pick, Action.PICK_HEAR, "Hear"), line(), hint(pick, Action.PICK_MARK_KNOWN, "Known"), back("Close"))
        picker.isOpen -> listOf(move("Word", "←→"), hint(pick, Action.PICK_AUDIO, if (picker.cardOpen) "Hear" else "Meaning"),
            line(), hint(pick, Action.PICK_MINE, if (rating.fresh) "Undo" else if (ctl.episode.srs || own) "Add to reviews" else "Add to Anki"),
            hint(pick, Action.PICK_MARK_KNOWN, "Known"), back("Close"))
        ctl.atSceneEnd -> listOf(hint(watch, Action.PLAY_PAUSE, "Play"), hint(watch, Action.REPLAY_LINE, "Line"),
            hint(watch, Action.WORD_PICKER, "Pick a word"), hint(watch, Action.PREVIOUS_SCENE, "Previous"))
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
        Text(tr("Did you know it before looking?"), color = Arcade.accent, fontSize = 13.sp, style = Arcade.label)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(Colors.unknown, Color(0xFFB9B5C6), Colors.due, Colors.mined).forEachIndexed { i, color ->
                Row(Modifier.width(160.dp).background(Arcade.slab, Slab).padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    DpadArm(i, color)
                    Text(tr(RATINGS[i]), color = color, fontSize = 17.sp, style = Arcade.label)
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
        Text(rating.said, color = Colors.due, fontSize = 15.sp, style = Arcade.label)
        Text(rating.last?.first.orEmpty(), color = Colors.text, fontSize = 15.sp)
        forInput(pick.filterValues { it == Action.PICK_MINE || it == Action.PICK_UNDO }.keys).firstOrNull()?.let { KeyHint(it) }
        Text(tr("Undo"), color = Colors.text, fontSize = 13.sp, style = Arcade.label)
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
            // Scrolls if the seven buttons ever outgrow the screen (focus brings each into view); 20 dp margins fit a 540 dp TV.
            Modifier.padding(horizontal = 48.dp, vertical = 20.dp).width(520.dp).arcadePanel()
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Title(tr("Options"), fontSize = 26.sp)
            Button(onClick = ctl::togglePauseAtSceneEnd, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                Text(tr("After each line: %s", if (ctl.pauseAtSceneEnd) tr("pause") else tr("play on")))
            }
            Button(onClick = ctl::togglePlayThroughKnown, modifier = Modifier.fillMaxWidth()) {
                Text(tr("i+0 lines (no new or due word): %s", if (ctl.playThroughKnown) tr("play through") else tr("pause")).replace("I+0", "i+0"))
            }
            Button(onClick = ctl::togglePlainWords, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Word colours: %s", if (ctl.plainWords) tr("off (lookup only)") else tr("on")))
            }
            Button(onClick = ctl::toggleSlow, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Speed: %s", if (ctl.slow) "0.75x" else tr("normal")))
            }
            Button(onClick = { ctl.changeSubtitles(ctl.subtitles.next()) }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Subtitles: %s", ctl.subtitles.label(ctl.lang)))
            }
            if (ctl.episode.id.startsWith("alone:")) SyncNudge(ctl)
            Button(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text(tr("Help: the buttons")) }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(tr("Close")) }
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

/**
 * The display mode for a video of [fps]: among [modes] (id to refresh rate, all the current resolution) the one with
 * the highest refresh rate that is a whole multiple of [fps] (25 fps: 50 Hz over 25), null when [current] already is
 * one (30 fps at 60 Hz) or none is. Exact within 0.01 Hz: 24 fps on 23.976 Hz would skip a frame every 42 s.
 */
fun modeForFrameRate(fps: Float, current: Int, modes: List<Pair<Int, Float>>): Int? {
    fun fits(hz: Float) = Math.round(hz / fps).let { k -> k >= 1 && kotlin.math.abs(hz - k * fps) < 0.01f * k }
    if (modes.firstOrNull { it.first == current }?.let { fits(it.second) } == true) return null
    return modes.filter { fits(it.second) }.maxByOrNull { it.second }?.first
}

/**
 * Frames per second from frames' presentation [times] (µs, in the order shown): the mean gap over the gaps no longer
 * than 1.5× the median (a dropped frame or a seek doesn't count). Null when there are too few.
 */
fun frameRateOf(times: List<Long>): Float? {
    val gaps = times.zipWithNext { a, b -> b - a }.filter { it > 0 }
    if (gaps.size < 10) return null
    val med = gaps.sorted()[gaps.size / 2]
    val kept = gaps.filter { it <= med * 3 / 2 }
    return (1e6 * kept.size / kept.sum()).toFloat()
}

private fun torrentStall(): String? = io.github.pedrubik2000.kumapie.torrent.TorrentEngine.run {
    if (status.value?.phase == io.github.pedrubik2000.kumapie.torrent.TorrentPhase.FAILED) line.value else null
}
