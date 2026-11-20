package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.continueRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** [continueRow]: one card per show, the latest episode left partway; under 2 minutes left counts as watched. */
class ContinueRowTest {
    private fun ep(id: String, resume: Double?, at: Long) = Episode(id, id, 1, null, 1380.0, "", 100, 0, 0, resume, resumedAt = at)
    private fun show(id: String, vararg eps: Episode) = Show(id, id, "anime", "", eps.toList())

    @Test fun oneCardPerShowNewestFirst() {
        val bofuri = show("bofuri", ep("e4", 1320.0, 10), ep("e5", 1000.0, 20), ep("e6", 500.0, 40), ep("e7", 100.0, 30))
        val lucky = show("lucky", ep("l1", 300.0, 35))
        assertEquals(listOf("e6", "l1"), continueRow(listOf(bofuri, lucky)).map { it.second.id })
    }

    @Test fun nearlyDoneIsLeftOut() {
        // 1 minute left: credits, not a card.
        assertEquals(emptyList<String>(), continueRow(listOf(show("s", ep("x", 1320.0, 5)))).map { it.second.id })
    }

    @Test fun finishedSuggestsTheNext() {
        // e2 finished last (credits left), e1 partway earlier: e3, from the start, at e2's time.
        val s = show("s", ep("e1", 600.0, 10), ep("e2", 1370.0, 50), ep("e3", null, 0))
        val (_, e) = continueRow(listOf(s)).single()
        assertEquals("e3" to null, e.id to e.resume)
        assertEquals(50L, e.resumedAt)
    }

    @Test fun streamsMergeByTime() {
        val stream = show("k", ep("k:2", null, 60)).let { it to it.episodes[0] }
        assertEquals(listOf("k:2", "l1"), continueRow(listOf(show("lucky", ep("l1", 300.0, 35))), listOf(stream)).map { it.second.id })
    }

    @Test fun shortsNeverInContinue() {
        val reels = Show("r", "r", "reels", "", listOf(ep("r1", 30.0, 90)))
        assertEquals(listOf("l1"), continueRow(listOf(reels, show("lucky", ep("l1", 300.0, 35)))).map { it.second.id })
    }

    @Test fun aWatchedEpisodeIsNotInContinue() {
        val marked = ep("e1", 600.0, 10).copy(watchedFlag = true)
        assertEquals(emptyList<String>(), continueRow(listOf(show("s", marked))).map { it.second.id })
    }
}
