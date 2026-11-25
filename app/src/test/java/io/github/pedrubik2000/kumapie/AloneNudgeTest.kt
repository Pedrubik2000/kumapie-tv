package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.AloneStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AloneNudgeTest {
    private val prefs = Settings(RuntimeEnvironment.getApplication()).prefs
    private val id = "alone:kitsu:10051:1"
    private val fitKey = AloneStream.offKey(id)

    private fun withFit(fit: Float) = prefs.edit().putFloat(fitKey, fit).apply()

    @Test fun aNudgeOverASavedFitIsHandOnItsOwnTotal() {
        withFit(12.4f)
        AloneStream.nudge(prefs, id, 0.5)
        assertFalse(AloneStream.handed(prefs, id))
        AloneStream.nudge(prefs, id, 1.0)
        assertTrue(AloneStream.handed(prefs, id))
        assertEquals(1.5, AloneStream.kept(prefs, id), 1e-6)
        assertEquals(12.4f, prefs.getFloat(fitKey, 0f), 0f)
    }

    @Test fun resetKeepsTheFit() {
        withFit(12.4f)
        AloneStream.nudge(prefs, id, 3.0)
        assertEquals(3.0, AloneStream.clearNudge(prefs, id), 1e-6)
        assertEquals(0.0, AloneStream.kept(prefs, id), 0.0)
        assertFalse(AloneStream.handed(prefs, id))
        assertEquals(12.4f, prefs.getFloat(fitKey, 0f), 0f)
    }

    @Test fun resetBeforeTheFitLandsEndsAtTheFit() {
        val built = 0.0
        var at = built
        AloneStream.nudge(prefs, id, 3.0)
        at += 3.0
        at -= AloneStream.clearNudge(prefs, id)
        at += requireNotNull(AloneStream.lateShift(built, 71.3 - 60.0, 71.3 - 60.0))
        assertEquals(11.3, at, 1e-9)
    }

    @Test fun openingTwiceStartsAtFitPlusNudgeBothTimes() {
        val app = RuntimeEnvironment.getApplication()
        val file = "ep1.mkv"
        prefs.edit().putFloat(AloneStream.offKey(id, file), 12.4f).apply()
        prefs.edit().putString(AloneStream.fileKey(id), file).apply()
        AloneStream.nudge(prefs, id, 2.0)
        repeat(2) {
            val player = androidx.media3.exoplayer.ExoPlayer.Builder(app).build()
            val s = AloneStream(prefs, null, player, id, "series", "Show", "Show", "http://127.0.0.1/v", file) {}
            assertEquals(14.4, s.live.offset, 1e-4)
            s.leave()
            player.release()
            assertEquals(12.4f, prefs.getFloat(AloneStream.offKey(id, file), 0f), 1e-4f)
        }
    }

    @Test fun anOldCombinedValueIsTheFitWithNoNudge() {
        withFit(-2.8f)
        assertEquals(0.0, AloneStream.kept(prefs, id), 0.0)
        assertFalse(AloneStream.handed(prefs, id))
    }
}
