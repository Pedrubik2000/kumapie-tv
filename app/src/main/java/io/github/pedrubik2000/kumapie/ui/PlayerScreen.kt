package io.github.pedrubik2000.kumapie.ui

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.keys
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.player.Action
import io.github.pedrubik2000.kumapie.player.KeyHandler
import io.github.pedrubik2000.kumapie.player.KeyContext
import io.github.pedrubik2000.kumapie.player.keyMapOf
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import kotlinx.coroutines.delay

/** Loads the episode's scenes, then plays it scene by scene. */
@Composable
fun PlayerScreen(api: Api, settings: Settings, show: Show, episode: Episode, onClose: () -> Unit) {
    var detail by remember { mutableStateOf<EpisodeDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { api.episode(episode.id) }.onSuccess { detail = it }.onFailure { error = it.message ?: it.toString() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            error != null -> ErrorBox(error!!, onRetry = { attempt++ }, onSettings = onClose)
            detail == null -> Text("${show.title} · ${episode.title}", color = Colors.dim, fontSize = 22.sp,
                modifier = Modifier.align(Alignment.Center))
            else -> ScenePlayer(api, settings, detail!!, onClose)
        }
    }
}

@Composable
private fun ScenePlayer(api: Api, settings: Settings, episode: EpisodeDetail, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(episode.video))
            prepare()
        }
    }
    val ctl = remember { SceneController(episode, player, settings, api) }
    val picker = remember { WordPicker(ctl, api, scope) }
    var options by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) } // the selected word, in screen coordinates
    val focus = remember { FocusRequester() }
    fun openPicker() {
        if (!picker.open()) Toast.makeText(context, tr("No words in this scene"), Toast.LENGTH_SHORT).show()
    }
    var help by remember { mutableStateOf(settings.showHelp) } // the list of buttons, when an episode opens
    var helpKey by remember { mutableStateOf<Int?>(null) } // the button that closed it: its release is ours too
    val watchKeys = remember { keyMapOf(KeyContext.WATCH, settings.keys(KeyContext.WATCH)) }
    val pickerKeys = remember { keyMapOf(KeyContext.PICKER, settings.keys(KeyContext.PICKER)) }
    val keys = remember {
        KeyHandler({ if (picker.isOpen) pickerKeys else watchKeys }) { action ->
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
                Action.PICK_UP -> picker.up()
                Action.PICK_DOWN -> picker.down()
                Action.PICK_AUDIO -> picker.hearAgain()
                Action.PICK_REPLAY_LINE -> picker.replayLine()
                Action.PICK_MARK_KNOWN -> picker.toggleKnown()
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
            picker.release()
            player.release()
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
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!ctl.playing || now < ctl.bannerUntil || picker.isOpen) TopBar(ctl, picker, hints = settings.showHelp)
        Subtitles(ctl, picker, onAnchor = { anchor = it })
        if (picker.isOpen && picker.cardOpen) anchor?.let { MeaningCard(ctl, picker, it) }
        if (options) PlayerOptions(ctl, onClose = { options = false; focus.requestFocus() },
            onHelp = { options = false; help = true; focus.requestFocus() })
        if (help) HelpOverlay(settings)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Show, scene n/N with its level, the modes, and a hint of the keys when paused. */
@Composable
private fun TopBar(ctl: SceneController, picker: WordPicker, hints: Boolean) {
    val scene = ctl.scene
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xF0000000), Color(0x99000000), Color.Transparent)))
            .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ctl.episode.show} · ${ctl.episode.title}", color = Colors.text, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
        val modes = listOfNotNull(
            if (ctl.pauseAtSceneEnd) tr("pauses at scene end") else tr("plays on"),
            if (ctl.slow) "0.75x" else null,
        ).joinToString(" · ")
        Text(modes, color = Colors.text.copy(alpha = 0.8f), fontSize = 16.sp, modifier = Modifier.padding(end = 24.dp))
        Text(tr("Scene %1\$d / %2\$d", scene.index + 1, ctl.scenes.size), color = Colors.text, fontSize = 20.sp)
        // No badge where the PC doesn't know the person's words (everything comes as known: a parent's episode).
        val coloured = remember(ctl.episode) { ctl.episode.words.values.any { it.status != "k" } }
        if (coloured) LevelBadge(ctl.levelOf(scene), Modifier.padding(start = 16.dp))
    }
    val hint = when {
        picker.isOpen && picker.inDef -> "←→ words of the definition   ↓ back to the line   OK: hear the word (twice: definition)   hold ↓ / Y: known   Back: close"
        picker.isOpen && picker.cardOpen ->
            "OK: hear (twice: definition)   ↑ into the definition   ←→ other words   hold OK / X: replay line   hold ↓ / Y: known"
        picker.isOpen -> tr("←→ words   ↑↓ lines   OK: show the meaning   hold OK / X: replay line   hold ↓ / Y: known   Back: close")
        ctl.atSceneEnd -> tr("OK: next scene   ↑: replay line   ↓: pick a word   ←: previous")
        else -> null
    }
    if (hint != null && hints) {
        Box(Modifier.fillMaxSize().padding(top = 110.dp), contentAlignment = Alignment.TopCenter) {
            Text(hint, color = Colors.text, fontSize = 17.sp,
                modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 10.dp))
        }
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
            Modifier.padding(48.dp).width(520.dp).background(Colors.surface, RoundedCornerShape(16.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(tr("Options"), fontSize = 26.sp, color = Colors.text)
            Button(onClick = ctl::togglePauseAtSceneEnd, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                Text(tr("At the end of a scene: %s", if (ctl.pauseAtSceneEnd) tr("pause") else tr("play on")))
            }
            Button(onClick = ctl::toggleSlow, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Speed: %s", if (ctl.slow) "0.75x" else tr("normal")))
            }
            Button(onClick = { ctl.changeSubtitles(ctl.subtitles.next()) }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Subtitles: %s", ctl.subtitles.label(ctl.lang)))
            }
            Button(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text(tr("Help: the buttons")) }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(tr("Close")) }
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}
