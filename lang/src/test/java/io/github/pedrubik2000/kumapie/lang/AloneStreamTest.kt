package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The no-PC stream's bookkeeping, shared by the TV and the phone/tablet ([AloneStream]). */
class AloneStreamTest {
    @Test fun theLateFitMovesTheBuiltLinesByTheDifference() {
        assertNull("not built yet", AloneStream.lateShift(null, 2.4, 2.4))
        assertNull("no fit yet", AloneStream.lateShift(0.0, 0.0, null))
        assertEquals(2.4, AloneStream.lateShift(0.0, 2.4, 2.4)!!, 1e-9)
        // A hand shift saved earlier wins over the fit: the offset didn't change, nothing moves.
        assertEquals(0.0, AloneStream.lateShift(-1.5, -1.5, 2.4)!!, 1e-9)
    }

    @Test fun nearTheEndStartsOverNextTime() {
        assertEquals(0.0, AloneStream.place(1350.0, 1420.0), 0.0)
        assertEquals(600.0, AloneStream.place(600.0, 1420.0), 0.0)
    }

    @Test fun keysAndNumbersFromTheId() {
        assertEquals("alone_off_kitsu:46474:3", AloneStream.offKey("alone:kitsu:46474:3"))
        assertEquals("alone_off_kitsu:46474:3", AloneStream.offKey("kitsu:46474:3"))
        assertEquals(3, AloneStream.episodeNumber("kitsu:46474:3"))
        assertNull(AloneStream.episodeNumber("tt0245429"))
    }
}
