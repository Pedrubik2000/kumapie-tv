package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.nextUp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** [nextUp] (Pedro, 2026-11-20): Home's Continue episode, else partway (over 20 s, over 2 min left), else the first unwatched, else the first. */
class NextUpTest {
    private fun ep(id: String, resume: Double? = null, at: Long = 0, watched: Boolean = false) =
        Episode(id, id, 1, null, 1380.0, "", 100, 0, 0, resume, resumedAt = at, watchedFlag = watched)
    private fun lib(eps: List<Episode>, going: String? = null) = nextUp(eps, going)?.id
    private fun json(vararg ids: Pair<String, Boolean>) = ids.map { (id, w) -> JSONObject().put("id", id).put("watched", w) }
    private fun disc(eps: List<JSONObject>, going: String? = null) = nextUp(eps, going)?.optString("id")

    @Test fun continueEpisodeWins() {
        assertEquals("e3", lib(listOf(ep("e1", 600.0, 50), ep("e2"), ep("e3")), going = "e3"))
        assertEquals("k:3", disc(json("k:1" to true, "k:2" to false, "k:3" to false), going = "k:3"))
    }

    @Test fun goingNotInTheListFallsThrough() {
        assertEquals("e1", lib(listOf(ep("e1"), ep("e2")), going = "gone"))
        assertEquals("k:2", disc(json("k:1" to true, "k:2" to false), going = "gone"))
    }

    @Test fun partwayLatestPlayed() =
        assertEquals("e3", lib(listOf(ep("e1"), ep("e2", 300.0, 10), ep("e3", 500.0, 20), ep("e4"))))

    @Test fun tenSecondsInIsNotPartway() =
        assertEquals("e1", lib(listOf(ep("e1"), ep("e2", 10.0, 30))))

    @Test fun oneMinuteLeftCountsAsWatched() =
        assertEquals("e2", lib(listOf(ep("e1", 1320.0, 30), ep("e2"))))

    @Test fun firstUnwatched() {
        assertEquals("e2", lib(listOf(ep("e1", watched = true), ep("e2"), ep("e3"))))
        assertEquals("k:2", disc(json("k:1" to true, "k:2" to false, "k:3" to true)))
    }

    @Test fun allWatchedGivesTheFirst() {
        assertEquals("e1", lib(listOf(ep("e1", watched = true), ep("e2", watched = true))))
        assertEquals("k:1", disc(json("k:1" to true, "k:2" to true)))
    }

    @Test fun emptyIsNull() {
        assertEquals(null, lib(emptyList()))
        assertEquals(null, disc(emptyList()))
    }
}
