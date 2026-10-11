package io.github.pedrubik2000.kumapie.mobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCheckTest {
    // Ten minutes of "speech": 2 s spans with uneven gaps (an even rhythm would also fit at other shifts); the lines
    // sit exactly on them.
    private val speech = (0 until 160).runningFold(0.0) { t, i -> t + 2.0 + 0.8 + (i * 7 % 5) * 0.9 }.map { it to it + 2.0 }
    private val lines = speech.map { it.first to it.second }

    @Test fun inSync() {
        val r = SyncCheck.check(lines, speech, speech = true)
        assertEquals(0.0, r.shift, 0.05)
        assertTrue(r.ok)
    }

    @Test fun twoSecondsLate() {
        val r = SyncCheck.check(lines.map { it.first + 2 to it.second + 2 }, speech, speech = true)
        assertEquals(-2.0, r.shift, 0.15)
        assertFalse(r.ok)
    }

    @Test fun onePartOff() {
        // The first three minutes 1.5 s late (an opening of another length), the rest right: whole episode looks fine.
        val r = SyncCheck.check(lines.map { if (it.first < 180) it.first + 1.5 to it.second + 1.5 else it }, speech, speech = false)
        assertEquals(0.0, r.shift, 0.05)
        assertEquals(listOf(0.0 to 180.0), r.off)
        assertEquals("0:00-3:00", SyncCheck.parts(r.off))
        assertFalse(r.ok)
    }

    @Test fun tooLittleReference() {
        // The VAD heard only a fifth of the first three minutes' speech: no verdict there, even if some shift fits it.
        val thin = speech.filterIndexed { i, s -> s.first >= 180 || i % 5 == 0 }
        val r = SyncCheck.check(lines.map { if (it.first < 180) it.first + 1.5 to it.second + 1.5 else it }, thin, speech = false)
        assertTrue(r.off.isEmpty())
    }

    @Test fun nothingToCheck() {
        assertTrue(SyncCheck.check(emptyList(), speech, speech = true).ok)
        assertEquals(0.0, SyncCheck.score(lines, emptyList()), 0.0)
        assertEquals(listOf(0.0 to 3.0, 10.0 to 12.0), SyncCheck.merged(listOf(0.0 to 2.0, 1.0 to 3.0, 10.0 to 12.0)))
    }
}
