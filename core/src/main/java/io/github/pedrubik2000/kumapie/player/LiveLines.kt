package io.github.pedrubik2000.kumapie.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * A Discover stream's Japanese lines while its episode is still being made (SubMiner's way: one line at a time, as it
 * plays, nothing prepared): the file's own Japanese text track as ExoPlayer reads it, else a Jimaku file's lines
 * ([jimaku]: [[start, end, text]] from the PC) shown by the player's clock, [offset] seconds moved (it may be timed for
 * another release). [line] is the one on screen now. [stop] before the player goes on with the episode's scenes.
 */
class LiveLines(private val player: ExoPlayer, private val jimaku: suspend () -> List<Triple<Double, Double, String>>) : Player.Listener {
    var line by mutableStateOf<String?>(null)
        private set

    /** "file" (its own track), "jimaku", "" (not known yet / none). */
    var source by mutableStateOf("")
        private set
    var offset by mutableDoubleStateOf(0.0)

    /** The offset [fit] found from the file's other subtitle track (null: not yet / no track); a hand nudge after it wins. */
    var fitted by mutableStateOf<Double?>(null)
        private set

    /** No Japanese lines at all: none in the file, none on Jimaku. */
    var noLines by mutableStateOf(false)
        private set
    private var needJimaku = false
    private var starts = emptyList<Double>() // Jimaku's line starts

    /** Jimaku's lines once they came ([start, end, text], its own timing: [offset] not applied). */
    var lines by mutableStateOf(emptyList<Triple<Double, Double, String>>())
        private set
    private val refs = ArrayList<Double>() // when the file's other track showed a line (seconds of video)
    private var shown = emptyList<androidx.media3.common.text.Cue>() // the last group: a line ending under another isn't a new one
    private var tried = 0 // refs.size [fit] last ran on

    init {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguages("ja", "en").setSelectUndeterminedTextLanguage(true).build()
        player.addListener(this)
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (tracks.isEmpty || source.isNotEmpty() || needJimaku) return
        val ja = tracks.groups.any { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).language.orEmpty().startsWith("ja") }
        }
        // No Japanese track: Jimaku's lines, and the file's other text track (English…) stays on, never shown (players hide
        // ExoPlayer's subtitle view): its cue times are the video's own clock, to fit Jimaku's file to ([fit]).
        if (ja) source = "file" else needJimaku = true
    }

    override fun onCues(cueGroup: CueGroup) {
        // Either track's line starts are the video's own clock: Jimaku's file is fitted to them ([fit]).
        if (fitted == null && cueGroup.cues.any { it !in shown }) refs += player.currentPosition / 1000.0
        shown = cueGroup.cues
        if (needJimaku) return
        // Netflix's Japanese track is closed captions: （モモ）, （男子生徒の話し声）, ♪ go, as in a Jimaku file. Line by line,
        // so a two-line cue keeps its break.
        if (source == "file") line = cueGroup.cues.flatMap { it.text?.toString().orEmpty().lines() }
            .map(io.github.pedrubik2000.kumapie.data.Jimaku::spoken).filter { it.isNotEmpty() }.joinToString("\n").ifBlank { null }
    }

    /**
     * Jimaku's lines, once the tracks are known; runs until cancelled. Without a Japanese track they are the lines
     * shown, by the clock. With one (Netflix-style) the file's own lines stay on screen and Jimaku's only make the
     * episode ([lines]): a track's lines only come as it plays, the words need them all.
     */
    suspend fun run() {
        while (source.isEmpty() && !needJimaku) delay(200)
        val got = runCatching { jimaku() }.getOrDefault(emptyList())
        if (got.isEmpty()) { noLines = needJimaku; return }
        if (needJimaku) source = "jimaku"
        starts = got.map { it.first }
        lines = got
        while (true) {
            if (fitted == null && refs.size >= MIN_REFS && refs.size % 2 == 0 && refs.size != tried) {
                tried = refs.size
                fit(refs, starts)?.let { if (offset == 0.0) offset = it; fitted = it; android.util.Log.i("LiveLines", "fitted $it s from ${refs.size} lines") }
            }
            if (source == "jimaku") {
                val t = player.currentPosition / 1000.0 - offset
                line = got.lastOrNull { it.first <= t }?.takeIf { t < it.second }?.third
            }
            delay(100)
        }
    }

    companion object {
        private const val MIN_REFS = 8
        private const val CHUNK = 180.0

        /**
         * Another file's line starts ([other], seconds) moved onto the [japanese] ones: [fit] per [CHUNK] s, as two
         * releases' files often differ by one amount before the opening and another after it (AoT 1: -0.8 s, then
         * -2.8 s). A chunk that doesn't fit takes the nearest one's. Null when under half the chunks fit (another
         * episode, another cut).
         */
        fun place(japanese: List<Double>, other: List<Double>): List<Double>? {
            if (other.isEmpty()) return null
            val chunks = (0..(other.max() / CHUNK).toInt()).mapNotNull { c ->
                val t0 = c * CHUNK
                val part = other.filter { it >= t0 && it < t0 + CHUNK }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                c to fit(part, japanese.filter { it >= t0 - 60 && it < t0 + CHUNK + 60 })
            }
            val fitted = chunks.filter { it.second != null }
            if (fitted.size * 2 < chunks.size) return null
            return other.map { t -> val c = (t / CHUNK).toInt(); t - fitted.minBy { kotlin.math.abs(it.first - c) }.second!! }
        }

        /**
         * One offset for Jimaku's line [starts] (seconds) that puts the most of them on the other track's line starts
         * [refs]: every pair within 60 s votes for its difference (0.1 s steps, a step either side counts too); the
         * winner needs 5 votes and a third of [refs]. Null when nothing agrees (no match, or another script).
         * ponytail: one offset for the whole episode; a release with a different cut (recap, OP) needs one per part.
         */
        fun fit(refs: List<Double>, starts: List<Double>): Double? {
            val votes = HashMap<Long, Int>()
            for (r in refs) for (j in starts) if (kotlin.math.abs(r - j) <= 60) {
                val bin = Math.round((r - j) * 10)
                for (b in bin - 1..bin + 1) votes.merge(b, 1, Int::plus)
            }
            val (bin, n) = votes.maxByOrNull { it.value } ?: return null
            return if (n >= 5 && n * 3 >= refs.size) bin / 10.0 else null
        }
    }

    fun stop() {
        player.removeListener(this)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        line = null
    }
}
