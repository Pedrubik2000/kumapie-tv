package io.github.pedrubik2000.kumapie.mobile.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Mark as not watched" is a time: scenes seen before it stop counting, scenes seen again after it count. */
class UnwatchTest {
    private val seen = mapOf("s1" to "ep1", "s2" to "ep1", "s3" to "ep2")

    @Test fun noMarkKeepsEverything() =
        assertEquals(seen, Progress.stillSeen(seen, mapOf("s1" to 100L, "s2" to 100L, "s3" to 100L), emptyMap()))

    @Test fun markHidesOlderScenesOfThatEpisodeOnly() =
        assertEquals(mapOf("s3" to "ep2"), Progress.stillSeen(seen, mapOf("s1" to 100L, "s2" to 100L, "s3" to 100L), mapOf("ep1" to 200L)))

    @Test fun sceneSeenAgainAfterTheMarkCounts() =
        assertEquals(mapOf("s2" to "ep1", "s3" to "ep2"), Progress.stillSeen(seen, mapOf("s1" to 100L, "s2" to 300L, "s3" to 100L), mapOf("ep1" to 200L)))

    @Test fun sceneWithoutTimeIsHiddenByAMark() = // old entries written without a "last seen" time
        assertEquals(mapOf("s3" to "ep2"), Progress.stillSeen(seen, mapOf("s3" to 100L), mapOf("ep1" to 1L)))
}
