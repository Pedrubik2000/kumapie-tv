package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.player.LiveLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLinesBackSeekTest {
    private val refs = ArrayList<Double>()
    private val heard = HashSet<Long>()
    private fun hear(s: Double, at: Double = s) = LiveLines.addRef(refs, heard, (s * 1000).toLong(), at)

    @Test fun replayedLinesAreNotNewLines() {
        for (t in listOf(5.0, 8.0, 11.0, 14.0)) hear(t)
        repeat(40) { assertFalse(hear(8.0, 7.85 + it % 3 * 0.05)) }
        assertEquals(4, refs.size)
        assertTrue(hear(17.0))
        assertEquals(5, refs.size)
    }

    @Test fun lineAfterAJumpAndBackStillCounts() {
        for (t in 0..100 step 5) hear(t.toDouble())
        val before = refs.size
        for (t in listOf(600.0, 605.0)) assertTrue(hear(t))
        for (t in listOf(200.0, 205.0, 210.0)) assertTrue(hear(t))
        assertFalse(hear(205.0))
        assertEquals(before + 5, refs.size)
        assertEquals(refs.sorted(), refs)
    }
}
