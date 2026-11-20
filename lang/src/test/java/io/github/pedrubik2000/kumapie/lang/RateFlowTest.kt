package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Scene
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RateFlow]: rate -> the card's due changes, undo -> due again and the queue read, leaving -> each rated card takes its scene. */
class RateFlowTest {
    private val episode = EpisodeDetail("alone:kitsu:46474:10", "Sousou no Frieren", "10", "ja", 1400.0, "", null, emptyList(), emptyMap())
    private fun scene(i: Int) = Scene("s$i", i, i * 60.0, i * 60.0 + 60, true, 0, false, emptyList(), emptyList(), emptyMap())
    private val watch = Srs.Watch(episode.id, 120_000, "ja", true, 1.0)

    /** kumapie's own store in miniature: due cards per form, the queue reads, the recontexts. */
    private inner class Store {
        val due = mutableSetOf("魔法", "勇者")
        var undoLabel = ""
        var reads = 0
        val watches = mutableListOf<Srs.Watch?>()
        val moved = mutableListOf<Pair<List<Long>, Miner.Request>>()
        val flow = RateFlow(
            answer = { form, _, _, w -> watches += w; if (due.remove(form)) KnownWords.Rated(listOf(form.hashCode().toLong()), 1, "Undo $form").also { undoLabel = it.undoLabel } else null },
            takeBack = { r -> (r.undoLabel == undoLabel).also { if (it) { due += r.undoLabel.removePrefix("Undo "); undoLabel = "" } } },
            refreshDue = { reads++ },
            recontext = { notes, at -> moved += notes to at },
        )
    }

    @Test fun rateMovesTheDueAndKeepsTheWatch() = runBlocking {
        val s = Store()
        val r = s.flow.rate("魔法", 3, 2_000, watch, Miner.Request(episode, scene(2), 4))
        assertEquals(1, r?.cards)
        assertFalse("rated: not due any more", "魔法" in s.due)
        assertSame(watch, s.watches.single())
        assertNull("no card of its own: nothing rated", s.flow.rate("森", 3, 2_000, watch, null))
    }

    @Test fun undoMakesItDueAgainAndReadsTheQueue() = runBlocking {
        val s = Store()
        val r = requireNotNull(s.flow.rate("魔法", 1, 2_000, watch, Miner.Request(episode, scene(2), 4)))
        assertTrue(s.flow.undo(r))
        assertTrue("due again", "魔法" in s.due)
        assertEquals("the due queue read again", 1, s.reads)
        assertFalse("undone twice: no", s.flow.undo(r))
        assertEquals(1, s.reads)
        s.flow.leave()
        assertTrue("an undone rating moves no card", s.moved.isEmpty())
    }

    @Test fun leavingMovesEachRatedCardToItsScene() = runBlocking {
        val s = Store()
        val a = requireNotNull(s.flow.rate("魔法", 3, 2_000, watch, Miner.Request(episode, scene(2), 4)))
        s.flow.sceneLeft()
        assertEquals("leaving the scene reads the queue", 1, s.reads)
        s.flow.rate("勇者", 4, 2_000, watch, null) // rated in the dictionary: no line, no move
        assertTrue("nothing moves before the player closes", s.moved.isEmpty())
        s.flow.leave()
        assertEquals(listOf(a.notes to 2), s.moved.map { it.first to it.second.scene.index })
        s.flow.leave()
        assertEquals("moved once", 1, s.moved.size)
    }
}
