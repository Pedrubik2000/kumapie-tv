package io.github.pedrubik2000.kumapie.player

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import io.github.pedrubik2000.kumapie.data.Backend
import io.github.pedrubik2000.kumapie.data.Cue
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Scene
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Subtitles
import io.github.pedrubik2000.kumapie.data.Word
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Outlives screens: the last progress report still goes out after the player closes. */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * The scene-by-scene logic on top of a player that plays the whole episode: which scene is on, where to stop
 * (end of the scene or of a replayed line), what the subtitles show, and progress for the server.
 * `tick()` runs every few frames; everything else is an action from a button.
 */
class SceneController(
    val episode: EpisodeDetail,
    private val player: Player,
    private val settings: Settings,
    private val api: Backend,
) {
    val scenes: List<Scene> = episode.scenes

    /** Every word's status and meaning; changes when a word is marked known in the picker. */
    val words: SnapshotStateMap<String, Word> = mutableStateMapOf<String, Word>().apply { putAll(episode.words) }

    var index by mutableIntStateOf(startIndex())
        private set
    val scene: Scene get() = scenes[index]

    /** Seconds into the episode. */
    var position by mutableDoubleStateOf(scenes.firstOrNull()?.start ?: 0.0)
        private set
    var playing by mutableStateOf(false)
        private set
    /** Paused because the scene ended (OK then plays the next one). */
    var atSceneEnd by mutableStateOf(false)
        private set

    var pauseAtSceneEnd by mutableStateOf(settings.pauseAtSceneEnd)
        private set
    var slow by mutableStateOf(settings.slow)
        private set
    /** What the subtitles show. Stays as set from scene to scene and is remembered for next time. */
    var subtitles by mutableStateOf(settings.subtitles)
        private set
    /** German is on screen (blurred or readable). */
    val showGerman: Boolean get() = subtitles == Subtitles.BLURRED || subtitles == Subtitles.GERMAN || subtitles == Subtitles.BOTH
    /** German is there but blurred: you see that (and how much) is said, not what. */
    val blurGerman: Boolean get() = subtitles == Subtitles.BLURRED
    val showEnglish: Boolean get() = subtitles == Subtitles.ENGLISH || subtitles == Subtitles.BOTH

    /** The top bar (show, scene n/N, level) stays up until this uptime (ms), and always while paused. */
    var bannerUntil by mutableLongStateOf(0L)
        private set

    private var stopAt: Double? = null
    private var stopIsSceneEnd = false
    private val seenNew = mutableSetOf<String>()
    /** The position last sent to the server, so a paused player doesn't send the same one every 20 s. */
    private var reportedPos = episode.resume
    private var watchedMs = 0L
    private var lastTick = SystemClock.uptimeMillis()
    private var lastReport = SystemClock.uptimeMillis()

    init {
        player.setPlaybackSpeed(if (slow) 0.75f else 1f)
    }

    /** The scene the episode was left in (the next one if that scene was finished), else the first. */
    private fun startIndex(): Int {
        val resume = episode.resume ?: return 0
        val i = scenes.indexOfLast { it.start <= resume + 0.05 }
        if (i < 0) return 0
        return if (resume >= scenes[i].end - 0.2 && i + 1 < scenes.size) i + 1 else i
    }

    // ------------------------------------------------------------ actions

    /** Start the episode: the resume scene, paused or playing as the mode says; [paused]: on its start, not playing. */
    fun begin(paused: Boolean = false) {
        if (scenes.isEmpty()) return
        if (!paused) return playScene(index)
        atSceneEnd = false
        seek(scene.start)
        showBanner()
    }

    fun playScene(i: Int) {
        if (scenes.isEmpty()) return
        index = i.coerceIn(0, scenes.lastIndex)
        atSceneEnd = false
        seek(scene.start)
        stopAtSceneEnd()
        player.play()
        showBanner()
    }

    fun nextScene() = playScene(index + 1)
    fun previousScene() = playScene(index - 1)
    fun replayScene() = playScene(index)

    /** The line being said (or the last one said) again, then pause. */
    fun replayLine() {
        val cue = currentCue() ?: return replayScene()
        replayCue(scene.cues.indexOf(cue))
    }

    /** Line [i] of the scene again, then pause. */
    fun replayCue(i: Int) {
        val cue = scene.cues.getOrNull(i) ?: return
        atSceneEnd = false
        seek((cue.start - 0.15).coerceAtLeast(scene.start))
        stopAt = minOf(cue.end + 0.25, scene.end)
        stopIsSceneEnd = stopAt == scene.end
        player.play()
    }

    fun togglePlay() {
        when {
            player.isPlaying -> player.pause()
            atSceneEnd -> if (index < scenes.lastIndex) nextScene() else replayScene()
            else -> { stopAtSceneEnd(); player.play() }
        }
        showBanner()
    }

    fun pause() {
        player.pause()
        playing = false
        showBanner()
    }

    /** Words in the scene never studied (as the server counts, but live: marking a word known lowers it). */
    fun levelOf(scene: Scene): Int? =
        if (!scene.german) null
        else scene.cues.flatMap { c -> c.segments.mapNotNull { it.word } }.toSet().count { (words[it]?.status ?: "u") == "u" }

    /** Remote ↓: hidden → German blurred → German → German + English → hidden. */
    fun cycleSubtitles() = changeSubtitles(when (subtitles) {
        Subtitles.HIDDEN -> Subtitles.BLURRED
        Subtitles.BLURRED -> Subtitles.GERMAN
        Subtitles.GERMAN -> Subtitles.BOTH
        else -> Subtitles.HIDDEN
    })

    /** Gamepad L: blurred German becomes readable; otherwise German on / off. */
    fun toggleGerman() = changeSubtitles(
        if (blurGerman) Subtitles.GERMAN else Subtitles.of(german = !showGerman, english = showEnglish))
    fun toggleEnglish() = changeSubtitles(Subtitles.of(german = showGerman, english = !showEnglish))

    fun toggleSlow() {
        slow = !slow
        settings.slow = slow
        player.setPlaybackSpeed(if (slow) 0.75f else 1f)
        showBanner()
    }

    fun togglePauseAtSceneEnd() {
        pauseAtSceneEnd = !pauseAtSceneEnd
        settings.pauseAtSceneEnd = pauseAtSceneEnd
        if (player.isPlaying) stopAtSceneEnd()
        showBanner()
    }

    fun changeSubtitles(value: Subtitles) {
        subtitles = value
        settings.subtitles = value
    }

    fun showBanner(ms: Long = 3_000) { bannerUntil = SystemClock.uptimeMillis() + ms }

    // ------------------------------------------------------------ every frame or so

    fun tick() {
        val now = SystemClock.uptimeMillis()
        position = player.currentPosition / 1000.0
        playing = player.isPlaying
        if (playing) watchedMs += now - lastTick
        lastTick = now
        if (scenes.isEmpty()) return

        if (playing && position >= scene.end) seenNew += scene.id
        if (!pauseAtSceneEnd) { // playing on: follow the video into the next scene
            val i = scenes.indexOfLast { it.start <= position }
            if (i > index) {
                index = i
                showBanner()
            }
        }
        stopAt?.let { stop ->
            if (playing && position >= stop) {
                player.pause()
                playing = false
                atSceneEnd = stopIsSceneEnd
                stopAt = null
                if (atSceneEnd) seenNew += scene.id
            }
        }
        if (now - lastReport > 20_000) report()
    }

    /** The cue being said; between cues the last one said; before the first, the first. */
    fun currentCue(): Cue? {
        val cues = scene.cues
        if (cues.isEmpty()) return null
        return cues.lastOrNull { it.start <= position + 0.05 } ?: cues.first()
    }

    /** Sends where the episode is, new scenes seen and time watched. Also called when the player closes. */
    fun report() {
        lastReport = SystemClock.uptimeMillis()
        val seen = seenNew.toList()
        val watched = watchedMs / 1000.0
        val pos = position
        if (seen.isEmpty() && watched < 1 && reportedPos == pos) return
        reportedPos = pos
        seenNew.clear()
        watchedMs = 0
        AppScope.launch { runCatching { api.progress(episode.id, pos, seen, watched) } }
    }

    // ------------------------------------------------------------ helpers

    private fun seek(seconds: Double) {
        player.seekTo((seconds * 1000).toLong())
        position = seconds
    }

    private fun stopAtSceneEnd() {
        if (pauseAtSceneEnd) {
            stopAt = scene.end
            stopIsSceneEnd = true
        } else {
            stopAt = null
        }
    }
}
