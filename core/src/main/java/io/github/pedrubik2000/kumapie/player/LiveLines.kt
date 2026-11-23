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
class LiveLines(
    private val player: ExoPlayer,
    /** Jimaku's next files for the episode (1: the second best…), tried when the first never fits ([OTHERS_AFTER]). */
    private val others: suspend (Int) -> List<Triple<Double, Double, String>> = { emptyList() },
    /** The file that fitted instead of the first: keep it for next time. */
    private val switched: (List<Triple<Double, Double, String>>) -> Unit = {},
    private val jimaku: suspend () -> List<Triple<Double, Double, String>>,
) : Player.Listener {
    var line by mutableStateOf<String?>(null)
        private set

    /** "file" (its own track), "jimaku", "" (not known yet / none). */
    var source by mutableStateOf("")
        private set
    var offset by mutableDoubleStateOf(0.0)

    /** The [offset] it started from (saved last time): the fit replaces it, as it does 0; a hand nudge doesn't. */
    var guess: Double? = null

    /** The offset [fit] found from the file's other subtitle track (null: not yet / no track); a hand nudge after it wins. */
    var fitted by mutableStateOf<Double?>(null)
        private set

    var pieces by mutableStateOf(emptyList<Pair<Double, Double?>>())
        private set

    var unfitted by mutableStateOf(false)
        private set
    private var otherCut = false
    private var pieced = 0

    /** No Japanese lines at all: none in the file, none on Jimaku. */
    var noLines by mutableStateOf(false)
        private set
    private var needJimaku = false
    private var starts = emptyList<Double>() // Jimaku's line starts

    /** Jimaku's lines once they came ([start, end, text], its own timing: [offset] not applied). */
    var lines by mutableStateOf(emptyList<Triple<Double, Double, String>>())
        private set
    /** The file has a subtitle track whose line starts [fit] can use; without one, waiting for a fit is pointless. */
    var canFit by mutableStateOf(false)
        private set
    var english by mutableStateOf(emptyList<io.github.pedrubik2000.kumapie.data.Jimaku.Line>())
        private set
    var englishTrack by mutableStateOf(false)
        private set
    var englishOpen = emptySet<Int>()
        private set
    private val refs = ArrayList<Double>() // when the file's other track showed a line (seconds of video)
    val refCount get() = refs.size
    private var shown = emptyList<androidx.media3.common.text.Cue>() // the last group: a line ending under another isn't a new one
    private var tried = 0 // refs.size [fit] last ran on
    private var triedOthers = false

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
        canFit = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
        dialogue(tracks)?.let { (g, i) -> if (!g.isTrackSelected(i)) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build() }
        android.util.Log.i("LiveLines", "text tracks: " + tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.joinToString { g ->
            (0 until g.length).joinToString { "${g.getTrackFormat(it).language} '${g.getTrackFormat(it).label.orEmpty()}'" } } +
            "; fit listens to " + dialogue(tracks)?.let { (g, i) -> "'${g.getTrackFormat(i).label.orEmpty()}'" })
        englishTrack = needJimaku && dialogue(tracks)?.let { (g, i) -> g.getTrackFormat(i).let { f -> f.language.orEmpty().startsWith("en") || f.label.orEmpty().contains("english", true) } } == true
        tracksKnown = true
        if (stopWhenKnown && !canFit) stop()
    }

    /** The file's tracks were read ([canFit] means something from then on). */
    var tracksKnown = false
        private set
    private var stopWhenKnown = false

    /** The episode was made: stop, unless the file has a track to fit to (the fit goes on in [pieces]; tracks not read yet: decided then). */
    fun stopUnlessFitting() {
        if (!tracksKnown) stopWhenKnown = true
        else if (!canFit) stop()
    }

    override fun onCues(cueGroup: CueGroup) {
        // Either track's line starts are the video's own clock: Jimaku's file is fitted to them ([fit]).
        if (cueGroup.cues.any { it !in shown }) refs += player.currentPosition / 1000.0
        shown = cueGroup.cues
        if (englishTrack) {
            val texts = cueGroup.cues.map { io.github.pedrubik2000.kumapie.data.Jimaku.notes(io.github.pedrubik2000.kumapie.data.Jimaku.plain(it.text?.toString().orEmpty())) }
                .filter { it.isNotEmpty() }.distinct()
            val (grown, open) = grow(english, englishOpen, texts, player.currentPosition / 1000.0)
            if (grown != english) english = grown
            englishOpen = open
        }
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
    suspend fun run() = kotlinx.coroutines.coroutineScope {
        // Asked at once and handed on as soon as they come: the words need only them, not the file's tracks (waiting for
        // those first was ~1.4 s of a stream's words, tablet 2026-11-18).
        val got = runCatching { jimaku() }.getOrDefault(emptyList())
        if (got.isNotEmpty()) { starts = got.map { it.first }; lines = got }
        while (source.isEmpty() && !needJimaku) delay(50)
        if (got.isEmpty()) { noLines = needJimaku; return@coroutineScope }
        if (needJimaku) source = "jimaku"
        while (true) {
            if (fitted == null && refs.size >= MIN_REFS && refs.size % 2 == 0 && refs.size != tried) {
                tried = refs.size
                fit(refs, starts)?.let { if (offset == 0.0 || offset == guess) offset = it; fitted = it; android.util.Log.i("LiveLines", "fitted $it s from ${refs.size} lines") }
            }
            // The first file never fits (another script, or a loose one): the next ranked files against the lines heard so
            // far; one that fits replaces it (its new [lines] remake the episode's words).
            if (fitted == null && !triedOthers && refs.size >= OTHERS_AFTER) {
                triedOthers = true
                for (i in 1..3) {
                    val other = runCatching { others(i) }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() } ?: break
                    val f = fit(refs.toList(), other.map { it.first }) ?: continue
                    android.util.Log.i("LiveLines", "file ${i + 1} fitted $f s from ${refs.size} lines (the first didn't)")
                    starts = other.map { it.first }
                    lines = other
                    if (offset == 0.0 || offset == guess) offset = f
                    fitted = f
                    switched(other)
                    break
                }
            }
            if (fitted != null && refs.size >= pieced + PIECE_EVERY) {
                pieced = refs.size
                val heard = refs.toList()
                val got = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { pieces(heard, starts) }
                if (got == null) otherCut = true else pieces = got
            }
            val at = player.currentPosition / 1000.0
            unfitted = otherCut || pieces.lastOrNull { it.first <= at }?.let { it.second == null } == true
            if (source == "jimaku") {
                val t = at - offset
                line = lines.lastOrNull { it.first <= t }?.takeIf { t < it.second }?.third
            }
            delay(100)
        }
    }

    companion object {
        /**
         * The text track with the dialogue (Japanese first): a dual-audio release's default English track is often
         * "Signs/Song" only, which shows a line every few minutes, so the fit never got its [MIN_REFS] lines (Space
         * Dandy [commie]: "English Signs/Song" selected, "English Subs" not, tablet 2026-11-19).
         */
        fun dialogue(tracks: Tracks): Pair<Tracks.Group, Int>? = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { g -> (0 until g.length).map { g to it } }
            .filter { (g, i) -> g.getTrackFormat(i).let { f -> f.selectionFlags and C.SELECTION_FLAG_FORCED == 0 && !notDialogue(f.label.orEmpty()) } }
            .maxByOrNull { (g, i) -> if (g.getTrackFormat(i).language.orEmpty().startsWith("ja")) 1 else 0 }

        /** A track label that says signs, songs or forced only ("English Signs/Song [commie]", "Forced"). */
        fun notDialogue(label: String) = Regex("""sign|song|forced|karaoke|\bop\b|\bed\b""",RegexOption.IGNORE_CASE).containsMatchIn(label)

        fun grow(cues: List<io.github.pedrubik2000.kumapie.data.Jimaku.Line>, open: Set<Int>, texts: List<String>, now: Double):
            Pair<List<io.github.pedrubik2000.kumapie.data.Jimaku.Line>, Set<Int>> {
            val out = cues.toMutableList()
            val still = HashSet<Int>()
            for (i in open) {
                val c = out[i]
                if (c.text in texts) still += i else if (now > c.start) out[i] = c.copy(end = minOf(c.end, now))
            }
            for (t in texts) {
                if (still.any { out[it].text == t }) continue
                val j = out.indexOfLast { it.text == t && now >= it.start - 3 && now <= it.end + 1 }
                if (j >= 0) out[j] = out[j].copy(start = minOf(out[j].start, now), end = maxOf(out[j].end, now + 5))
                else out += io.github.pedrubik2000.kumapie.data.Jimaku.Line(now, now + 5, t)
                still += if (j >= 0) j else out.lastIndex
            }
            return out to still
        }

        private const val MIN_REFS = 8
        private const val OTHERS_AFTER = 30
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
            val near = { c: Int -> fitted.minBy { kotlin.math.abs(it.first - c) }.second!! }
            val gap = { t: Double -> japanese.minOf { kotlin.math.abs(it - t) } }
            // The chunk holding the opening has both amounts in it and fits one: a line over 0.5 s off a Japanese one by
            // its chunk's offset takes a neighbour chunk's when that puts it nearer.
            return other.map { t ->
                val c = (t / CHUNK).toInt()
                val own = near(c)
                t - (if (gap(t - own) > 0.5) listOf(own, near(c - 1), near(c + 1)).minBy { gap(t - it) } else own)
            }
        }

        /**
         * One offset for Jimaku's line [starts] (seconds) that puts the most of them on the other track's line starts
         * [refs]: every pair within 180 s proposes its difference (0.1 s steps); a proposal counts the refs it puts within
         * 0.15 s of a Jimaku line. The winner needs 6 of them, half of [refs], and 1.5 times any other offset's (over 0.3 s
         * away). Null when nothing agrees (no match, another script, or a loose one: Eden of the East 2's Coalgirls
         * Japanese file, where 5 smeared votes from 8 lines fitted noise as 2.8, 16.8, 26.1 s, tablet 2026-11-19; on
         * that episode's English track and Netflix file: 1 wrong in 1200 tries, against 2 % and 0-74 % on Coalgirls). 180 s, not
         * 60: Coalgirls' file is a good one, 80.1 s off this release (an opening it doesn't have), fitted 292 of 360 tries.
         * Half of what can land: of [refs], or of the Jimaku lines in their stretch when those are fewer (an English
         * track that splits lines 2:1, Dekinai; core plan 11b).
         * [pieces]: [fit] per [PIECE] s of [refs] (seconds of video): (from, offset) for each stretch with its own offset, the first
         * from 0; a change lands between the two refs that split the lines best between the two offsets (an inserted or
         * removed block, an OP of another length). Offset null: the stretch after the last fit doesn't fit (yet). Null:
         * another cut (fewer stretches fit than not, over [PIECES] offsets, or over [SPAN] s apart: Rakugo's TV-cut file
         * on the Blu-ray, +12 s then -352 ... -1968 s).
         * ponytail: at most [PIECES] offsets [SPAN] s apart, by [PIECE] s windows; a file of another cut is "not fitted".
         */
        fun fit(refs: List<Double>, starts: List<Double>): Double? {
            if (refs.isEmpty()) return null
            val proposed = HashMap<Long, Int>() // offset (0.1 s) -> refs proposing it
            for (r in refs) starts.filter { kotlin.math.abs(r - it) <= 180 }.map { Math.round((r - it) * 10) }.toSet()
                .forEach { proposed.merge(it, 1, Int::plus) }
            fun hits(bin: Long) = refs.count { r -> starts.any { kotlin.math.abs(r - it - bin / 10.0) <= 0.15 } }
            val scored = proposed.entries.sortedByDescending { it.value }.take(40).map { it.key to hits(it.key) }.sortedByDescending { it.second }
            val (bin, n) = scored.firstOrNull() ?: return null
            val other = scored.filter { kotlin.math.abs(it.first - bin) > 3 }.maxOfOrNull { it.second } ?: 0
            val (lo, hi) = refs.min() - bin / 10.0 - 1 to refs.max() - bin / 10.0 + 1
            val possible = minOf(refs.size, starts.count { it in lo..hi })
            return if (n >= 6 && n * 2 >= possible && n * 2 >= other * 3) bin / 10.0 else null
        }

        fun pieces(refs: List<Double>, starts: List<Double>): List<Pair<Double, Double?>>? {
            val chunks = refs.sorted().groupBy { (it / PIECE).toInt() }.values.filter { it.size >= MIN_REFS }
                .map { c -> c to fit(c, starts.filter { it in c.first() - 200..c.last() + 200 }) }
            val fitted = chunks.count { it.second != null }
            if (fitted == 0) return emptyList()
            if (fitted * 2 < chunks.size) return null
            val out = ArrayList<Pair<Double, Double?>>()
            var last = -1
            for ((i, c) in chunks.withIndex()) {
                val o = c.second ?: continue
                val prev = out.lastOrNull()?.second
                if (prev == null) out += 0.0 to o
                else if (kotlin.math.abs(o - prev) > 0.3) out += split(chunks.subList(last, i + 1).flatMap { it.first }, starts, prev, o) to o
                last = i
            }
            if (last < chunks.size - 1) out += chunks[last + 1].first.first() to null
            val offsets = out.mapNotNull { it.second }
            return out.takeIf { offsets.size <= PIECES && offsets.max() - offsets.min() <= SPAN }
        }

        private fun split(refs: List<Double>, starts: List<Double>, a: Double, b: Double): Double {
            fun on(r: Double, o: Double) = starts.any { kotlin.math.abs(r - it - o) <= 0.15 }
            val before = refs.runningFold(0) { n, r -> n + if (on(r, a)) 1 else 0 }
            val after = refs.reversed().runningFold(0) { n, r -> n + if (on(r, b)) 1 else 0 }.reversed()
            val k = (1 until refs.size).maxByOrNull { before[it] + after[it] } ?: return refs.first()
            return (refs[k - 1] + refs[k]) / 2
        }

        private const val PIECE = 240.0
        private const val PIECE_EVERY = 20
        private const val PIECES = 4
        private const val SPAN = 300.0
    }

    fun stop() {
        player.removeListener(this)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        line = null
    }
}
