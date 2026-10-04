package io.github.pedrubik2000.kumapie.player

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.LineDef
import io.github.pedrubik2000.kumapie.data.Segment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The word picker: a cursor on the words of the paused scene. It lands only on words that are red (never
 * studied) or orange (learning), unless the scene has none; then on every word. Landing on a word plays its
 * audio; resting on it for a moment counts as a lookup (sent to the server).
 *
 * ↑ moves the cursor into the word's German definition (its words can be picked too), ↓ back to the line.
 * OK plays the word again; a second OK right after plays the whole German definition.
 */
class WordPicker(
    private val ctl: SceneController,
    private val api: Api,
    private val scope: CoroutineScope,
) {
    /** A word's place in the scene: line (cue) and segment. */
    data class Spot(val line: Int, val seg: Int)

    var isOpen by mutableStateOf(false)
        private set
    var line by mutableIntStateOf(0)
        private set
    var seg by mutableIntStateOf(0)
        private set
    /** The cursor is inside the selected word's German definition, on segment [defSeg]. */
    var inDef by mutableStateOf(false)
        private set
    var defSeg by mutableIntStateOf(0)
        private set
    private var lastOk = 0L

    private val audio = WordAudio()
    private var lookupJob: Job? = null

    val selected: Segment? get() = ctl.scene.cues.getOrNull(line)?.segments?.getOrNull(seg)

    /** The selected word's definition in this line, if written. */
    val definition: LineDef? get() = selected?.word?.let { ctl.scene.def(line, it) }

    /** The word picked inside the definition (only while [inDef]). */
    val selectedInDef: Segment? get() = if (inDef) definition?.german?.getOrNull(defSeg) else null

    /** Pauses and puts the cursor on the first red word of the line being said (else orange, else any). */
    fun open(): Boolean {
        val spots = spots()
        if (spots.isEmpty()) return false
        ctl.pause()
        val cueIndex = ctl.scene.cues.indexOf(ctl.currentCue()).coerceAtLeast(0)
        val inLine = spots.filter { it.line == cueIndex }
        val start = inLine.firstOrNull { status(it) == "u" } ?: inLine.firstOrNull { status(it) == "l" }
            ?: spots.firstOrNull { it.line >= cueIndex } ?: spots.first()
        isOpen = true
        land(start)
        return true
    }

    fun close() {
        isOpen = false
        inDef = false
        lookupJob?.cancel()
    }

    fun right() = if (inDef) stepInDef(+1) else step(+1)
    fun left() = if (inDef) stepInDef(-1) else step(-1)

    /** ↓: out of the definition, else the first word on the next line that has one. */
    fun down() { if (inDef) inDef = false else changeLine(+1) }

    /** ↑: into the selected word's German definition, else the line above. */
    fun up() {
        if (inDef) return
        val spots = defSpots()
        if (spots.isNotEmpty()) {
            inDef = true
            defSeg = spots.first()
            selectedInDef?.let { audio.play(api.wordAudio(it.text)) }
        } else {
            changeLine(-1)
        }
    }

    /** OK: the word again; OK twice in a row (on a line word): its German definition read aloud. */
    fun hearAgain() {
        val now = android.os.SystemClock.uptimeMillis()
        val def = definition
        val word = selected?.word
        if (!inDef && def != null && word != null && now - lastOk < 1_500) {
            audio.play(api.definitionAudio(ctl.scene.id, line, word))
            lastOk = 0L
            return
        }
        lastOk = now
        (selectedInDef ?: selected)?.let { audio.play(api.wordAudio(it.text)) }
    }

    /** The selected word's line again; the picker stays open. */
    fun replayLine() = ctl.replayCue(line)

    /** Marks the selected word known (no card needed), or undoes it. */
    fun toggleKnown() {
        val word = (selectedInDef ?: selected)?.word ?: return
        val now = ctl.words[word] ?: return
        val mark = !now.marked
        scope.launch {
            runCatching { api.markKnown(word, mark) }.onSuccess { s ->
                ctl.words[word] = now.copy(status = s, marked = mark)
            }
        }
    }

    fun release() = audio.release()

    // ------------------------------------------------------------ helpers

    private fun status(spot: Spot): String? =
        ctl.scene.cues[spot.line].segments[spot.seg].word?.let { ctl.words[it]?.status ?: "u" }

    /** The words the cursor can land on, in reading order (always including where it is now). */
    private fun spots(): List<Spot> {
        val all = ctl.scene.cues.flatMapIndexed { l, cue ->
            cue.segments.mapIndexedNotNull { s, segment -> if (segment.word != null) Spot(l, s) else null }
        }
        val here = Spot(line, seg)
        val wanted = all.filter { status(it) != "k" || (isOpen && it == here) }
        return wanted.ifEmpty { all }
    }

    private fun step(by: Int) {
        val spots = spots()
        val i = spots.indexOf(Spot(line, seg))
        spots.getOrNull(if (i < 0) 0 else i + by)?.let(::land)
    }

    private fun changeLine(by: Int) {
        val spots = spots()
        val lines = spots.map { it.line }.distinct()
        val target = lines.getOrNull(lines.indexOf(line) + by) ?: return
        land(spots.first { it.line == target })
    }

    private fun defSpots(): List<Int> =
        definition?.german?.mapIndexedNotNull { i, s -> if (s.word != null) i else null } ?: emptyList()

    private fun stepInDef(by: Int) {
        val spots = defSpots()
        val i = spots.indexOf(defSeg)
        spots.getOrNull(if (i < 0) 0 else i + by)?.let {
            defSeg = it
            selectedInDef?.let { s -> audio.play(api.wordAudio(s.text)) }
        }
    }

    private fun land(spot: Spot) {
        inDef = false
        line = spot.line
        seg = spot.seg
        val segment = selected ?: return
        audio.play(api.wordAudio(segment.text))
        lookupJob?.cancel()
        val word = segment.word ?: return
        val scene = ctl.scene.id
        lookupJob = scope.launch {
            delay(1_200) // resting on it, not passing through
            runCatching { api.lookup(word, scene) }.onSuccess { n ->
                ctl.words[word]?.let { ctl.words[word] = it.copy(lookups = n) }
            }
        }
    }
}

/** Plays one short clip at a time (a new one stops the last). */
class WordAudio {
    private var player: MediaPlayer? = null

    fun play(url: String) {
        release()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setOnPreparedListener { it.start() }
            setOnErrorListener { _, _, _ -> true }
            runCatching {
                setDataSource(url)
                prepareAsync()
            }
        }
    }

    fun release() {
        player?.release()
        player = null
    }
}
