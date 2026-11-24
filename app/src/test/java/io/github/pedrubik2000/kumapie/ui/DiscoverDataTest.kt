package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.BestForYou.Why
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.lang.TitleDifficulty
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private fun mine(id: Int, average: Int = 0, episodes: Int? = 24) = Mine(id, "k$id", "n$id", "n$id", "", "", "TV", episodes, "PLANNING", 0, 0.0, average, emptyList(), 2020)

class WhyLinesTest {
    private fun why(feature: String = "Fantasy", scored: Int = 3, average: Double = 8.4, finished: Int = 3) = Why(feature, 0.3, scored, average, finished)

    @Test fun aHighAverageSaysSoWithItsFloor() {
        assertEquals(listOf("You score Fantasy 8+ on average"), whyLines(mine(1), listOf(why()), 1.0))
        assertEquals(listOf("You score Fantasy 8+ on average"), whyLines(mine(1), listOf(why(average = 84.0)), 0.1))
        assertEquals(listOf("You score Fantasy 7+ on average"), whyLines(mine(1), listOf(why(average = 3.6)), 2.0))
    }

    @Test fun aLowAverageOrTooFewScoresFallsBackToWhatHeFinishedOrToNothing() {
        assertEquals(listOf("You finished 3 titles with Fantasy"), whyLines(mine(1), listOf(why(average = 6.0)), 1.0))
        assertEquals(listOf("You finished 3 titles with Fantasy"), whyLines(mine(1), listOf(why(scored = 1)), 1.0))
        assertEquals(listOf("You finished 3 titles with Fantasy"), whyLines(mine(1), listOf(why()), null))
        assertEquals(emptyList<String>(), whyLines(mine(1), listOf(why(scored = 1, finished = 1)), 1.0))
    }

    @Test fun theAniListLineNeedsAScoreOf75() {
        assertEquals(listOf("AniList 85"), whyLines(mine(1, average = 85), emptyList(), 1.0))
        assertEquals(emptyList<String>(), whyLines(mine(1, average = 74), emptyList(), 1.0))
        assertEquals(emptyList<String>(), whyLines(mine(1, average = 0), emptyList(), 1.0))
    }

    @Test fun theShortLineNeedsBetweenTwoAndThirteenEpisodes() {
        assertEquals(listOf("Short: 12 episodes"), whyLines(mine(1, episodes = 12), emptyList(), 1.0))
        assertEquals(listOf("Short: 13 episodes"), whyLines(mine(1, episodes = 13), emptyList(), 1.0))
        assertEquals(emptyList<String>(), whyLines(mine(1, episodes = 14), emptyList(), 1.0))
        assertEquals(emptyList<String>(), whyLines(mine(1, episodes = 1), emptyList(), 1.0))
        assertEquals(emptyList<String>(), whyLines(mine(1, episodes = null), emptyList(), 1.0))
    }

    @Test fun atMostThreeLinesFeaturesFirstAndOnlyTheTopTwoFeatures() {
        val lines = whyLines(mine(1, average = 90, episodes = 12), listOf(why("Fantasy"), why("Comedy"), why("Cooking")), 1.0)
        assertEquals(listOf("You score Fantasy 8+ on average", "You score Comedy 8+ on average", "AniList 90"), lines)
    }

    @Test fun scoreFormatsThatCannotBeReadAsTenGiveNoScoreLine() {
        assertEquals(0.1, scaleTo10("POINT_100") ?: 0.0, 1e-9)
        assertEquals(1.0, scaleTo10("POINT_10_DECIMAL") ?: 0.0, 1e-9)
        assertEquals(null, scaleTo10("POINT_3"))
    }
}

class KnockoutTest {
    private fun play(n: Int, first: Boolean = true): List<Pair<Int, Pair<Int, Int>>> {
        var game = Knockout((1..n).toList())
        val seen = ArrayList<Pair<Int, Pair<Int, Int>>>()
        while (true) {
            val pair = game.pair ?: break
            seen += game.round to pair
            game = game.choose(first)
        }
        assertEquals(if (first) 1 else n, game.winner)
        return seen
    }

    @Test fun eightGoToFourThenTwoThenOne() {
        val seen = play(8)
        assertEquals(listOf(1 to (1 to 2), 1 to (3 to 4), 1 to (5 to 6), 1 to (7 to 8), 2 to (1 to 3), 2 to (5 to 7), 3 to (1 to 5)), seen)
        assertEquals(3, Knockout((1..8).toList()).rounds)
    }

    @Test fun anOddFieldGivesTheLastOneAByeAndStillEndsWithOne() {
        assertEquals(listOf(1 to (1 to 2), 1 to (3 to 4), 2 to (1 to 3), 3 to (1 to 5)), play(5))
        assertEquals(3, Knockout((1..5).toList()).rounds)
        assertEquals(listOf(1 to (1 to 2), 2 to (1 to 3)), play(3))
    }

    @Test fun twoIsOneMatchAndTheSameInputPairsTheSameWay() {
        assertEquals(listOf(1 to (1 to 2)), play(2))
        assertEquals(play(7), play(7))
        assertEquals(1, Knockout(listOf(4, 9)).rounds)
    }

    @Test fun aBracketIsUsableOnlyWhileItsRemainingTitlesAreStillRanked() {
        val game = Knockout(listOf(1, 2, 3, 4)).choose(true)
        assertTrue(game.usableWith(setOf(1, 3, 4)))
        assertTrue(!game.usableWith(setOf(1, 2, 4)))
        assertTrue(!game.usableWith(emptySet()))
    }

    @Test fun theOtherSideCanWinToo() {
        play(8, first = false)
    }

    @Test fun droppedAreTheLostAndLeftCountsTheRest() {
        var game = Knockout((1..8).toList())
        assertEquals(8, game.left)
        game = game.choose(false)
        assertEquals(setOf(1), game.dropped)
        game = game.choose(true).choose(true).choose(true)
        assertEquals(setOf(1, 4, 6, 8), game.dropped)
        assertEquals(4, game.left)
        assertEquals(2, game.round)
        assertEquals(null, game.winner)
    }
}

class CandidatesTest {
    private val ranked = listOf(mine(1), mine(2), mine(3), mine(4))

    @Test fun skippedAndNotForMeAreLeftOutKeepingTheOrder() {
        assertEquals(listOf(2, 4), candidates(ranked, setOf(1, 3)).map { it.anilistId })
        assertEquals(listOf(1, 2, 3, 4), candidates(ranked, emptySet()).map { it.anilistId })
        assertEquals(listOf(1, 3, 4), candidates(ranked, setOf(2)).map { it.anilistId })
    }
}

class BrowseViewTest {
    private val levels = mapOf("k1" to 0.5, "k2" to 4.5).mapValues { TitleDifficulty(it.value, 1, null, null) }
    private val results = listOf(mine(1), mine(2), mine(3))

    @Test fun withoutALimitEverythingShowsAndNothingIsCountedUnrated() {
        val v = browseView(results, BrowseFilters()) { levels[it] }
        assertEquals(listOf(1, 2, 3), v.items.map { it.anilistId })
        assertEquals(0, v.unrated)
    }

    @Test fun aLimitHidesTheRatedTitlesAboveItAndKeepsTheUnratedOnes() {
        val v = browseView(results, BrowseFilters(limit = 2)) { levels[it] }
        assertEquals(listOf(1, 3), v.items.map { it.anilistId })
        assertEquals(1, v.unrated)
    }

    @Test fun theQueryCarriesOnlyWhatIsSetAndSearchMatchComesFirstWhenSearching() {
        val q = BrowseFilters(query = " psycho ", sort = BrowseSort.SCORE, genres = ChipPicks(setOf("Mystery"), setOf("Ecchi")), tags = ChipPicks(exclude = setOf("Gore")),
            format = FormatBand.OVA_ONA, airing = AiringBand.FINISHED, mine = BrowseMine.NOT_ON).toQuery(emptyList())
        assertEquals("psycho", q.search)
        assertEquals(listOf("SEARCH_MATCH", "SCORE_DESC"), q.sort)
        assertEquals(setOf("Mystery"), q.genreIn)
        assertEquals(setOf("Ecchi"), q.genreNot)
        assertEquals(setOf("Gore"), q.tagNot)
        assertEquals(setOf("OVA", "ONA", "SPECIAL"), q.formats)
        assertEquals("FINISHED", q.status)
        assertEquals(false, q.onList)
        assertEquals(listOf("POPULARITY_DESC"), BrowseFilters().toQuery(emptyList()).sort)
    }

    @Test fun episodeAndYearBandsBecomeExclusiveBounds() {
        fun q(f: BrowseFilters) = f.toQuery(emptyList())
        assertEquals(0 to 2, q(BrowseFilters(episodes = EpisodeBand.ONE)).let { it.episodesGreater to it.episodesLesser })
        assertEquals(1 to 14, q(BrowseFilters(episodes = EpisodeBand.TWO_TO_13)).let { it.episodesGreater to it.episodesLesser })
        assertEquals(26 to null, q(BrowseFilters(episodes = EpisodeBand.MORE)).let { it.episodesGreater to it.episodesLesser })
        assertEquals(20191231 to null, q(BrowseFilters(year = YearBand.NEW)).let { it.startGreater to it.startLesser })
        assertEquals(20091231 to 20200000, q(BrowseFilters(year = YearBand.TEENS)).let { it.startGreater to it.startLesser })
        assertEquals(null to 20000000, q(BrowseFilters(year = YearBand.OLD)).let { it.startGreater to it.startLesser })
        assertEquals(null, q(BrowseFilters()).idIn)
    }

    @Test fun onlyPlanningAsksForThoseIds() {
        assertEquals(listOf(3, 5), BrowseFilters(mine = BrowseMine.PLANNING).toQuery(listOf(3, 5)).idIn)
    }

    @Test fun theLineNamesTheTotalThePicksAndTheOtherFilters() {
        val f = BrowseFilters(genres = ChipPicks(setOf("Supernatural", "Mystery"), setOf("Ecchi")), format = FormatBand.TV, mine = BrowseMine.NOT_ON, limit = 4)
        assertEquals("312 on AniList · Mystery, Supernatural · no Ecchi · TV · not on my list · up to Hard", browseLine(f, 312, BrowseView(emptyList(), 0)).toString())
        assertEquals("5 on AniList", browseLine(BrowseFilters(), 5, BrowseView(emptyList(), 0)).toString())
    }

    @Test fun anExpiredLoginDropsNotOnMyListAndSaysSo() {
        val f = BrowseFilters(mine = BrowseMine.NOT_ON)
        assertEquals("1 on AniList · AniList login expired: log in again in Settings", browseLine(f, 1, BrowseView(emptyList(), 0), expired = true).toString())
    }

    @Test fun theActiveFiltersAreNamedForAnEmptyResult() {
        assertEquals(listOf("Genres", "Year"), browseActive(BrowseFilters(genres = ChipPicks(setOf("X")), year = YearBand.NEW)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickStoreTest {
    private var now = 1_000_000L
    private val day = 24 * 60 * 60_000L

    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("pickstore-${System.nanoTime()}", 0)

    private fun store(p: android.content.SharedPreferences = prefs()) = PickStore(p) { now }

    @Test fun notForMeIsForever() {
        val store = store()
        store.notForMe(5)
        now += 400 * day
        assertEquals(setOf(5), store.never())
        assertEquals(setOf(5), store.hidden())
    }

    @Test fun aSkipHidesForSevenDaysThenTheTitleComesBack() {
        val store = store()
        store.skip(7)
        now += 6 * day
        assertEquals(setOf(7), store.hidden())
        now += day
        assertEquals(emptySet<Int>(), store.hidden())
        assertEquals(emptySet<Int>(), store.never())
    }

    @Test fun skippingAgainRestartsTheWeekAndExpiredOnesAreDroppedOnWrite() {
        val p = prefs()
        val store = store(p)
        store.skip(7)
        now += 5 * day
        store.skip(8)
        store.skip(7)
        now += 3 * day
        assertEquals(setOf(7, 8), store.hidden())
        now += 5 * day
        store.skip(9)
        assertEquals(setOf(9), store.hidden())
        assertTrue(!p.getString("discover_skipped", "").orEmpty().contains("7:"))
    }

    @Test fun theStoredIdsAreCappedKeepingTheNewest() {
        val store = store()
        (1..520).forEach { store.notForMe(it) }
        assertEquals(500, store.never().size)
        assertTrue(1 !in store.never() && 520 in store.never() && 21 in store.never())
        val skipper = store()
        (1..230).forEach { skipper.skip(it) }
        assertEquals(200, skipper.hidden().size)
        assertTrue(30 !in skipper.hidden() && 31 in skipper.hidden() && 230 in skipper.hidden())
    }

    @Test fun aSkipMadeWithTheClockInTheFutureDoesNotHideTheTitleForGood() {
        val store = store()
        now += 30 * day
        store.skip(7)
        now -= 30 * day
        assertEquals(emptySet<Int>(), store.hidden())
        now += 3 * day
        store.skip(8)
        now -= 3 * day
        assertEquals(setOf(8), store.hidden())
    }

    @Test fun notForMeKeepsTheNameAnOldIdListStillReadsAndUndoRemovesIt() {
        val p = prefs()
        p.edit().putString("discover_never", "5,6").commit()
        assertEquals(listOf(5 to "", 6 to ""), store(p).neverList())
        store(p).notForMe(7, "Dandadan")
        assertEquals(listOf(5 to "", 6 to "", 7 to "Dandadan"), store(p).neverList())
        store(p).undoNotForMe(6)
        assertEquals(setOf(5, 7), store(p).never())
    }

    @Test fun undoingASkipBringsTheTitleBackAtOnce() {
        val store = store()
        store.skip(9)
        assertEquals(setOf(9), store.hidden())
        store.undoSkip(9)
        assertEquals(emptySet<Int>(), store.hidden())
    }

    @Test fun undoLastSaysWhetherItRestoredAnything() {
        val state = PickState(store(), { 0L })
        assertFalse(state.undoLast())
        state.notForMe(5, "x")
        assertTrue(state.undoLast())
        assertEquals(5, state.chosen)
        assertFalse(state.undoLast())
    }

    @Test fun theUndoIsLiveForSixSecondsOnTheClockAndGoneAfter() {
        var t = 1_000L
        val state = PickState(store(), { t })
        state.notForMe(5, "x")
        assertTrue(state.liveUndo() != null)
        t += 5_999
        assertTrue(state.liveUndo() != null)
        t += 2
        assertEquals(null, state.liveUndo())
    }

    @Test fun theUndoSurvivesPressingTheModeAlreadyShownAndDiesOnARealChange() {
        val state = PickState(store(), { 0L })
        state.notForMe(5, "x")
        state.modeChanged(DiscoverMode.PICK, DiscoverMode.PICK)
        assertTrue(state.undo != null)
        state.modeChanged(DiscoverMode.PICK, DiscoverMode.DUEL)
        assertEquals(null, state.undo)
    }

    @Test fun theSameStoreReadAgainSeesWhatWasKept() {
        val p = prefs()
        store(p).notForMe(3)
        store(p).skip(4)
        assertEquals(setOf(3, 4), store(p).hidden())
    }
}
