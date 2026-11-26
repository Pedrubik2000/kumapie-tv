package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.player.LiveLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pedro, 2026-11-25: ← a line right after the video starts crashed the bedroom TV (Jimaku's other files fetched and split). */
class LiveLinesBackSeekTest {
    @Test fun replayedLinesAreNotNewLines() {
        val refs = ArrayList<Double>()
        for (t in listOf(5.0, 8.0, 11.0, 14.0)) LiveLines.addRef(refs, t)
        repeat(40) { assertFalse(LiveLines.addRef(refs, 8.0 + it % 3 * 0.1)) } // ← then the lines show again
        assertEquals(4, refs.size) // not 44: Jimaku's other files (30 lines) wait for 30 lines really heard
        assertTrue(LiveLines.addRef(refs, 17.0))
        assertEquals(5, refs.size)
    }
}
