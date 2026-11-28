package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.notAnEpisode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A debrid placeholder clip (a file not cached yet) ends in seconds: no next episode, nothing marked watched (2026-11-27). */
class NotAnEpisodeTest {
    @Test fun aPlaceholderClipIsNot() = assertTrue(notAnEpisode(8_000))

    @Test fun anEpisodeIs() = assertFalse(notAnEpisode(23 * 60_000L))

    @Test fun anUnknownLengthChangesNothing() {
        assertFalse(notAnEpisode(0))
        assertFalse(notAnEpisode(Long.MIN_VALUE + 1))
    }
}
