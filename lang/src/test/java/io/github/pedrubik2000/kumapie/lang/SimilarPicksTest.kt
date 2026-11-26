package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.SimilarPicks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarPicksTest {
    private fun show(id: Int, genres: List<String>, tags: List<String> = emptyList(), average: Int = 70, year: Int = 2020, format: String = "TV", episodes: Int? = 12) =
        Mine(id, "kitsu:$id", "n$id", "r$id", "c$id", "", format, episodes, "", 0, 0.0, average, genres, year, tags)

    private val me = show(1, listOf("Romance", "Comedy", "Action"), listOf("School", "Shounen"), year = 2024)

    @Test fun picksShareMoreWithTheShowFirstThenScoreAndNeverThemselves() {
        val list = listOf(
            show(1, listOf("Romance")),
            show(2, listOf("Romance"), average = 90),
            show(3, listOf("Romance", "Comedy"), listOf("School"), average = 60),
            show(4, listOf("Romance"), average = 95),
            show(5, listOf("Horror")),
            show(6, listOf("Comedy", "Action"), average = 80),
        )
        assertEquals(listOf(3, 6, 4, 2), SimilarPicks.pick(me, list, emptyMap()).map { it.id })
    }

    @Test fun watchedCompletedAndDroppedAreSkippedButPlanningIsKeptAndMarked() {
        val list = (2..7).map { show(it, listOf("Romance")) }
        val mine = mapOf(2 to "COMPLETED", 3 to "CURRENT", 4 to "DROPPED", 5 to "PAUSED", 6 to "REPEATING", 7 to "PLANNING")
        val out = SimilarPicks.pick(me, list, mine)
        assertEquals(listOf(7), out.map { it.id })
        assertEquals("PLANNING", out[0].listed)
    }

    @Test fun atMostFiveAndNothingWhenNothingIsShared() {
        val many = (2..12).map { show(it, listOf("Romance"), average = it) }
        assertEquals(5, SimilarPicks.pick(me, many, emptyMap()).size)
        assertTrue(SimilarPicks.pick(me, listOf(show(9, listOf("Horror"))), emptyMap()).isEmpty())
        assertTrue(SimilarPicks.pick(me, emptyList(), emptyMap()).isEmpty())
    }

    @Test fun reasonsAreOnlyTrueFactsInSimpleTemplatesUpToThree() {
        val c = show(2, listOf("Romance", "Comedy"), listOf("School", "Shounen"), average = 85, year = 2024)
        assertEquals(listOf("Also tagged School", "Also tagged Shounen", "Shares Romance, Comedy"), SimilarPicks.reasons(me, c))
        val few = show(3, listOf("Romance"), average = 80, year = 2024, format = "MOVIE")
        assertEquals(listOf("Shares Romance", "Also from 2024", "Rated 80% on AniList"), SimilarPicks.reasons(me, few))
        val none = show(4, listOf("Romance"), average = 50, year = 2010, format = "OVA")
        assertEquals(listOf("Shares Romance"), SimilarPicks.reasons(me, none))
    }

    @Test fun theStatLineSkipsWhatIsUnknown() {
        val p = SimilarPicks.pick(me, listOf(show(2, listOf("Romance"), average = 85, year = 2024, episodes = 12)), emptyMap())[0]
        assertEquals("★ 85% · 2024 · TV · 12 eps", SimilarPicks.stats(p))
        val bare = p.copy(average = 0, year = 0, format = "", episodes = null)
        assertEquals("", SimilarPicks.stats(bare))
    }

    @Test fun theBrowseQueryAsksForTheShowsFirstTwoGenres() {
        assertEquals(setOf("Romance", "Comedy"), SimilarPicks.query(me).genreIn)
        assertEquals(listOf("SCORE_DESC"), SimilarPicks.query(me).sort)
    }

    @Test fun planToWatchWritesPlanningOnlyWhenTheLiveEntryIsEmpty() = runBlocking {
        val sent = ArrayList<Pair<Int, AniList.Entry>>()
        assertEquals("PLANNING", SimilarPicks.addToPlanning(42, { AniList.Entry() }) { id, e -> sent += id to e })
        assertEquals(listOf(42 to AniList.Entry(status = "PLANNING")), sent)
    }

    @Test fun anExistingEntryIsNeverWrittenOver() = runBlocking {
        val sent = ArrayList<Pair<Int, AniList.Entry>>()
        val done = AniList.Entry(status = "COMPLETED", progress = 12, score = 9.0)
        assertEquals("COMPLETED", SimilarPicks.addToPlanning(42, { done }) { id, e -> sent += id to e })
        assertEquals(emptyList<Pair<Int, AniList.Entry>>(), sent)
    }

    @Test fun theBrowseQueryLeavesOutShowsOnTheList() {
        assertEquals(false, SimilarPicks.query(me).onList)
    }
}
