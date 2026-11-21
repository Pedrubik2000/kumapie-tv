package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Span
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * [NoPcBackend], the TV's and the phone's player without a PC: a mark is in Progress and the colour list at once (the
 * phone's used to wait until Anki was read here: KnownWords.ready), and nothing is kept for a PC.
 */
class NoPcBackendTest {
    private val dir = Files.createTempDirectory("nopc").toFile()
    private val progress = Progress(dir, null)
    private val list = HashSet<String>()
    private var changes = 0
    private val backend = NoPcBackend(progress, "en", { "en:$it" }, { w, k -> if (k) list += w else list -= w; if (w in list) "k" else "u" },
        { s, _ -> "tts:$s" }, {}, { changes++ })

    @Test fun markedKnownAtOnceAndUnmarked() = runBlocking {
        assertEquals("k", backend.markKnown("give up", true))
        assertTrue("give up" in list)
        assertTrue("en:give up" in progress.merged.marked)
        assertEquals("u", backend.markKnown("give up", false))
        assertFalse("give up" in list)
        assertFalse("en:give up" in progress.merged.marked)
    }

    @Test fun lookupsCount() = runBlocking {
        assertEquals(1, backend.lookup("sushi", ""))
        assertEquals(2, backend.lookup("sushi", ""))
    }

    @Test fun progressSavedAndNothingForAPc() = runBlocking {
        backend.progress("ep1", 42.0, 30.0, listOf(Span(0, 30_000)), false)
        backend.markKnown("give up", true)
        val again = Progress(dir, null).merged
        assertEquals(42.0, again.pos["ep1"] ?: 0.0, 0.0)
        assertTrue("en:give up" in again.marked)
        assertEquals(2, changes)
        assertEquals(listOf("progress.json"), dir.list()?.toList())
        assertEquals("", backend.definitionAudio("s1", 0, "give up"))
        assertEquals("tts:give up", backend.wordAudio("give up", "en"))
    }
}
