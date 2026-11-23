package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.BestForYou.Why
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.lang.TitleDifficulty
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

    @Test fun skippedNotForMeAndOnHomeAreAllLeftOutKeepingTheOrder() {
        assertEquals(listOf(4), candidates(ranked, setOf(1, 3), setOf("kitsu:k2")).map { it.anilistId })
        assertEquals(listOf(1, 2, 3, 4), candidates(ranked, emptySet(), emptySet()).map { it.anilistId })
        assertEquals(listOf(1, 3), candidates(ranked, setOf(2), setOf("kitsu:k4")).map { it.anilistId })
    }
}

class BrowseViewTest {
    private fun found(id: String) = Found(id, "series", id, "", "2024")

    private val rows = listOf("Popular" to listOf(found("kitsu:1"), found("kitsu:2"), found("kitsu:3")), "New" to listOf(found("kitsu:3"), found("tt9")))
    private val levels = mapOf("1" to 0.5, "2" to 4.5).mapValues { TitleDifficulty(it.value, 1, null, null) }
    private fun ids(v: BrowseView) = v.items.map { it.id }
    private fun view(f: BrowseFilters, onList: Set<String> = emptySet(), results: List<Found> = emptyList()) = browseView(rows, results, f, onList) { levels[it] }

    @Test fun allIsEveryCatalogWithoutRepeats() {
        assertEquals(listOf("kitsu:1", "kitsu:2", "kitsu:3", "tt9"), ids(view(BrowseFilters())))
        assertEquals(listOf("kitsu:3", "tt9"), ids(view(BrowseFilters(catalog = "New"))))
        assertEquals(emptyList<String>(), ids(view(BrowseFilters(catalog = "Gone"))))
    }

    @Test fun aSearchReplacesTheCatalogs() {
        assertEquals(listOf("kitsu:7"), ids(view(BrowseFilters(catalog = "New", query = "z"), results = listOf(found("kitsu:7"), found("kitsu:7")))))
    }

    @Test fun notOnMyListHidesWhatHisListsHold() {
        val v = view(BrowseFilters(notOnList = true), setOf("2", "3"))
        assertEquals(listOf("kitsu:1", "tt9"), ids(v))
        assertEquals(4, v.total)
    }

    @Test fun aLimitKeepsRatedTitlesAtOrBelowItAndCountsTheUnrated() {
        val v = view(BrowseFilters(limit = 2))
        assertEquals(listOf("kitsu:1"), ids(v))
        assertEquals(2, v.unrated)
        assertEquals(listOf("kitsu:1", "kitsu:2"), ids(view(BrowseFilters(limit = 5))))
        assertEquals(0, view(BrowseFilters()).unrated)
    }

    @Test fun theLimitAndMyListWorkTogether() {
        val v = view(BrowseFilters(limit = 5, notOnList = true), setOf("1"))
        assertEquals(listOf("kitsu:2"), ids(v))
        assertEquals(2, v.unrated)
    }

    @Test fun withoutALimitNothingIsAskedBeyondTheVisibleCovers() {
        assertEquals(emptyList<String>(), browseAskIds(rows, emptyList(), BrowseFilters()) { false })
    }

    @Test fun withALimitTheUnratedOfTheScopeAreAskedInOrderAndAllIsCappedAtSixty() {
        val big = listOf("Big" to (1..100).map { found("kitsu:$it") })
        val rated = setOf("2")
        assertEquals((1..62).map { "$it" }.filter { it != "2" }.take(60), browseAskIds(big, emptyList(), BrowseFilters(limit = 3)) { it in rated })
        assertEquals(99, browseAskIds(big, emptyList(), BrowseFilters(limit = 3, catalog = "Big")) { it in rated }.size)
        assertEquals(listOf("3"), browseAskIds(rows, emptyList(), BrowseFilters(limit = 3, catalog = "New")) { false })
        assertEquals(emptyList<String>(), browseAskIds(rows, emptyList(), BrowseFilters(limit = 3, catalog = "New")) { true })
        assertEquals(listOf("7"), browseAskIds(rows, listOf(found("kitsu:7"), found("tt1")), BrowseFilters(limit = 3, query = "z")) { false })
    }

    @Test fun theLineSaysHowManyAreShownAndHowManyAreUnrated() {
        assertEquals("4 titles", browseLine(BrowseFilters(), view(BrowseFilters())))
        val f = BrowseFilters(limit = 2)
        assertEquals("1 of 4 shown · 2 not rated yet", browseLine(f, view(f)))
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

    @Test fun theSameStoreReadAgainSeesWhatWasKept() {
        val p = prefs()
        store(p).notForMe(3)
        store(p).skip(4)
        assertEquals(setOf(3, 4), store(p).hidden())
    }
}
