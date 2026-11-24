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
    private fun view(list: LibraryList, f: LibraryFilters = LibraryFilters()) = libraryView(all, list, f) { levels[it] }
    private fun ids(v: LibraryView) = v.items.map { it.anilistId }

    @Test fun eachListCountsItsStatuses() {
        assertEquals(listOf(2, 3, 1, 1, 1), LibraryList.entries.map { it.of(all).size })
        assertEquals(listOf(1, 2), ids(view(LibraryList.WATCHING)))
    }

    @Test fun inProgressKeepsTitlesWithEpisodesWatched() {
        assertEquals(listOf(5), ids(view(LibraryList.PLANNING, LibraryFilters(inProgress = true))))
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

    @Test fun theLimitAlsoRespectsTheOtherFiltersWhenCountingUnrated() {
        val v = view(LibraryList.WATCHING, LibraryFilters(query = "b", limit = 1))
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
        assertEquals("Planning · 3 titles", libraryLine(LibraryList.PLANNING, LibraryFilters(), view(LibraryList.PLANNING)).toString())
        val f = LibraryFilters(inProgress = true)
        assertEquals("Planning · 1 of 3 shown", libraryLine(LibraryList.PLANNING, f, view(LibraryList.PLANNING, f)).toString())
        val g = LibraryFilters(limit = 1)
        assertEquals("Watching · 0 of 2 shown · 1 not rated yet", libraryLine(LibraryList.WATCHING, g, view(LibraryList.WATCHING, g)).toString())
        assertEquals("Completed · 1 title", libraryLine(LibraryList.COMPLETED, LibraryFilters(), view(LibraryList.COMPLETED)).toString())
    }

    private val rich = listOf(
        mine(1, "PLANNING").copy(genres = listOf("Mystery", "Psychological"), tags = listOf("Detective"), format = "TV", episodes = 22, year = 2012, airing = "FINISHED"),
        mine(2, "PLANNING").copy(genres = listOf("Mystery", "Ecchi"), tags = listOf("School"), format = "MOVIE", episodes = 1, year = 2005, airing = "FINISHED"),
        mine(3, "PLANNING").copy(genres = listOf("Action"), tags = emptyList(), format = "TV_SHORT", episodes = 12, year = 2024, airing = "RELEASING"),
        mine(4, "PLANNING").copy(genres = emptyList(), format = "OVA", episodes = 3, year = 1998, airing = "NOT_YET_RELEASED"),
        mine(5, "PLANNING", episodes = null).copy(year = 0),
    )

    private fun rich(f: LibraryFilters) = libraryView(rich, LibraryList.PLANNING, f) { null }.items.map { it.anilistId }

    @Test fun includedGenresMustAllBePresentAndExcludedOnesAbsent() {
        assertEquals(listOf(1, 2), rich(LibraryFilters(genres = ChipPicks(include = setOf("Mystery")))))
        assertEquals(listOf(1), rich(LibraryFilters(genres = ChipPicks(include = setOf("Mystery", "Psychological")))))
        assertEquals(listOf(1), rich(LibraryFilters(genres = ChipPicks(include = setOf("Mystery"), exclude = setOf("Ecchi")))))
        assertEquals(listOf(1, 3, 4, 5), rich(LibraryFilters(genres = ChipPicks(exclude = setOf("Ecchi")))))
    }

    @Test fun tagsFilterTheSameWay() {
        assertEquals(listOf(1), rich(LibraryFilters(tags = ChipPicks(include = setOf("Detective")))))
        assertEquals(listOf(1, 3, 4, 5), rich(LibraryFilters(tags = ChipPicks(exclude = setOf("School")))))
    }

    @Test fun aPickCyclesOffIncludeExcludeOff() {
        val a = ChipPicks().cycle("X")
        assertEquals(ChipPicks(include = setOf("X")), a)
        val b = a.cycle("X")
        assertEquals(ChipPicks(exclude = setOf("X")), b)
        assertEquals(ChipPicks(), b.cycle("X"))
    }

    @Test fun formatEpisodesYearAndAiringBandsFilterAndWhatIsUnknownMatchesEveryBand() {
        assertEquals(listOf(1, 5), rich(LibraryFilters(format = FormatBand.TV)))
        assertEquals(listOf(2), rich(LibraryFilters(format = FormatBand.MOVIE)))
        assertEquals(listOf(4), rich(LibraryFilters(format = FormatBand.OVA_ONA)))
        assertEquals(listOf(3), rich(LibraryFilters(format = FormatBand.SHORT)))
        assertEquals(listOf(2, 5), rich(LibraryFilters(episodes = EpisodeBand.ONE)))
        assertEquals(listOf(3, 4, 5), rich(LibraryFilters(episodes = EpisodeBand.TWO_TO_13)))
        assertEquals(listOf(1, 5), rich(LibraryFilters(episodes = EpisodeBand.FOURTEEN_TO_26)))
        assertEquals(listOf(3, 5), rich(LibraryFilters(year = YearBand.NEW)))
        assertEquals(listOf(1, 5), rich(LibraryFilters(year = YearBand.TEENS)))
        assertEquals(listOf(2, 5), rich(LibraryFilters(year = YearBand.NOUGHTS)))
        assertEquals(listOf(4, 5), rich(LibraryFilters(year = YearBand.OLD)))
        assertEquals(listOf(1, 2, 5), rich(LibraryFilters(airing = AiringBand.FINISHED)))
        assertEquals(listOf(3, 5), rich(LibraryFilters(airing = AiringBand.AIRING)))
        assertEquals(listOf(4, 5), rich(LibraryFilters(airing = AiringBand.NOT_YET)))
    }

    @Test fun theSearchMatchesTheNativeOrRomajiNameIgnoringCase() {
        val named = listOf(mine(1, "PLANNING").copy(native = "サイコパス", romaji = "Psycho-Pass"), mine(2, "PLANNING").copy(native = "千年女優", romaji = "Millennium Actress"))
        fun ids(q: String) = libraryView(named, LibraryList.PLANNING, LibraryFilters(query = q)) { null }.items.map { it.anilistId }
        assertEquals(listOf(1), ids("psycho"))
        assertEquals(listOf(2), ids("千年"))
        assertEquals(listOf(1, 2), ids("  "))
    }

    @Test fun yearSortIsNewestFirstAndUnknownLast() {
        assertEquals(listOf(3, 1, 2, 4, 5), libraryView(rich, LibraryList.PLANNING, LibraryFilters(sort = LibrarySort.YEAR)) { null }.items.map { it.anilistId })
    }

    @Test fun theCountLineNamesTheIncludedAndExcludedPicks() {
        val f = LibraryFilters(genres = ChipPicks(include = setOf("Psychological", "Mystery"), exclude = setOf("Ecchi")))
        val v = libraryView(rich, LibraryList.PLANNING, f) { null }
        assertEquals("Planning · 1 of 5 shown · Mystery, Psychological · no Ecchi", libraryLine(LibraryList.PLANNING, f, v).toString())
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
