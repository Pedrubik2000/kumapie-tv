package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The moras are VOICEVOX's own for these words: only a real misreading may send the voice to the dictionary's reading. */
class SameSoundTest {
    private fun same(said: String, reading: String) = JapaneseLookup.sameSound(said, reading)

    @Test fun spellingsOfOneSound() {
        assertTrue(same("トオキョオ", "とうきょう"))
        assertTrue(same("エエガ", "えいが"))
        assertTrue(same("キズク", "きづく"))
        assertTrue(same("ハナジ", "はなぢ"))
        assertTrue(same("ケエキ", "ケーキ"))
        assertTrue(same("オモウ", "おもう"))
        assertTrue(same("ホオ", "ほお"))
        assertTrue(same("キュウリョオ", "きゅうりょう"))
    }

    @Test fun misreadings() {
        assertFalse(same("カゼヤク", "かぜぐすり"))
        assertFalse(same("ニジュッサイ", "はたち"))
        assertFalse(same("カゼグスリ", "かざぐすり"))
    }
}
