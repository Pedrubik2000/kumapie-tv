package io.github.pedrubik2000.kumapie.mobile.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch
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
import androidx.media3.common.Player
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
import io.github.pedrubik2000.kumapie.i18n.tr

/** Loads the episode (from the PC, or its download), then plays it scene by scene, full screen in landscape. */
@Composable
fun PlayerScreen(library: Library, show: Show, episode: Episode, startAt: Double? = null, onBack: () -> Unit) {
    var upright by remember { mutableStateOf(library.settings.upright) }
    // Upright: the same player (one video for the episode), held upright, swipe up/down for the scenes.
    key(upright) { FullScreen(if (upright) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) }
    var detail by remember { mutableStateOf<EpisodeDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val scopeShift = rememberCoroutineScope()
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
                    Button(onClick = { attempt++ }) { Text(tr("Try again")) }
                    TextButton(onClick = onBack) { Text(tr("Back")) }
                }
            }
            d == null -> Text("${show.title} · ${episode.title}", color = Colors.dim, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center))
            else -> key(d) {
                ScenePlayer(library, library.settings, library.backend(d.lang), d, startPaused = startAt != null, onBack,
                    onUpright = { upright = !upright; library.settings.upright = upright }, uprightNow = upright,
                    // Subtitle timing (episodes made here): moves every line, then reloads the episode where it was.
                    onShift = if (!io.github.pedrubik2000.kumapie.mobile.local.LocalEpisodes.isLocal(d.id)) null else ({ secs, at ->
                        library.local.shift(d.id, secs)
                        detail = null
                        scopeShift.launch {
                            runCatching { library.episode(episode.id) }.onSuccess { detail = it.copy(resume = at) }
                        }
                    }))
            }
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
    /** Shifts the subtitles by (seconds), then reloads at (position); null when the episode isn't made here. */
    onShift: ((Double, Double) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(episode.video))
            prepare()
        }
    }
    val ctl = remember { SceneController(episode, player, settings, backend) }
    // A novel's music: its own player, so it goes on while a line waits for the tap.
    val bgm = remember { if (!episode.novel) null else ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE; volume = 0.4f } }
    val track = if (bgm == null) null else ctl.scene.bgm
    LaunchedEffect(track) {
        if (bgm == null) return@LaunchedEffect
        if (track == null) bgm.stop() else { bgm.setMediaItem(MediaItem.fromUri(track)); bgm.prepare(); bgm.play() }
    }
    val picker = remember { WordPicker(ctl, backend, scope) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    var options by remember { mutableStateOf(false) }
    var sceneList by remember { mutableStateOf(false) }
    var mining by remember { mutableStateOf(false) }
    // Each word's last rating from the word card ("Answered: Good", Undo), kept while the episode is open.
    val ratings: Ratings = remember { androidx.compose.runtime.mutableStateMapOf() }
    // kuma3's whole queue read again after a rating, in the background (big collections: seconds); not cancelled with the word card.
    val queueScope = rememberCoroutineScope()
    val queueBusy = remember { mutableStateOf(false) } // while it runs, no rating (it would read a queue from before)
    // Leaving a scene you rated in: kuma3's queue read once (RWKV-Instant may bring a card back). Not after each rating:
    // the read selects decks, which replaces kuma3's Undo, so ratings stay undoable while you're in their scene.
    LaunchedEffect(ctl.scene.index) {
        if (ratings.isEmpty()) return@LaunchedEffect
        ratings.clear()
        val known = library.languages.of(ctl.lang).known
        queueBusy.value = true
        queueScope.launch {
            try { known.refreshDue(force = true); repaintWords(ctl, known) } finally { queueBusy.value = false }
        }
    }
    var menuUntil by remember { mutableLongStateOf(SystemClock.uptimeMillis() + 3_000) } // a novel's bar
    var flash by remember { mutableStateOf<String?>(null) } // "Replay line" etc., shown briefly in the middle
    var flashAt by remember { mutableLongStateOf(0L) }
    fun flash(text: String) { flash = text; flashAt = SystemClock.uptimeMillis() }

    DisposableEffect(Unit) {
        onDispose {
            ctl.tick()
            ctl.report()
            picker.release()
            player.release()
            bgm?.release()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && bgm != null && bgm.mediaItemCount > 0) bgm.play()
            if (event == Lifecycle.Event.ON_STOP) {
                bgm?.pause()
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
    // Japanese: the dictionary popup's WebView and Sudachi ready before the first tap.
    LaunchedEffect(Unit) {
        if (ctl.lang == io.github.pedrubik2000.kumapie.data.Lang.JAPANESE) {
            prewarmPopup(context)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { library.languages.japanese.model.parse(listOf("準備")) }
            }
        }
    }
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
        // A novel in landscape: the picture dims under the text, like the original game's text window (not during the
        // transition into the line, nor upright, where the text is below the picture).
        if (episode.novel) {
            val texted = !uprightNow && (ctl.showTarget || ctl.showEnglish) &&
                ctl.scene.cues.firstOrNull()?.let { ctl.position >= it.start - 0.05 } == true
            val dim by animateFloatAsState(if (texted) 0.5f else 0f, tween(250), label = "dim")
            if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }

        // The gestures on the video. Subtitles and the card sit above and take their own taps.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p ->
                            when {
                                picker.isOpen -> picker.close()
                                ctl.subtitles == SubtitleMode.HIDDEN && p.y > size.height * 0.65f -> ctl.cycleSubtitles()
                                // A novel: a tap is always the next line, cutting the voice, like the game.
                                episode.novel -> if (ctl.index < ctl.scenes.lastIndex) ctl.nextScene()
                                else -> ctl.togglePlay()
                            }
                        },
                        // Not in a novel: fast taps through narration would replay instead (and wait for a second tap).
                        onDoubleTap = if (episode.novel) null else ({ p ->
                            picker.close()
                            if (p.x < size.width / 2) { ctl.replayLine(); flash(tr("↺ line")) } else { ctl.replayScene(); flash(tr("↺ scene")) }
                        }),
                        onLongPress = if (!episode.novel) null else ({ menuUntil = SystemClock.uptimeMillis() + 4_000 }),
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

        // A novel never shows the play button; its bar only when opened or on a long press.
        val barVisible = picker.isOpen || if (episode.novel) now < menuUntil else !ctl.playing || now < ctl.bannerUntil
        if (barVisible) TopBar(ctl, onBack = onBack, onOptions = { options = true }, onScenes = { sceneList = true })
        if (!ctl.playing && !picker.isOpen && !episode.novel) {
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
            // With imported dictionaries the popup below has the meanings: no second, episode-made meaning above it.
            val dictionaries = remember { library.yomitan.of(ctl.lang).any { d -> d.enabled && d.terms > 0 } }
            MeaningCard(ctl, picker, it, maxWidth = 460.dp, onTapDef = picker::tapInDef, ownMeaning = !dictionaries, footer = {
                DictionaryPanel(library, ctl, picker)
                RatingRow(library, ctl, picker, ratings, queueScope, queueBusy)
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
            Options(ctl, if (onUpright == null) null else ({ options = false; onUpright() }), uprightNow,
                onShift?.let { f -> { secs: Double -> options = false; ctl.report(); f(secs, ctl.position) } },
                onShift?.let { library.local.shifted(episode.id) })
        }
    }
    if (sceneList) {
        ModalBottomSheet(onDismissRequest = { sceneList = false }) {
            SceneList(ctl, onPick = { i -> sceneList = false; picker.close(); ctl.playScene(i) })
        }
    }
    if (mining) {
        ModalBottomSheet(onDismissRequest = { mining = false }) { MineSheet(library, episode, ctl, picker, onDone = { mining = false }, ratings, queueScope, queueBusy) }
    }
}

/** Back, show and episode, scene n/N with its level, the modes, and ⋮. */
@Composable
private fun TopBar(ctl: SceneController, onBack: () -> Unit, onOptions: () -> Unit, onScenes: () -> Unit) {
    val scene = ctl.scene
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xE0000000), Color(0x80000000), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 6.dp).padding(bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), tint = Colors.text) }
        Text("${ctl.episode.show} · ${ctl.episode.title}", color = Colors.text, fontSize = 15.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val modes = listOfNotNull(if (ctl.newStraight) tr("new: straight") else null,
            if (ctl.pauseAtSceneStart) tr("primed") else null, if (ctl.pauseAtSceneEnd) null else tr("plays on"),
            if (ctl.slow) "0.75x" else null)
        if (modes.isNotEmpty()) Text(modes.joinToString(" · "), color = Colors.dim, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp))
        // One scene alone (feed): its place in the episode.
        // Tap: the list of scenes, to jump to one.
        Text(if (ctl.scenes.size == 1) tr("scene %d", scene.index + 1) else "${scene.index + 1} / ${ctl.scenes.size}",
            color = Colors.text, fontSize = 15.sp, modifier = if (ctl.scenes.size > 1)
                Modifier.clickable(onClick = onScenes).padding(horizontal = 6.dp, vertical = 8.dp) else Modifier)
        LevelBadge(ctl.levelOf(scene), Modifier.padding(start = 10.dp))
        IconButton(onClick = onOptions) { Icon(Icons.Default.MoreVert, tr("Options"), tint = Colors.text) }
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

/** Every scene: number, start time, first line, level, ✓ when seen; the one playing highlighted. Tap = play it. */
@Composable
private fun SceneList(ctl: SceneController, onPick: (Int) -> Unit) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (ctl.index - 3).coerceAtLeast(0))
    androidx.compose.foundation.lazy.LazyColumn(state = list, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        items(ctl.scenes.size) { i ->
            val sc = ctl.scenes[i]
            val start = sc.start.toInt()
            Row(Modifier.fillMaxWidth().clickable { onPick(i) }
                .background(if (i == ctl.index) Color(0x33FFFFFF) else Color.Transparent)
                .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Colors.dim, fontSize = 14.sp, modifier = Modifier.width(36.dp))
                Text("%d:%02d".format(start / 60, start % 60), color = Colors.dim, fontSize = 14.sp, modifier = Modifier.width(52.dp))
                Text(sc.cues.firstOrNull()?.text ?: "", fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (sc.seen) Icon(Icons.Default.Check, tr("Seen"), tint = Colors.dim, modifier = Modifier.padding(horizontal = 6.dp).size(16.dp))
                LevelBadge(ctl.levelOf(sc))
            }
        }
    }
}

/** Under the meaning: hear the word, hear the definition, replay the line, mark known. */
@Composable
private fun CardButtons(ctl: SceneController, picker: WordPicker, onMine: () -> Unit) {
    val word = (picker.selectedInDef ?: picker.selected)?.word
    val marked = word?.let { ctl.words[it]?.marked } == true
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = picker::hearWord) { Icon(Icons.AutoMirrored.Filled.VolumeUp, tr("Hear the word"), tint = Colors.text) }
        if (picker.definition != null) {
            IconButton(onClick = picker::hearDefinition) { Icon(Icons.Default.RecordVoiceOver, tr("Hear the definition"), tint = Colors.text) }
        }
        IconButton(onClick = picker::replayLine) { Icon(Icons.Default.Replay, tr("Replay the line"), tint = Colors.text) }
        IconButton(onClick = onMine) { Icon(Icons.Default.BookmarkAdd, tr("Add to Anki"), tint = Colors.text) }
        Spacer(Modifier.weight(1f, fill = false).width(8.dp))
        FilterChip(selected = marked, onClick = picker::toggleKnown, label = { Text(if (marked) tr("Known") else tr("Mark known")) },
            leadingIcon = if (marked) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null)
    }
}

/** The player's modes, saved on the device; and a reminder of the gestures. */
@Composable
private fun Options(ctl: SceneController, onUpright: (() -> Unit)? = null, uprightNow: Boolean = false,
                    onShift: ((Double) -> Unit)? = null, shifted: Double? = null) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()
        .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OptionRow(tr("Primed Listening: pause at the start of each scene (read, then play)"), ctl.pauseAtSceneStart, ctl::togglePauseAtSceneStart)
        OptionRow(tr("Pause at the end of each scene"), ctl.pauseAtSceneEnd, ctl::togglePauseAtSceneEnd)
        OptionRow(tr("Scenes I haven't seen: play straight through (no pauses, nothing skipped)"), ctl.newStraight, ctl::toggleNewStraight)
        OptionRow(tr("Slow (0.75x)"), ctl.slow, ctl::toggleSlow)
        if (onUpright != null) OptionRow(tr("Upright: scene by scene, swipe up"), uprightNow, onUpright)
        if (onShift != null) {
            Text(tr("Subtitle timing") + (shifted?.takeIf { it != 0.0 }?.let { tr(" (moved %+.1f s so far)").format(java.util.Locale.ROOT, it) } ?: ""),
                color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(-1.0, -0.5, -0.1, 0.1, 0.5, 1.0).forEach { s ->
                    FilterChip(selected = false, onClick = { onShift(s) }, label = { Text("%+.1f s".format(java.util.Locale.ROOT, s), fontSize = 12.sp) })
                }
            }
            Text(tr("− shows the subtitles earlier, + later. Every line of the episode moves."), color = Colors.dim, fontSize = 12.sp)
        }
        Text(tr("Subtitles"), color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(SubtitleMode.HIDDEN, SubtitleMode.BLURRED, SubtitleMode.TARGET, SubtitleMode.BOTH).forEach { m ->
                FilterChip(selected = ctl.subtitles == m, onClick = { ctl.changeSubtitles(m) }, label = { Text(m.label(ctl.lang), fontSize = 12.sp) })
            }
        }
        Text(
            tr("Tap the scene number at the top: all scenes, to jump to one · tap the video: play / pause · swipe ← →: next / previous scene · double-tap left: replay the line, " +
                "right: the scene · tap the subtitles: next mode (hidden: tap the bottom of the screen) · " +
                "tap a word: its meaning, then tap the words of the German definition too."),
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

/**
 * The word card's rating rows ([RatingRows]): German and English one, the exact form (without a card: what the mine
 * sheet picks at first); Japanese up to three ([io.github.pedrubik2000.kumapie.lang.JapaneseLookup.forms]).
 */
@Composable
private fun RatingRow(library: io.github.pedrubik2000.kumapie.mobile.offline.Library, ctl: SceneController, picker: WordPicker,
                      ratings: Ratings, queueScope: kotlinx.coroutines.CoroutineScope, queueBusy: androidx.compose.runtime.MutableState<Boolean>) {
    val segment = picker.selectedInDef ?: picker.selected
    val key = segment?.word ?: return
    val language = library.languages.of(ctl.lang)
    val line = picker.line
    val forms = if (language is io.github.pedrubik2000.kumapie.lang.Japanese) {
        val cue = ctl.scene.cues.getOrNull(line)
        val offset = cue?.segments?.take(picker.seg)?.sumOf { it.text.length } ?: 0
        val found by androidx.compose.runtime.produceState(emptyList<io.github.pedrubik2000.kumapie.lang.JapaneseLookup.Form>(), cue?.text, offset) {
            value = if (cue == null || picker.selectedInDef != null) emptyList() else runCatching { language.forms(cue.text, offset) }
                .onFailure { android.util.Log.w("kumapie", "word card forms: $it") }.getOrDefault(emptyList())
        }
        japaneseRateForms(library, found, io.github.pedrubik2000.kumapie.lang.SensePick.english(ctl.scene, line)) { w, p ->
            library.miner.mine(io.github.pedrubik2000.kumapie.lang.Miner.Request(ctl.episode, ctl.scene, line, w), p)
        }
    } else listOf(RateForm(key, null, if (picker.selectedInDef != null) null else ({ p -> // a word in a definition has no line
        val request = autoWordCard(library, ctl, line, segment.text, key)
        library.miner.mine(request, p)
    })))
    RatingRows(language.known, library.settings.prefs, forms, ratings, queueScope, queueBusy) { repaintWords(ctl, language.known) }
}
