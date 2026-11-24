package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.resumeLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's Continue watching never offers an episode that does not exist (Dandadan said "Episode 13 · Next episode · 12 episodes", 2026-11-24). */
class StreamsThatExistTest {
    private fun anilist(episodes: Int?) = Mine(1, "1", "ダンダダン", "Dandadan", "", "", "TV", episodes, "CURRENT", 12, 0.0, 0, emptyList(), 2024)
    private fun card(n: Int): Pair<Show, Episode> {
        val e = Episode("kitsu:1:$n", "Episode $n", 1, n, 1400.0, "", 0, 0, 0, null)
        return Show("kitsu:1", "Dandadan", "stream", "", listOf(e), "ja") to e
    }
    private fun ids(last: Int) = (1..last).map { "kitsu:1:$it" }.toSet()
    private fun shown(n: Int, ani: Int?, meta: Map<String, Set<String>>, last: Map<String, String> = emptyMap()) =
        streamsThatExist(listOf(card(n)), listOf(anilist(ani)), meta, last).map { it.second.id }
    private fun facts(ani: Int?, meta: Map<String, Set<String>>): String {
        val d = HomeData(listOf(card(13)), listOf(anilist(ani)), true, emptyList(), meta)
        val (s, e) = d.going.single()
        return ContinueTarget(s, e, 0, 1, d.mineFor(s, e)).facts()
    }

    @Test fun lastEpisodeOfTheMetaIsFinished() = assertEquals(emptyList<String>(), shown(13, 12, mapOf("kitsu:1" to ids(12))))

    @Test fun metaWithEpisode13KeepsTheCardAndDoesNotCountTwelve() {
        val meta = mapOf("kitsu:1" to ids(24))
        assertEquals(listOf("kitsu:1:13"), shown(13, 12, meta))
        assertEquals("Next episode", resumeLine(card(13).second))
        assertEquals("24 episodes · 2024", facts(12, meta))
    }

    @Test fun aCountBelowTheEpisodeNumberIsNotShown() = assertEquals("2024", facts(12, mapOf("kitsu:1" to ids(12))))

    @Test fun noMetaNoCountShowsNoCount() = assertEquals("2024", facts(12, emptyMap()))

    @Test fun noMetaFallsBackToAniListCount() {
        assertEquals(emptyList<String>(), shown(13, 12, emptyMap()))
        assertEquals(listOf("kitsu:1:12"), shown(12, 12, emptyMap()))
        assertEquals(listOf("kitsu:1:13"), shown(13, null, emptyMap()))
    }

    @Test fun aStaleMetaDoesNotDropANewlyAiredEpisode() = assertEquals(listOf("kitsu:1:13"), shown(13, 24, mapOf("kitsu:1" to ids(12))))

    @Test fun theEpisodeHeIsOnIsNeverDropped() {
        assertEquals(listOf("kitsu:1:13"), shown(13, 12, mapOf("kitsu:1" to ids(12)), mapOf("kitsu:1" to "kitsu:1:13")))
        assertEquals(listOf("kitsu:1:13"), shown(13, 12, emptyMap(), mapOf("kitsu:1" to "kitsu:1:13")))
    }
}
