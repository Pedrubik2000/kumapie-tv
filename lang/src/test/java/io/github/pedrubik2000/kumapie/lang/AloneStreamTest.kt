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

class PoisonedPrefsTest {
    private fun prefs(map: Map<String, Any?>) = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(android.content.SharedPreferences::class.java)) { _, m, a ->
        val v = map[a[0] as String]
        when (m.name) {
            "getBoolean" -> v as? Boolean ?: if (v == null) a[1] else throw ClassCastException()
            "getString" -> v as? String ?: if (v == null) a[1] else throw ClassCastException()
            else -> error(m.name)
        }
    } as android.content.SharedPreferences

    @Test fun importKeepsTypesAndOldPeersStillWork() {
        val theirs = org.json.JSONObject().put("alone_off_clean", true).put("alone_off_x|hand", false).put("alone_pos_a:1", 1.5)
            .put("alone_big", 123456789012L).put("alone_seen", org.json.JSONArray(listOf("a")))
        val out = DeviceFile.streams(emptyMap(), theirs)
        assertEquals(true, out["alone_off_clean"])
        assertEquals(false, out["alone_off_x|hand"])
        assertEquals(1.5f, out["alone_pos_a:1"])
        assertEquals(setOf("a"), out["alone_seen"])
        assertEquals(123456789012L.toFloat(), out["alone_big"])
    }

    @Test fun readsHealPoisonedStrings() {
        assertEquals(true, AloneStream.flag(prefs(mapOf("k" to true)), "k"))
        assertEquals(true, AloneStream.flag(prefs(mapOf("k" to "true")), "k"))
        assertEquals(false, AloneStream.flag(prefs(mapOf("k" to "false")), "k"))
        assertEquals(false, AloneStream.flag(prefs(emptyMap()), "k"))
        val all = mapOf("alone_off_x" to 40f, "alone_off_x|hand" to "true", "alone_off_y" to 40f)
        assertEquals(listOf("alone_off_y"), AloneStream.staleOffsets(all))
    }
}
