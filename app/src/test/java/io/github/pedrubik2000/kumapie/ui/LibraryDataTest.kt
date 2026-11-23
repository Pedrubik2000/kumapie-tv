package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.lang.TitleDifficulty
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryDataTest {
    private fun mine(id: Int, status: String, progress: Int = 0, average: Int = 0, name: String = "n$id", episodes: Int? = 12) =
        Mine(id, "k$id", name, name, "", "", "TV", episodes, status, progress, 0.0, average, emptyList(), 2020)

    private val all = listOf(
        mine(1, "CURRENT", 3, 70, "b"), mine(2, "REPEATING", 1, 90, "a"), mine(3, "PLANNING", 0, 80, "d"), mine(4, "PLANNING", 0, 0, "c"),
        mine(5, "PLANNING", 2, 60, "e"), mine(6, "COMPLETED", 12), mine(7, "PAUSED", 4), mine(8, "DROPPED", 1),
    )
    private val levels = mapOf("k3" to 0.5, "k4" to 4.5, "k5" to 2.5, "k1" to 1.5).mapValues { TitleDifficulty(it.value, 1, null, null) }
    private fun view(list: LibraryList, f: LibraryFilters = LibraryFilters(), kept: Set<String> = emptySet()) = libraryView(all, list, f, kept) { levels[it] }
    private fun ids(v: LibraryView) = v.items.map { it.anilistId }

    @Test fun eachListCountsItsStatuses() {
        assertEquals(listOf(2, 3, 1, 1, 1), LibraryList.entries.map { it.of(all).size })
        assertEquals(listOf(1, 2), ids(view(LibraryList.WATCHING)))
    }

    @Test fun inProgressKeepsTitlesWithEpisodesWatched() {
        assertEquals(listOf(5), ids(view(LibraryList.PLANNING, LibraryFilters(inProgress = true))))
    }

    @Test fun onHomeKeepsTheSavedTitles() {
        assertEquals(listOf(4), ids(view(LibraryList.PLANNING, LibraryFilters(onHome = true), setOf("kitsu:k4"))))
        assertEquals(emptyList<Int>(), ids(view(LibraryList.PLANNING, LibraryFilters(onHome = true))))
    }

    @Test fun aLimitKeepsRatedTitlesAtOrBelowItAndCountsTheUnrated() {
        val v = view(LibraryList.PLANNING, LibraryFilters(limit = 3))
        assertEquals(listOf(3, 5), ids(v))
        assertEquals(0, v.unrated)
        val none = view(LibraryList.WATCHING, LibraryFilters(limit = 1))
        assertEquals(emptyList<Int>(), ids(none))
        assertEquals(1, none.unrated)
        assertEquals(listOf(3), ids(view(LibraryList.PLANNING, LibraryFilters(limit = 1))))
        assertEquals(listOf(3, 5, 4), ids(view(LibraryList.PLANNING, LibraryFilters(limit = 5, sort = LibrarySort.EASIEST))))
    }

    @Test fun theLimitAlsoRespectsTheCheckboxesWhenCountingUnrated() {
        val v = view(LibraryList.WATCHING, LibraryFilters(onHome = true, limit = 1), setOf("kitsu:k1"))
        assertEquals(emptyList<Int>(), ids(v))
        assertEquals(0, v.unrated)
    }

    @Test fun withoutALimitUnratedTitlesShowAndAreNotCounted() {
        val v = view(LibraryList.WATCHING, LibraryFilters())
        assertEquals(2, v.items.size)
        assertEquals(0, v.unrated)
    }

    @Test fun easiestFirstPutsUnratedLast() {
        assertEquals(listOf(3, 5, 4), ids(view(LibraryList.PLANNING, LibraryFilters(sort = LibrarySort.EASIEST))))
        assertEquals(listOf(1, 2), ids(view(LibraryList.WATCHING, LibraryFilters(sort = LibrarySort.EASIEST))))
    }

    @Test fun aniListScoreIsHighestFirstAndUnratedLast() {
        assertEquals(listOf(3, 5, 4), ids(view(LibraryList.PLANNING, LibraryFilters(sort = LibrarySort.SCORE))))
    }

    @Test fun titleSortIsAlphabeticalAndMyOrderKeepsTheListOrder() {
        assertEquals(listOf(4, 3, 5), ids(view(LibraryList.PLANNING, LibraryFilters(sort = LibrarySort.TITLE))))
        assertEquals(listOf(3, 4, 5), ids(view(LibraryList.PLANNING)))
    }

    @Test fun theCountLineSaysTitlesOrHowManyAreShown() {
        assertEquals("Planning · 3 titles", libraryLine(LibraryList.PLANNING, LibraryFilters(), view(LibraryList.PLANNING)))
        val f = LibraryFilters(inProgress = true)
        assertEquals("Planning · 1 of 3 shown", libraryLine(LibraryList.PLANNING, f, view(LibraryList.PLANNING, f)))
        val g = LibraryFilters(limit = 1)
        assertEquals("Watching · 0 of 2 shown · 1 not rated yet", libraryLine(LibraryList.WATCHING, g, view(LibraryList.WATCHING, g)))
    }

    @Test fun theLimitStepsNameJitensSixLabels() {
        assertEquals(listOf("Any", "Beginner", "Easy", "Average", "Hard", "Expert"), (0..LIMIT_MAX).map(::limitLabel))
    }

    @Test fun theStatusLineSaysWhereTheTitleIs() {
        assertEquals("PLANNING · NOT STARTED", libraryStatus(all[2]))
        assertEquals("WATCHING · 3 / 12", libraryStatus(all[0]))
        assertEquals("COMPLETED", libraryStatus(all[5]))
        assertEquals("PAUSED · EP 4", libraryStatus(mine(9, "PAUSED", 4, episodes = null)))
    }
}
