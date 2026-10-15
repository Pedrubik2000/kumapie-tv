package io.github.pedrubik2000.kumapie.mobile.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/** Time per language: its own, plus a share (by scenes seen) of the time kept before languages were. */
class LangWatchTest {
    private fun view(watch: Map<String, Double>, langWatch: Map<String, Map<String, Double>>) =
        Progress.View(emptyMap(), emptyMap(), watch, emptyMap(), emptySet(), emptyMap(), langWatch = langWatch)

    @Test fun oldTimeSplitByScenes() = assertEquals(mapOf("d1" to 75.0),
        Progress.daysIn("de", view(mapOf("d1" to 100.0), emptyMap()), mapOf("s1" to "de", "s2" to "de", "s3" to "de", "s4" to "ja")))

    @Test fun newTimeIsExact() = assertEquals(mapOf("d1" to 60.0),
        Progress.daysIn("de", view(mapOf("d1" to 100.0), mapOf("de" to mapOf("d1" to 60.0), "ja" to mapOf("d1" to 40.0))), mapOf("s1" to "ja")))
}
