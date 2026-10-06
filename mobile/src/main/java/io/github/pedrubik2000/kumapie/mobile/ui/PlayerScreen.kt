package io.github.pedrubik2000.kumapie.mobile.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.pedrubik2000.kumapie.data.Backend
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.Subtitles as SubtitleMode
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.ui.MeaningCard
import io.github.pedrubik2000.kumapie.ui.SubtitleTap
import io.github.pedrubik2000.kumapie.ui.Subtitles
import kotlinx.coroutines.delay

/** Loads the episode (from the PC, or its download), then plays it scene by scene, full screen in landscape. */
@Composable
fun PlayerScreen(library: Library, show: Show, episode: Episode, startAt: Double? = null, onBack: () -> Unit) {
    var upright by remember { mutableStateOf(library.settings.upright) }
    // Upright: the same player (one video for the episode), held upright, swipe up/down for the scenes.
    key(upright) { FullScreen(if (upright) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) }
    var detail by remember { mutableStateOf<EpisodeDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { library.episode(episode.id) }.onSuccess { detail = if (startAt != null) it.copy(resume = startAt + 0.05) else it }.onFailure { error = it.message ?: it.toString() }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val d = detail
        when {
            error != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error ?: "", color = Colors.text)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { attempt++ }) { Text("Try again") }
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }
            d == null -> Text("${show.title} · ${episode.title}", color = Colors.dim, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center))
            else -> ScenePlayer(library, library.settings, library.backend(), d, startPaused = startAt != null, onBack,
                onUpright = { upright = !upright; library.settings.upright = upright }, uprightNow = upright)
        }
    }
}

/** Landscape, no system bars, screen kept on, while the player is open. */
@Composable
private fun FullScreenLandscape() = FullScreen(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

/** No system bars and the screen kept on, in [orientation] (landscape player, upright feed). */
@Composable
internal fun FullScreen(orientation: Int) {
    val activity = LocalContext.current as Activity
    val view = LocalView.current
    DisposableEffect(Unit) {
        activity.requestedOrientation = orientation
        val bars = WindowCompat.getInsetsController(activity.window, view)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            bars.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}

/**
 * Touch: tap the video = play/pause (at a scene end: next scene); swipe left/right = next/previous scene;
 * double-tap the left half = replay the line, the right half = replay the scene. Subtitles: a tap moves to the
 * next mode (hidden → blurred → German → German + English → hidden; while hidden, tap the bottom of the
 * screen); once German is readable a tap on a word opens its card. ⋮ = options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScenePlayer(
    library: Library,
    settings: Settings,
    backend: Backend,
    episode: EpisodeDetail,
    startPaused: Boolean,
    onBack: () -> Unit,
    /** Switches between landscape and upright (null: no switch, e.g. in the feed). */
    onUpright: (() -> Unit)? = null,
    uprightNow: Boolean = false,
    /** Subtitles for this player only, not saved (unlock screen: German + English). */
    subtitles: io.github.pedrubik2000.kumapie.data.Subtitles? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(episode.video))
            prepare()
        }
    }
    val ctl = remember { SceneController(episode, player, settings, backend, subtitles) }
    val picker = remember { WordPicker(ctl, backend, scope) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    var options by remember { mutableStateOf(false) }
    var mining by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<String?>(null) } // "Replay line" etc., shown briefly in the middle
    var flashAt by remember { mutableLongStateOf(0L) }
    fun flash(text: String) { flash = text; flashAt = SystemClock.uptimeMillis() }

    DisposableEffect(Unit) {
        onDispose {
            ctl.tick()
            ctl.report()
            picker.release()
            player.release()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                ctl.tick()
                ctl.report()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        ctl.begin(paused = startPaused) // opened from the i+1 list: wait on the scene, to mine from it
        while (true) {
            ctl.tick()
            delay(40)
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.uptimeMillis(); delay(250) } }
    BackHandler { if (picker.isOpen) picker.close() else onBack() }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // The gestures on the video. Subtitles and the card sit above and take their own taps.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p ->
                            when {
                                picker.isOpen -> picker.close()
                                ctl.subtitles == SubtitleMode.HIDDEN && p.y > size.height * 0.65f -> ctl.cycleSubtitles()
                                else -> ctl.togglePlay()
                            }
                        },
                        onDoubleTap = { p ->
                            picker.close()
                            if (p.x < size.width / 2) { ctl.replayLine(); flash("↺ line") } else { ctl.replayScene(); flash("↺ scene") }
                        },
                    )
                }
                .pointerInput(uprightNow) {
                    // Landscape: swipe left = next scene. Upright: swipe up = next. Same player, the video seeks.
                    var d = 0f
                    val end = {
                        val min = 60.dp.toPx()
                        if (d < -min) { picker.close(); ctl.nextScene() }
                        if (d > min) { picker.close(); ctl.previousScene() }
                    }
                    if (uprightNow) {
                        detectVerticalDragGestures(onDragStart = { d = 0f }, onDragEnd = end) { _, amount -> d += amount }
                    } else {
                        detectHorizontalDragGestures(onDragStart = { d = 0f }, onDragEnd = end) { _, amount -> d += amount }
                    }
                },
        )

        val barVisible = !ctl.playing || now < ctl.bannerUntil || picker.isOpen
        if (barVisible) TopBar(ctl, onBack = onBack, onOptions = { options = true })
        if (!ctl.playing && !picker.isOpen) {
            Box(Modifier.align(Alignment.Center).size(64.dp).background(Color(0x66000000), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Colors.text, modifier = Modifier.size(40.dp))
            }
        }

        Subtitles(ctl, picker, onAnchor = { anchor = it }, onTap = { tap ->
            when (tap) {
                is SubtitleTap.Word -> picker.tap(tap.line, tap.seg)
                SubtitleTap.Box -> if (picker.isOpen) picker.close() else ctl.cycleSubtitles()
            }
        })
        if (picker.isOpen && picker.cardOpen) anchor?.let {
            MeaningCard(ctl, picker, it, maxWidth = 460.dp, onTapDef = picker::tapInDef, footer = {
                DictionaryPanel(library, ctl, picker)
                CardButtons(ctl, picker, onMine = { mining = true })
            })
        }

        if (flash != null && now - flashAt < 900) {
            Text(flash ?: "", color = Colors.text, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center)
                .background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 8.dp))
        }
    }

    if (options) {
        ModalBottomSheet(onDismissRequest = { options = false }) {
            Options(ctl, if (onUpright == null) null else ({ options = false; onUpright() }), uprightNow)
        }
    }
    if (mining) {
        ModalBottomSheet(onDismissRequest = { mining = false }) { MineSheet(library, episode, ctl, picker, onDone = { mining = false }) }
    }
}

/** Back, show and episode, scene n/N with its level, the modes, and ⋮. */
@Composable
private fun TopBar(ctl: SceneController, onBack: () -> Unit, onOptions: () -> Unit) {
    val scene = ctl.scene
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xE0000000), Color(0x80000000), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 6.dp).padding(bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Colors.text) }
        Text("${ctl.episode.show} · ${ctl.episode.title}", color = Colors.text, fontSize = 15.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val modes = listOfNotNull(if (ctl.pauseAtSceneEnd) null else "plays on", if (ctl.slow) "0.75x" else null)
        if (modes.isNotEmpty()) Text(modes.joinToString(" · "), color = Colors.dim, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp))
        // One scene alone (feed, unlock): its place in the episode.
        Text(if (ctl.scenes.size == 1) "scene ${scene.index + 1}" else "${scene.index + 1} / ${ctl.scenes.size}",
            color = Colors.text, fontSize = 15.sp)
        LevelBadge(ctl.levelOf(scene), Modifier.padding(start = 10.dp))
        IconButton(onClick = onOptions) { Icon(Icons.Default.MoreVert, "Options", tint = Colors.text) }
    }
}

@Composable
private fun LevelBadge(level: Int?, modifier: Modifier = Modifier) {
    val (text, color) = when {
        level == null -> "♪" to Colors.dim
        level == 0 -> "i+0" to Colors.levelZero
        level == 1 -> "i+1" to Colors.levelOne
        else -> "i+$level" to Colors.unknown
    }
    Text(text, color = Color.Black, fontSize = 14.sp,
        modifier = modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 1.dp))
}

/** Under the meaning: hear the word, hear the definition, replay the line, mark known. */
@Composable
private fun CardButtons(ctl: SceneController, picker: WordPicker, onMine: () -> Unit) {
    val word = (picker.selectedInDef ?: picker.selected)?.word
    val marked = word?.let { ctl.words[it]?.marked } == true
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = picker::hearWord) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Hear the word", tint = Colors.text) }
        if (picker.definition != null) {
            IconButton(onClick = picker::hearDefinition) { Icon(Icons.Default.RecordVoiceOver, "Hear the definition", tint = Colors.text) }
        }
        IconButton(onClick = picker::replayLine) { Icon(Icons.Default.Replay, "Replay the line", tint = Colors.text) }
        IconButton(onClick = onMine) { Icon(Icons.Default.BookmarkAdd, "Add to Anki", tint = Colors.text) }
        Spacer(Modifier.weight(1f, fill = false).width(8.dp))
        FilterChip(selected = marked, onClick = picker::toggleKnown, label = { Text(if (marked) "Known" else "Mark known") },
            leadingIcon = if (marked) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null)
    }
}

/** The player's modes, saved on the device; and a reminder of the gestures. */
@Composable
private fun Options(ctl: SceneController, onUpright: (() -> Unit)? = null, uprightNow: Boolean = false) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()
        .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OptionRow("Pause at the end of each scene", ctl.pauseAtSceneEnd, ctl::togglePauseAtSceneEnd)
        OptionRow("Slow (0.75x)", ctl.slow, ctl::toggleSlow)
        if (onUpright != null) OptionRow("Upright: scene by scene, swipe up", uprightNow, onUpright)
        Text("Subtitles", color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(SubtitleMode.HIDDEN, SubtitleMode.BLURRED, SubtitleMode.GERMAN, SubtitleMode.BOTH).forEach { m ->
                FilterChip(selected = ctl.subtitles == m, onClick = { ctl.changeSubtitles(m) }, label = { Text(m.label, fontSize = 12.sp) })
            }
        }
        Text(
            "Tap the video: play / pause · swipe ← →: next / previous scene · double-tap left: replay the line, " +
                "right: the scene · tap the subtitles: next mode (hidden: tap the bottom of the screen) · " +
                "tap a word: its meaning, then tap the words of the German definition too.",
            color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun OptionRow(label: String, on: Boolean, toggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { toggle() })
    }
}
