package io.github.pedrubik2000.kumapie.data

private const val LONGEST_RUN = 4

fun joinCardedScenes(scenes: List<Scene>, carded: (String) -> Boolean): List<Scene> =
    scenes.map { s -> s.copy(cues = s.cues.map { c -> c.copy(segments = joinCarded(c.segments, carded)) }) }

fun joinCarded(segments: List<Segment>, carded: (String) -> Boolean): List<Segment> {
    if (segments.count { it.word != null } < 2) return segments
    val out = ArrayList<Segment>(segments.size)
    var i = 0
    while (i < segments.size) {
        val n = (minOf(LONGEST_RUN, segments.size - i) downTo 2).firstOrNull { n ->
            segments.subList(i, i + n).let { run -> run.all { it.word != null } && carded(run.joinToString("") { it.text }) }
        } ?: 1
        out += if (n == 1) segments[i] else segments.subList(i, i + n).joinToString("") { it.text }.let { Segment(it, it) }
        i += n
    }
    return out
}
