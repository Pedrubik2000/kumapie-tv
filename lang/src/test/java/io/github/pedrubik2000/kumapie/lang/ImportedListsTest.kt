package io.github.pedrubik2000.kumapie.lang

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [ImportedLists]: what a device's own import, other devices' lists and the wire do to each other. */
class ImportedListsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Prefs : ImportedLists.Stamps {
        val map = HashMap<String, Long>()
        override fun get(key: String, default: Long) = map[key] ?: default
        override fun put(key: String, value: Long) { map[key] = value }
    }

    private fun device(old: String? = null): ImportedLists {
        val dir = tmp.newFolder()
        val legacy = File(dir, "imported.txt").apply { old?.let { writeText(it) } }
        return ImportedLists(ImportedSources(dir, legacy), Prefs(), "ja")
    }

    private fun send(from: ImportedLists, to: ImportedLists) = to.use(JSONObject(from.body().toString()))

    @Test fun aNewerListFromElsewhereReplacesTheWholeOldOne() {
        val d = device("old1\nold2")
        assertEquals(setOf("old1", "old2"), d.words)
        assertEquals(1L, d.time())
        d.use(wire(100, "p1"))
        assertEquals(setOf("p1"), d.words) // the migrated list went too
        d.use(wire(200)) // a newer, empty list (a removal elsewhere)
        assertEquals(emptySet<String>(), d.words)
        d.set(ImportedSources.JITEN, setOf("j"), 300)
        d.use(wire(250, "stale"))
        assertEquals(setOf("j"), d.words)
        assertEquals(300L, d.time())
    }

    @Test fun aRemovalReachesTheOtherDeviceAndDoesNotComeBack() {
        val a = device()
        val b = device()
        a.set(ImportedSources.JITEN, setOf("j1", "j2"), 100)
        assertTrue(send(a, b))
        assertEquals(setOf("j1", "j2"), b.words)
        b.set(ImportedSources.JITEN, setOf("k"), 200)
        assertEquals(setOf("k"), b.words)
        assertTrue(send(b, a))
        assertEquals(setOf("k"), a.words) // the newest list wins, whole
        a.set(ImportedSources.JITEN, emptySet(), 300)
        assertTrue(send(a, b))
        assertEquals(emptySet<String>(), b.words)
        assertFalse(send(b, a)) // the same time is never applied again
        assertEquals(emptySet<String>(), a.words)
    }

    @Test fun aRemovalWithWordsOnlyFromPeersSticks() {
        val a = device()
        val b = device()
        a.set(ImportedSources.JITEN, setOf("j"), 100)
        send(a, b)
        b.set(ImportedSources.JITEN, emptySet(), 200)
        assertEquals(emptySet<String>(), b.words)
        assertEquals(200L, b.time())
        assertEquals(0, b.body().getJSONObject("imported").getJSONArray("words").length())
        send(b, a)
        assertEquals(emptySet<String>(), a.words) // A's own import is replaced by the newer removal
    }

    @Test fun anUnmigratedDeviceStillSendsItsOldList() {
        val legacy = tmp.newFile("imported.txt").apply { writeText("a\nb") }
        val s = ImportedSources(File(tmp.newFile(), "known"), legacy) // its folder cannot be made: not migrated
        assertEquals(mapOf(ImportedSources.LEGACY to setOf("a", "b")), s.read())
        val words = ImportedLists(s, Prefs(), "ja").body().getJSONObject("imported").getJSONArray("words")
        assertEquals(listOf("a", "b"), List(words.length()) { words.getString(it) })
    }

    @Test fun sentOnceIsNotSentAgain() {
        val a = device()
        a.set(ImportedSources.JITEN, setOf("j"), 100)
        assertTrue(a.body().getJSONObject("imported").has("words"))
        a.use(wire(100)) // the PC answers with the same time: it has them
        assertFalse(a.body().getJSONObject("imported").has("words"))
    }

    private fun wire(time: Long, vararg words: String) =
        JSONObject().put("imported", JSONObject().put("time", time).put("words", JSONArray(words.toList())))
}
