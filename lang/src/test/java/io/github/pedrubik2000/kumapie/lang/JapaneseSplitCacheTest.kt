package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** An episode's split words saved on the device ([JapaneseModel.words]) come back the same. */
class JapaneseSplitCacheTest {
    @Test fun wordsComeBackAsSaved() {
        val words = listOf(
            listOf(JapaneseModel.Token("届いた", "届く", "トドイタ", "動詞", 0, "届い/た"), JapaneseModel.Token("。", "。", "", "補助記号", 3)),
            emptyList(), // a line with no words ("♪")
            listOf(JapaneseModel.Token("でも", "でも", "デモ", "接続詞", 0)))
        assertEquals(words, JapaneseModel.decode(JapaneseModel.encode(words)))
    }

    @Test fun anotherJitenVersionIsAnotherFile() =
        assertNotEquals(JapaneseModel.key(listOf("あ", "い"), 1L), JapaneseModel.key(listOf("あ", "い"), 2L))
}
