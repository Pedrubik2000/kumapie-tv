package io.github.pedrubik2000.kumapie.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** [modeForFrameRate] on the living-room Chromecast's 4K modes (2026-11-18). */
class FrameRateTest {
    companion object { val CC = listOf(143 to 59.94006f, 144 to 60.000004f, 145 to 50f, 146 to 29.97003f, 147 to 30.000002f,
        148 to 25f, 149 to 23.976025f, 150 to 24.000002f) }
    private val cc = CC

    @Test fun film() = assertEquals(149, modeForFrameRate(23.976f, 144, cc))
    @Test fun exact24NotOn23976() = assertEquals(150, modeForFrameRate(24f, 144, cc))
    @Test fun pal() = assertEquals(145, modeForFrameRate(25f, 144, cc))
    @Test fun alreadyFits() = assertEquals(null, modeForFrameRate(30f, 144, cc))
    @Test fun ntscTv() = assertEquals(143, modeForFrameRate(29.97f, 144, cc))
    @Test fun sixtyOnlyTv() = assertEquals(null, modeForFrameRate(23.976f, 1, listOf(1 to 60f)))
}

/** [frameRateOf] on MKV-style times (whole ms) with a dropped frame. */
class MeasuredFrameRateTest {
    private fun mkv(fps: Double, n: Int) = (0 until n).map { (it * 1000.0 / fps).toLong() * 1000 }

    @Test fun film() = assertEquals(149, modeForFrameRate(frameRateOf(mkv(24000.0 / 1001, 75))!!, 144, FrameRateTest.CC))
    @Test fun exact24() = assertEquals(150, modeForFrameRate(frameRateOf(mkv(24.0, 75))!!, 144, FrameRateTest.CC))
    @Test fun droppedFrame() = assertEquals(149, modeForFrameRate(frameRateOf(mkv(24000.0 / 1001, 76).filterIndexed { i, _ -> i != 30 })!!, 144, FrameRateTest.CC))
}
