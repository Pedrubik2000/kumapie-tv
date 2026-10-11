package io.github.pedrubik2000.kumapie.mobile.local

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Is a synced subtitle file right? alass gives no score, so this finds the shift (within ±3 s) that puts the most line
 * time on the reference (the video's English subtitles, or the speech the VAD heard): about 0 = in sync. Also per
 * 3-minute part, for a sync that is wrong in one part only. Calibrated on the PC (kumapie_plan.md item 30,
 * C:\dojo\work\alass\score.py): in sync = 0.0..0.2 s; 2 s off found as 1.8..2.0 s; a window off = over 0.5 s with
 * 2 %+ more line time on the reference; the last window is skipped on speech (the ending song fools the VAD).
 */
object SyncCheck {
    class Result(val shift: Double, val off: List<Pair<Double, Double>>) {
        val ok get() = abs(shift) <= LIMIT && off.isEmpty()
    }

    const val LIMIT = 0.5
    private const val WINDOW = 180.0
    private const val MIN_GAIN = 0.02

    /** [lines] and [reference] as (start, end) in seconds; [speech]: the reference is VAD speech, not subtitles. */
    fun check(lines: List<Pair<Double, Double>>, reference: List<Pair<Double, Double>>, speech: Boolean): Result {
        val ref = merged(reference)
        val sorted = lines.sortedBy { it.first }
        if (sorted.isEmpty() || ref.isEmpty()) return Result(0.0, emptyList())
        val end = sorted.maxOf { it.second }
        val windows = generateSequence(0.0) { it + WINDOW }.takeWhile { it < end }.toList()
        val off = windows.filterIndexed { i, t -> !(speech && i == windows.lastIndex) }.mapNotNull { t ->
            val w = sorted.filter { it.first >= t && it.first < t + WINDOW }
            if (w.size < 8) return@mapNotNull null
            val (s, gain) = best(w, ref)
            (t to t + WINDOW).takeIf { abs(s) > LIMIT && gain >= MIN_GAIN }
        }
        return Result(best(sorted, ref).first, off)
    }

    internal fun merged(spans: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for ((a, b) in spans.sortedBy { it.first }) {
            if (out.isNotEmpty() && a <= out.last().second) out[out.lastIndex] = out.last().first to maxOf(out.last().second, b)
            else out += a to b
        }
        return out
    }

    /** Share of the lines' time (moved by [shift]) on the merged [ref]. */
    internal fun score(lines: List<Pair<Double, Double>>, ref: List<Pair<Double, Double>>, shift: Double = 0.0): Double {
        var total = 0.0
        var on = 0.0
        var j = 0
        for ((a0, b0) in lines) {
            val a = a0 + shift
            val b = b0 + shift
            total += b - a
            while (j < ref.size && ref[j].second <= a) j++
            var k = j
            while (k < ref.size && ref[k].first < b) { on += minOf(b, ref[k].second) - maxOf(a, ref[k].first); k++ }
        }
        return if (total > 0) on / total else 0.0
    }

    /** The shift in −3..+3 s (0.1 s steps) with the most line time on [ref], and its gain over no shift. */
    private fun best(lines: List<Pair<Double, Double>>, ref: List<Pair<Double, Double>>): Pair<Double, Double> {
        val zero = score(lines, ref)
        return (-30..30).map { it / 10.0 }.map { it to score(lines, ref, it) - zero }
            .maxWith(compareBy<Pair<Double, Double>> { it.second }.thenBy { -abs(it.first) })
    }

    /** "0:00-6:00" for parts off, joined when they touch. */
    fun parts(off: List<Pair<Double, Double>>): String = merged(off).joinToString(", ") { (a, b) -> "${clock(a)}-${clock(b)}" }

    private fun clock(t: Double) = "${(t / 60).roundToInt()}:00" // windows start on whole minutes
}
