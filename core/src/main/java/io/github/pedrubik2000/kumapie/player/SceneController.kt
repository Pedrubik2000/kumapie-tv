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
import io.github.pedrubik2000.kumapie.data.Lang
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
    val lang: Lang = Lang.of(episode.lang)

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
    /** Primed Listening: a new scene waits on its start (all its lines shown while paused) until play. */
    var pauseAtSceneStart by mutableStateOf(settings.pauseAtSceneStart)
        private set
    /** Skip nothing: the gaps between scenes play too and scenes don't wait on their start; Pause at scene end still pauses (user, 2026-10-14). */
    var newStraight by mutableStateOf(settings.newStraight)
        private set
    /** i+0 scenes (no red words) don't pause at their start or end (user, 2026-10-14). */
    var playThroughKnown by mutableStateOf(settings.playThroughKnown)
        private set
    var slow by mutableStateOf(settings.slow)
        private set
    /** What the subtitles show. Stays as set from scene to scene and is remembered for next time. */
    var subtitles by mutableStateOf(settings.subtitles)
        private set
    /** The episode's language is on screen (blurred or readable). */
    val showTarget: Boolean get() = subtitles == Subtitles.BLURRED || subtitles == Subtitles.TARGET || subtitles == Subtitles.BOTH
    /** It is there but blurred: you see that (and how much) is said, not what. */
    val blurTarget: Boolean get() = subtitles == Subtitles.BLURRED
    val showEnglish: Boolean get() = subtitles == Subtitles.ENGLISH || subtitles == Subtitles.BOTH

    /** The top bar (show, scene n/N, level) stays up until this uptime (ms), and always while paused. */
    var bannerUntil by mutableLongStateOf(0L)
        private set

    private var stopAt: Double? = null
    private var stopIsSceneEnd = false
    private val seenNew = mutableSetOf<String>()
    /** Scenes played to their end since the episode opened (seenNew is emptied by each report). */
    private val seenHere = mutableSetOf<String>()

    /** Seen before (any device) or since the episode opened: a novel's Skip goes through these. */
    fun wasSeen(s: Scene) = s.seen || s.id in seenHere
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
        if (!paused && !pausesAtStart(scene)) return playScene(index)
        atSceneEnd = false
        seek(scene.start)
        showBanner()
    }

    /** Scene [i] from its start; a different scene with Primed Listening on waits there (replaying one plays). */
    fun playScene(i: Int) {
        if (scenes.isEmpty()) return
        val other = i.coerceIn(0, scenes.lastIndex) != index
        index = i.coerceIn(0, scenes.lastIndex)
        atSceneEnd = false
        seek(scene.start)
        if (other && pausesAtStart(scene)) {
            player.pause()
            playing = false
        } else {
            stopAtSceneEnd()
            player.play()
            playing = true
        }
        showBanner()
    }

    fun nextScene() = playScene(index + 1)
    /** Back in the first scene goes to 00:00, not the scene's start. */
    fun previousScene() { if (index > 0) playScene(index - 1) else { playScene(0); seek(0.0) } }
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
        playing = true
    }

    fun togglePlay() {
        when {
            playing -> player.pause()
            atSceneEnd && newStraight && !episode.novel && index < scenes.lastIndex -> { // play on through the gap into the next scene
                atSceneEnd = false; stopAt = null; player.play(); playing = true
            }
            atSceneEnd -> if (index < scenes.lastIndex) nextScene() else replayScene()
            else -> { stopAtSceneEnd(); player.play(); playing = true }
        }
        showBanner()
    }

    fun pause() {
        player.pause()
        playing = false
        showBanner()
    }

    /** The scene's level, live: a rating or marking a word known lowers it ([io.github.pedrubik2000.kumapie.data.levelOf]). */
    fun levelOf(scene: Scene): Int? = io.github.pedrubik2000.kumapie.data.levelOf(scene, words)

    /** Remote ↓: hidden → German blurred → German → German + English → hidden. */
    fun cycleSubtitles() = changeSubtitles(when (subtitles) {
        Subtitles.HIDDEN -> Subtitles.BLURRED
        Subtitles.BLURRED -> Subtitles.TARGET
        Subtitles.TARGET -> Subtitles.BOTH
        else -> Subtitles.HIDDEN
    })

    /** Gamepad L: blurred German becomes readable; otherwise German on / off. */
    fun toggleTarget() = changeSubtitles(
        if (blurTarget) Subtitles.TARGET else Subtitles.of(target = !showTarget, english = showEnglish))
    fun toggleEnglish() = changeSubtitles(Subtitles.of(target = showTarget, english = !showEnglish))

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

    fun toggleNewStraight() {
        newStraight = !newStraight
        settings.newStraight = newStraight
        if (player.isPlaying) stopAtSceneEnd()
        showBanner()
    }

    fun togglePlayThroughKnown() {
        playThroughKnown = !playThroughKnown
        settings.playThroughKnown = playThroughKnown
        if (player.isPlaying) stopAtSceneEnd()
        showBanner()
    }

    fun togglePauseAtSceneStart() {
        pauseAtSceneStart = !pauseAtSceneStart
        settings.pauseAtSceneStart = pauseAtSceneStart
        showBanner()
    }

    fun changeSubtitles(value: Subtitles) {
        subtitles = value
        settings.subtitles = value
    }

    /** The player's position right now, for drawing every frame (tick() only updates [position] every 40 ms). */
    fun livePosition(): Double {
        // Audio only, ExoPlayer moves currentPosition in ~250 ms steps: run it on from the clock between steps.
        val p = player.currentPosition / 1000.0
        val now = SystemClock.uptimeMillis()
        // Restarted while not playing: after a seek the clock runs from when the audio starts, not from the seek
        // (counting the load skipped a line's 0.2 s expression fade).
        if (p != clockPos || !player.isPlaying) { clockPos = p; clockAt = now }
        return clockPos + (now - clockAt) / 1000.0 * player.playbackParameters.speed
    }
    private var clockPos = -1.0
    private var clockAt = 0L

    fun showBanner(ms: Long = 3_000) { bannerUntil = SystemClock.uptimeMillis() + ms }

    // ------------------------------------------------------------ every frame or so

    fun tick() {
        val now = SystemClock.uptimeMillis()
        position = player.currentPosition / 1000.0
        // Loading counts as playing: a scene still buffering after a seek must not show paused (readable) subtitles.
        playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        if (playing) watchedMs += now - lastTick
        lastTick = now
        if (scenes.isEmpty()) return

        if (playing && position >= scene.end) { seenNew += scene.id; seenHere += scene.id }
        stopAt?.let { stop ->
            if (playing && position >= stop) {
                player.pause()
                playing = false
                atSceneEnd = stopIsSceneEnd
                stopAt = null
                if (atSceneEnd) { seenNew += scene.id; seenHere += scene.id }
            }
        }
        // After the stop: a next scene starting at (or within a tick of) this one's end moved on first and replaced
        // this scene's end pause, so Skip nothing played through (2026-10-15). Paused at the end: stay on this scene.
        if (!atSceneEnd && (!pausesAtEnd(scene) || (newStraight && !episode.novel))) { // playing on: follow the video into the next scene
            val i = scenes.indexOfLast { it.start <= position }
            // Scenes often overlap by ~0.2 s (their padding): move on only once this one has ended, unless its end
            // doesn't pause anyway; moving on early dropped every end pause (2026-10-15, 74 of 133 in an episode).
            if (i > index && (position >= scene.end || !pausesAtEnd(scene))) {
                index = i
                if (pausesAtStart(scene) && playing) { // wait on the new scene's start to read it
                    player.pause()
                    playing = false
                    seek(scene.start)
                } else {
                    // Its own end may pause; set even while loading (a streamed episode), or it played on through.
                    stopAtSceneEnd()
                }
                showBanner()
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

    // A novel waits after every line until a tap, like the game: the modes don't apply.
    private fun pausesAtStart(s: Scene) = !episode.novel && pauseAtSceneStart && !newStraight && !known(s)
    private fun pausesAtEnd(s: Scene) = episode.novel || (pauseAtSceneEnd && !known(s))
    private fun known(s: Scene) = playThroughKnown && levelOf(s) == 0

    private fun stopAtSceneEnd() {
        if (pausesAtEnd(scene)) {
            stopAt = scene.end
            stopIsSceneEnd = true
        } else {
            stopAt = null
        }
    }
}
