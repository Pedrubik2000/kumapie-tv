package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.resumeLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's Continue watching never offers an episode that does not exist (Dandadan said "Episode 13 · Next episode · 12 episodes", 2026-11-24). */
class StreamsThatExistTest {
    private val anilist = Mine(1, "1", "ダンダダン", "Dandadan", "", "", "TV", 12, "CURRENT", 12, 0.0, 0, emptyList(), 2024)
    private fun card(n: Int): Pair<Show, Episode> {
        val e = Episode("kitsu:1:$n", "Episode $n", 1, n, 1400.0, "", 0, 0, 0, null)
        return Show("kitsu:1", "Dandadan", "stream", "", listOf(e), "ja") to e
    }
    private fun ids(last: Int) = (1..last).map { "kitsu:1:$it" }.toSet()
    private fun data(meta: Map<String, Set<String>>) = HomeData(streamsThatExist(listOf(card(13)), listOf(anilist), meta), listOf(anilist), true, emptyList(), meta)

    @Test fun lastEpisodeOfTheMetaIsFinished() = assertEquals(emptyList<String>(), data(mapOf("kitsu:1" to ids(12))).going.map { it.second.id })

    @Test fun metaWithEpisode13KeepsTheCardAndDoesNotCountTwelve() {
        val d = data(mapOf("kitsu:1" to ids(24)))
        val (s, e) = d.going.single()
        assertEquals("Next episode", resumeLine(e))
        assertEquals("24 episodes · 2024", ContinueTarget(s, e, 0, 1, d.mineFor(s, e)).facts())
    }

    @Test fun metaWithoutCountShowsNoCount() {
        val d = HomeData(listOf(card(13)), listOf(anilist), true, emptyList())
        val (s, e) = d.going.single()
        assertEquals("2024", ContinueTarget(s, e, 0, 1, d.mineFor(s, e)).facts())
    }

    @Test fun noMetaFallsBackToAniListCount() {
        assertEquals(emptyList<String>(), data(emptyMap()).going.map { it.second.id })
        assertEquals(listOf("kitsu:1:12"), streamsThatExist(listOf(card(12)), listOf(anilist), emptyMap()).map { it.second.id })
    }
}
