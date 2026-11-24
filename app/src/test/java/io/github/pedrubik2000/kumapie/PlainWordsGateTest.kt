package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.KnownWords
import io.github.pedrubik2000.kumapie.lang.LangKit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Settings > Word colours off: the Anki reads that only serve colours do not run; a forced call (a button, a rating) still does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlainWordsGateTest {
    @Test fun colourReadsStopWhileColoursAreOff() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val settings = Settings(ctx).also { it.plainWords = true }
        val known = LangKit.get(ctx, settings).languages.japanese.known
        assertFalse(known.readCards())
        assertEquals("word colours are off", known.refresh().exceptionOrNull()?.message)
        assertNotEquals("word colours are off", known.refresh(force = true).exceptionOrNull()?.message) // runs, then stops on "no AnkiDroid"
        KnownWords::class.java.getDeclaredField("lastReading").apply { isAccessible = true }.set(known, KnownWords.Reading("none", emptyList(), emptyList(), emptyMap())) // so a due queue is asked for
        known.refreshDue()
        assertEquals("refreshDue gate: unforced call must be skipped while colours are off", 0L, known.dueAt)
        runCatching { known.refreshDue(force = true) }
        assertNotEquals("refreshDue gate: forced call must still run", 0L, known.dueAt)
    }
}
