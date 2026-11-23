package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.LangTotal
import io.github.pedrubik2000.kumapie.data.Mine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLogicTest {
    private fun mine(id: Int, status: String, score: Double = 0.0, average: Int = 0, genres: List<String> = emptyList()) =
        Mine(id, "k$id", "名$id", "n$id", "", "", "TV", 12, status, 0, score, average, genres, 2020)

    @Test fun theStripHasThePreviousTheCurrentAndTheNextThree() {
        val chips = episodeStrip(7, "20 min left", null)
        assertEquals(listOf("Ep 6 · watched", "Ep 7 · 20 min left", "Ep 8", "Ep 9", "Ep 10"), chips.map { it.text })
        assertEquals(listOf(false, true, false, false, false), chips.map { it.current })
    }

    @Test fun theStripStopsAtTheLastEpisodeAndHasNoPreviousForTheFirst() {
        assertEquals(listOf("Ep 1 · Next episode", "Ep 2", "Ep 3"), episodeStrip(1, "Next episode", 3).map { it.text })
        assertEquals(emptyList<StripChip>(), episodeStrip(null, "x", 12))
    }

    @Test fun theRowLabelNamesTheRowsAboveAndBelow() {
        val names = listOf("Continue watching", "Watching on AniList", "Planning on AniList")
        rowNav(names, 1)!!.let {
            assertEquals("2 of 3", it.position)
            assertEquals("Continue watching", it.up)
            assertEquals("Planning on AniList", it.down)
        }
        assertEquals("1 of 3", rowNav(names, 0)!!.position)
        assertNull(rowNav(names, 0)!!.up)
        assertNull(rowNav(names.take(1), 0))
    }

    @Test fun aGenresLineShowsAtMostThreeGenres() {
        assertEquals("Comedy, Super Power, Fantasy", capGenres("Comedy, Super Power, Fantasy, Drama, Action, Friendship, Adventure"))
        assertEquals("A, B", capGenres("A,B"))
        assertEquals("", capGenres(""))
        assertEquals("2020 · A, B, C", io.github.pedrubik2000.kumapie.data.Found("x", "series", "n", "p", "2020", genres = "A, B, C, D, E").line)
    }

    @Test fun theColourFollowsJitensLabelNotThePercentageBands() {
        fun colour(raw: Double) = difficultyColour(io.github.pedrubik2000.kumapie.lang.TitleDifficulty(raw, 1, null, null))
        assertEquals(DifficultyColours.blue, colour(0.5))
        assertEquals(DifficultyColours.green, colour(1.5))
        assertEquals(DifficultyColours.lime, colour(2.95))
        assertEquals(DifficultyColours.orange, colour(3.05))
        assertEquals(DifficultyColours.red, colour(4.5))
        assertEquals(DifficultyColours.purple, colour(4.97))
        assertEquals(59, io.github.pedrubik2000.kumapie.lang.TitleDifficulty(2.95, 1, null, null).percent)
        assertEquals(61, io.github.pedrubik2000.kumapie.lang.TitleDifficulty(3.05, 1, null, null).percent)
    }

    @Test fun theStatsLineSaysEveryFigureWithItsLabel() {
        val line = statsLine(LangTotal(today = 17 * 60.0, week = (9 * 60 + 22) * 60.0, total = 132480.0, known = 17445))
        assertEquals("17 min today · 9 h 22 min last 7 days · ${"%.1f".format(36.8)} h in total · ${"%,d".format(17445)} words known", line)
    }

    @Test fun theAniListCardsSayTheirProgressAndStatus() {
        val m = mine(1, "CURRENT").copy(progress = 11, episodes = 28, average = 90)
        assertEquals("Ep 11 of 28", m.progressLine())
        assertEquals("Ep 3", m.copy(progress = 3, episodes = null).progressLine())
        assertEquals("Watching", m.statusName())
        assertEquals("Planning", m.copy(status = "PLANNING").statusName())
        assertEquals(listOf("On AniList: Watching", "Ep 11 of 28", "AniList 90"), ListTarget(m).chips())
        assertEquals(listOf("AniList 90"), ListTarget(m.copy(status = "PLANNING")).chips())
    }

    @Test fun planningFollowsBestForYouAndLeavesTheRestOut() {
        val all = listOf(
            mine(1, "COMPLETED", score = 9.0, genres = listOf("Drama")),
            mine(2, "DROPPED", score = 3.0, genres = listOf("Sports")),
            mine(3, "PLANNING", average = 70, genres = listOf("Sports")),
            mine(4, "PLANNING", average = 70, genres = listOf("Drama")),
            mine(5, "CURRENT", genres = listOf("Drama")),
        )
        assertEquals(listOf(4, 3), planningOrder(all).map { it.anilistId })
    }

    @Test fun aListTitleOpensTheTitlePageAsDiscoversRowsDo() {
        val f = mine(7, "CURRENT", average = 85, genres = listOf("Drama", "Fantasy")).found()
        assertEquals("kitsu:k7", f.id)
        assertEquals("anime", f.type)
        assertEquals("名7", f.name)
        assertEquals("Drama, Fantasy", f.genres)
        assertEquals("8.5", f.rating)
    }
}
