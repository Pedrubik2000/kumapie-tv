package io.github.pedrubik2000.kumapie.ui

import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
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
import io.github.pedrubik2000.kumapie.data.Cue
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.player.Action
import io.github.pedrubik2000.kumapie.player.KeyHandler
import io.github.pedrubik2000.kumapie.player.KeyMap
import io.github.pedrubik2000.kumapie.player.SceneController
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
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(episode.video))
            prepare()
        }
    }
    val ctl = remember { SceneController(episode, player, settings, api) }
    var options by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keys = remember {
        KeyHandler(KeyMap()) { action ->
            when (action) {
                Action.PLAY_PAUSE -> ctl.togglePlay()
                Action.NEXT_SCENE -> ctl.nextScene()
                Action.PREVIOUS_SCENE -> ctl.previousScene()
                Action.REPLAY_LINE -> ctl.replayLine()
                Action.REPLAY_SCENE -> ctl.replayScene()
                Action.CYCLE_SUBTITLES -> ctl.cycleSubtitles()
                Action.TOGGLE_GERMAN -> ctl.toggleGerman()
                Action.TOGGLE_ENGLISH -> ctl.toggleEnglish()
                Action.SLOW -> ctl.toggleSlow()
                Action.PAUSE_AT_SCENE_END -> ctl.togglePauseAtSceneEnd()
                Action.OPTIONS -> options = true
                Action.WORD_PICKER -> Toast.makeText(context, "The word picker comes in the next version", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        ctl.begin()
        onDispose {
            ctl.tick()
            ctl.report()
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
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.uptimeMillis(); delay(250) } }
    BackHandler(onBack = onClose)

    Box(
        Modifier.fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { !options && keys.handle(it.nativeKeyEvent) }
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
        if (!ctl.playing || now < ctl.bannerUntil) TopBar(ctl)
        Subtitles(ctl, Modifier)
        if (options) PlayerOptions(ctl, onClose = { options = false; focus.requestFocus() })
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Show, scene n/N with its level, and the modes. */
@Composable
private fun TopBar(ctl: SceneController) {
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
            if (ctl.pauseAtSceneEnd) "pauses at scene end" else "plays on",
            if (ctl.slow) "0.75x" else null,
        ).joinToString(" · ")
        Text(modes, color = Colors.text.copy(alpha = 0.8f), fontSize = 16.sp, modifier = Modifier.padding(end = 24.dp))
        Text("Scene ${scene.index + 1} / ${ctl.scenes.size}", color = Colors.text, fontSize = 20.sp)
        LevelBadge(scene.level, Modifier.padding(start = 16.dp))
    }
    if (ctl.atSceneEnd) {
        Box(Modifier.fillMaxSize().padding(top = 110.dp), contentAlignment = Alignment.TopCenter) {
            Text("OK: next scene   ↑: replay line   ←: previous", color = Colors.text, fontSize = 18.sp,
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

/** Subtitle sizes to try, largest first; English is set at ENGLISH_SCALE of the German. */
private val SIZES = listOf(34, 31, 28, 25, 22, 20, 18)
private const val ENGLISH_SCALE = 0.72f

/**
 * While playing: the line being said. Paused: every line of the scene (the current one bright), then the
 * English as one block. The largest size that fits is used: a third of the screen while playing, two thirds
 * while paused (the video is stopped then), the smallest size if nothing fits.
 * Unknown words red, learning words orange.
 */
@Composable
private fun Subtitles(ctl: SceneController, modifier: Modifier) {
    if (!ctl.showGerman && !ctl.showEnglish) return
    val scene = ctl.scene
    val current = ctl.currentCue()
    val pos = ctl.position
    val german: List<Cue> = when {
        !ctl.showGerman -> emptyList()
        !ctl.playing -> scene.cues
        current != null && pos <= current.end + 0.6 -> listOf(current)
        else -> emptyList()
    }
    val english = when {
        !ctl.showEnglish -> emptyList()
        !ctl.playing -> scene.english
        else -> scene.english.filter { it.start - 0.1 <= pos && pos <= it.end + 0.6 }.takeLast(1)
    }
    if (german.isEmpty() && english.isEmpty()) return
    val germanText = buildAnnotatedString {
        german.forEachIndexed { i, cue ->
            if (i > 0) append('\n')
            append(colored(cue, ctl.episode.words, dim = !ctl.playing && cue != current))
        }
    }
    val englishText = english.joinToString(" ") { it.text }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padH = 28.dp
        val padV = 14.dp
        val bottom = 40.dp
        val boxWidth = minOf(maxWidth - 64.dp, 900.dp)
        val budget = (if (ctl.playing) maxHeight / 3 else maxHeight * 2 / 3) - bottom - padV * 2
        val (textWidth, budgetPx) = with(density) { (boxWidth - padH * 2).roundToPx() to budget.toPx() }
        fun germanStyle(size: Int) = TextStyle(fontSize = size.sp, lineHeight = (size * 1.22f).sp, textAlign = TextAlign.Center)
        fun englishStyle(size: Int) = TextStyle(fontSize = (size * ENGLISH_SCALE).sp,
            lineHeight = (size * ENGLISH_SCALE * 1.22f).sp, textAlign = TextAlign.Center)
        val size = SIZES.firstOrNull { s ->
            val limits = Constraints(maxWidth = textWidth)
            val g = if (germanText.isEmpty()) 0 else measurer.measure(germanText, germanStyle(s), constraints = limits).size.height
            val e = if (englishText.isEmpty()) 0 else measurer.measure(englishText, englishStyle(s), constraints = limits).size.height
            g + e + with(density) { 8.dp.toPx() } <= budgetPx
        } ?: SIZES.last()
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottom).widthIn(max = boxWidth)
                .background(Color(0xB3000000), RoundedCornerShape(12.dp)).padding(horizontal = padH, vertical = padV),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (germanText.isNotEmpty()) {
                Text(germanText, style = germanStyle(size), color = Colors.text,
                    modifier = if (ctl.blurGerman) Modifier.blur(10.dp, BlurredEdgeTreatment.Unbounded) else Modifier)
            }
            if (englishText.isNotEmpty()) {
                Text(englishText, style = englishStyle(size), color = Colors.dim,
                    modifier = Modifier.padding(top = if (germanText.isEmpty()) 0.dp else 8.dp))
            }
        }
    }
}

private fun colored(cue: Cue, words: Map<String, Word>, dim: Boolean): AnnotatedString = buildAnnotatedString {
    val base = if (dim) Colors.dim else Colors.text
    for (seg in cue.segments) {
        val color = when (seg.word?.let { words[it]?.status }) {
            "u" -> if (dim) Colors.unknown.copy(alpha = 0.7f) else Colors.unknown
            "l" -> if (dim) Colors.learning.copy(alpha = 0.7f) else Colors.learning
            else -> base
        }
        withStyle(SpanStyle(color = color)) { append(seg.text) }
    }
}

/** The player's modes, saved on the TV. Back or Close returns to the video. */
@Composable
private fun PlayerOptions(ctl: SceneController, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).onBackKey(onBack = onClose), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.padding(48.dp).width(520.dp).background(Colors.surface, RoundedCornerShape(16.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Options", fontSize = 26.sp, color = Colors.text)
            Button(onClick = ctl::togglePauseAtSceneEnd, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                Text("At the end of a scene: " + if (ctl.pauseAtSceneEnd) "pause" else "play on")
            }
            Button(onClick = ctl::toggleSlow, modifier = Modifier.fillMaxWidth()) {
                Text("Speed: " + if (ctl.slow) "0.75x" else "normal")
            }
            Button(onClick = { ctl.changeSubtitles(ctl.subtitles.next()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Subtitles: " + ctl.subtitles.label)
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}
