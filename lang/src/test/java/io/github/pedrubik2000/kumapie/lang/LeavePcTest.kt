package io.github.pedrubik2000.kumapie.lang

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The marks merge both apps use (TV leaving the PC, the phone's Progress): newest mark of a word wins. */
class LeavePcTest {
    private fun part(vararg marks: Triple<String, Boolean, Long>) = JSONObject().put("marked",
        JSONObject().apply { marks.forEach { (w, k, at) -> put(w, org.json.JSONArray().put(k).put(at)) } })

    @Test fun newestMarkWins() {
        val tv = part(Triple("猫", true, 10), Triple("犬", true, 30), Triple("鳥", false, 5))
        val phone = part(Triple("猫", false, 20), Triple("犬", false, 25), Triple("鳥", true, 6))
        assertEquals(setOf("犬", "鳥"), LeavePc.newestMarks(listOf(tv, phone)))
    }

    @Test fun partsWithoutMarksOrBadEntries() {
        val bad = JSONObject().put("marked", JSONObject().put("x", "not an array"))
        assertEquals(setOf("a"), LeavePc.newestMarks(listOf(JSONObject(), bad, part(Triple("a", true, 1)))))
    }
}
