package io.github.pedrubik2000.kumapie.data

import org.json.JSONArray

/** A stretch of an episode that played, in milliseconds. */
data class Span(val start: Long, val end: Long)

/**
 * Watched time ranges of an episode: what "seen" means since there are no scenes (a subtitle fix can't lose them).
 * The PC has the same functions in kumapie-server `services/feed/ranges.py`: keep the two constants the same there.
 */
object Ranges {
    /** Ranges closer than this are one (a seek's rounding, a player tick). */
    const val JOIN_MS = 1_000L
    /** An episode is watched when at most this share of its spoken time is missing. */
    const val SLACK = 0.03

    /** Sorted, not overlapping. */
    fun union(spans: Collection<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans.filter { it.end > it.start }.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && s.start <= last.end + JOIN_MS) out[out.lastIndex] = Span(last.start, maxOf(last.end, s.end)) else out += s
        }
        return out
    }

    /** Milliseconds of [need] that [have] doesn't cover (both from [union]). */
    fun missing(need: List<Span>, have: List<Span>): Long {
        var total = 0L
        for (n in need) {
            var at = n.start
            for (h in have) {
                if (h.end <= at) continue
                if (h.start >= n.end) break
                total += maxOf(0, h.start - at)
                at = maxOf(at, h.end)
            }
            total += maxOf(0, n.end - at)
        }
        return total
    }

    /**
     * What must be watched: the time of the lines that are said (no lyrics). Silence, the opening and the ending
     * have no such lines, so skipping them keeps an episode watched.
     */
    fun spoken(cues: Collection<Cue>): List<Span> = union(cues.map { Span((it.start * 1000).toLong(), (it.end * 1000).toLong()) })

    fun watched(need: List<Span>, have: List<Span>): Boolean {
        val size = need.sumOf { it.end - it.start }
        return size > 0 && missing(need, have) <= size * SLACK
    }

    fun json(spans: Collection<Span>): JSONArray = JSONArray().also { a -> spans.forEach { a.put(JSONArray().put(it.start).put(it.end)) } }

    fun parse(a: JSONArray?): List<Span> = (0 until (a?.length() ?: 0)).map { i -> a!!.getJSONArray(i).let { Span(it.getLong(0), it.getLong(1)) } }
}
