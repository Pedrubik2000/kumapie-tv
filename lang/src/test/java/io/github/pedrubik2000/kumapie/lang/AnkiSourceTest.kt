package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.AnkiSource
import io.github.pedrubik2000.kumapie.data.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AnkiSourceTest {
    private val kuma3 = "io.github.pedrubik2000.kuma3"
    private val droid = "com.ichi2.anki"

    @Test fun kuma3IsUsedFirstWhenBothAreInstalled() {
        assertEquals(kuma3, AnkiSource.appFor(AnkiSource.ANY, listOf(droid, kuma3)))
        assertEquals(droid, AnkiSource.appFor(AnkiSource.ANY, listOf(droid)))
        assertEquals(droid, AnkiSource.appFor(AnkiSource.ANKIDROID, listOf(kuma3, droid)))
        assertEquals(kuma3, AnkiSource.appFor(AnkiSource.KUMA3, listOf(droid, kuma3)))
        assertNull(AnkiSource.appFor(AnkiSource.KUMA3, listOf(droid)))
        assertNull(AnkiSource.appFor(AnkiSource.ANY, emptyList()))
        assertNull(AnkiSource.appFor(AnkiSource.NONE, listOf(kuma3, droid)))
    }

    @Test fun theTwoTicksMakeOneChoiceAndBack() {
        listOf(false to false, true to false, false to true, true to true).forEach { (k, d) ->
            val c = AnkiSource.choice(k, d)
            assertEquals(k, AnkiSource.kuma3On(c))
            assertEquals(d, AnkiSource.droidOn(c))
        }
        assertEquals(AnkiSource.ANY, AnkiSource.choice(true, true))
        assertEquals(AnkiSource.NONE, AnkiSource.choice(false, false))
    }

    @Test fun defaultsAreTodaysBehaviour() {
        assertEquals("none", AnkiSource.NONE)
        assertEquals(listOf("Japanese Mined"), AnkiSource.decks(null, Lang.JAPANESE))
        assertEquals(listOf("Japanese Mined"), AnkiSource.decks("", Lang.JAPANESE))
        assertEquals("\"deck:Japanese Mined\"", AnkiSource.search(AnkiSource.decks(null, Lang.JAPANESE)))
        assertEquals("Word", AnkiSource.DEFAULT_WORD)
        assertEquals("Reading", AnkiSource.DEFAULT_READING)
    }

    @Test fun severalDecksAreOneSearch() {
        val decks = AnkiSource.decks(AnkiSource.encode(listOf("A b", "C")), Lang.JAPANESE)
        assertEquals(listOf("A b", "C"), decks)
        assertEquals("(\"deck:A b\" or \"deck:C\")", AnkiSource.search(decks))
    }

    private fun note(vararg f: Pair<String, String>) = AnkiCards.Note(1, mapOf(*f), emptySet())

    @Test fun defaultFieldsLeaveTheNoteAlone() {
        val n = note("Word" to "日本[にほん]語", "Reading" to "にほんご")
        assertSame(n, AnkiKnown.remap(n, "Word", "Reading"))
    }

    @Test fun chosenFieldsFeedTheCardKeys() {
        val n = note("Expression" to "上手", "Kana" to "じょうず", "Word" to "ignored")
        assertEquals(listOf("上手\tじょうず", "上手"), CardIndex.japaneseKeys(AnkiKnown.remap(n, "Expression", "Kana")))
        assertEquals(listOf("上手"), CardIndex.spacedKeys(AnkiKnown.remap(n, "Expression", "Kana")))
    }

    @Test fun theReadAsksForReviewedNotesOnly() {
        assertEquals("\"deck:Japanese Mined\" -is:new", AnkiSource.reviewedSearch(AnkiSource.decks(null, Lang.JAPANESE)))
        assertEquals("(\"deck:A\" or \"deck:B\") -is:new", AnkiSource.reviewedSearch(listOf("A", "B")))
    }

    @Test fun fieldNamesComeAsJsonOrSeparated() {
        assertEquals(listOf("Word", "Reading"), AnkiLookups.fieldNames("[\"Word\",\"Reading\"]"))
        assertEquals(listOf("Word", "Reading"), AnkiLookups.fieldNames("Word\u001fReading"))
        assertEquals(emptyList<String>(), AnkiLookups.fieldNames(""))
    }
}
