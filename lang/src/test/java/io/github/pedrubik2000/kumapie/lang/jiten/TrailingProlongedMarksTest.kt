package io.github.pedrubik2000.kumapie.lang.jiten

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which ー the stand-in for Jiten's TrailingKanaProlongedPlugin leaves out of Sudachi's input. */
class TrailingProlongedMarksTest {
    private fun marks(line: String) = MorphologicalAnalyser.trailingKanaProlongedMarks(line)

    @Test fun wordFinalAfterHiraganaOrKanji() {
        assertEquals(listOf(3), marks("わかるー"))
        assertEquals(listOf(2), marks("休憩ー"))
        assertEquals(listOf(3, 10), marks("そっかー、そろそろかー"))
        assertEquals(listOf(3), marks("すごいー 」"))
    }

    @Test fun notInsideWordsOrAfterKatakana() {
        assertEquals(emptyList<Int>(), marks("休憩ーなんて"))
        assertEquals(emptyList<Int>(), marks("コーヒー"))
        assertEquals(emptyList<Int>(), marks("ー"))
    }
}
