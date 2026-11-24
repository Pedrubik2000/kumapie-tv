package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.AniListBrowse
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowseStateTest {
    @org.junit.Before fun noGap() { browseGapMs = 0 }

    private fun state() = BrowseState(BrowseFilters(), Settings(RuntimeEnvironment.getApplication()))

    private fun m(id: Int) = Mine(id, "k$id", "n$id", "n$id", "", "", "TV", 12, "", 0, 0.0, 80, emptyList(), 2020)

    private fun page(vararg ids: Int, next: Boolean = true) = AniListBrowse.Page(ids.map(::m), 100, next)

    private val q = AniListBrowse.Query()

    @Test fun aLateMorePageFromAnOldQueryIsDroppedAndDoesNotMovePaging() = runBlocking {
        val s = state()
        s.first("a", q, { _, _ -> page(1) })
        val late = CompletableDeferred<AniListBrowse.Page>()
        val more = async(start = CoroutineStart.UNDISPATCHED) { s.more { _, _ -> late.await() } }
        val release = async { kotlinx.coroutines.delay(100); late.complete(page(3)) }
        s.first("b", q, { _, _ -> page(2) })
        release.await()
        more.join()
        assertEquals(listOf(2), s.items.map { it.anilistId })
        assertEquals(1, s.page)
    }

    @Test fun aFilterChangeWaitsTheDebounceButTheFirstLoadAndARetryDoNot() = runBlocking {
        val s = state()
        var t = System.nanoTime()
        s.first("a", q, { _, _ -> page(1) })
        assertTrue((System.nanoTime() - t) / 1_000_000 < 300)
        t = System.nanoTime()
        s.first("b", q, { _, _ -> page(2) })
        assertTrue((System.nanoTime() - t) / 1_000_000 >= 350)
        s.retry()
        t = System.nanoTime()
        s.first("c", q, { _, _ -> page(3) })
        assertTrue((System.nanoTime() - t) / 1_000_000 < 300)
    }

    @Test fun anEmptyFirstPageLoadsTheNextOnesUpToThree() = runBlocking {
        val asked = ArrayList<Int>()
        val s = state()
        s.first("a", q, { _, p -> asked += p; AniListBrowse.Page(emptyList(), 100, true) })
        assertEquals(listOf(1, 2, 3), asked)
        assertTrue(s.items.isEmpty() && s.loaded && s.page == 3)
        val t = state()
        t.first("a", q, { _, p -> if (p == 2) page(9) else AniListBrowse.Page(emptyList(), 100, true) })
        assertEquals(listOf(9), t.items.map { it.anilistId })
    }

    @Test fun pagingAdvancesEvenWhenAPageAddsNothingNew() = runBlocking {
        val s = state()
        s.first("a", q, { _, _ -> page(1) })
        s.more { _, _ -> page(1) }
        assertEquals(2, s.page)
        assertEquals(1, s.items.size)
    }

    @Test fun aFailedMoreKeepsTheResultsAndRetryAsksThatPageAgain() = runBlocking {
        val s = state()
        s.first("a", q, { _, _ -> page(1) })
        s.more { _, _ -> error("down") }
        assertEquals("down", s.moreError)
        assertEquals(listOf(1), s.items.map { it.anilistId })
        assertEquals(1, s.page)
        assertEquals(null, s.error)
        s.retryMore()
        s.more { _, p -> assertEquals(2, p); page(2, next = false) }
        assertEquals(listOf(1, 2), s.items.map { it.anilistId })
        assertEquals(null, s.moreError)
    }

    @Test fun anEmptyPlanningListAsksNothingAndSaysSo() = runBlocking {
        val s = state()
        var asked = 0
        s.first("a", null, { _, _ -> asked++; page(1) })
        assertEquals(0, asked)
        assertEquals("Your Planning list is empty", s.emptyNote)
        assertTrue(s.loaded && s.items.isEmpty())
    }

    @Test fun backToAnAnswerAlreadyShownAfterACancelledFilterLeavesItLoaded() = runBlocking {
        val s = state()
        s.first("a", q, { _, _ -> page(1) })
        val b = async(start = CoroutineStart.UNDISPATCHED) { s.first("b", q, { _, _ -> page(2) }) }
        b.cancel()
        s.first("a", q, { _, _ -> error("must not ask again") })
        assertTrue(s.loaded)
        assertEquals(listOf(1), s.items.map { it.anilistId })
    }

    @Test fun backToAAfterBWasCancelledMidFetchLoadsAAgain() = runBlocking {
        val s = state()
        s.first("a", q, { _, _ -> page(1) })
        val gate = CompletableDeferred<AniListBrowse.Page>()
        val b = async(start = CoroutineStart.UNDISPATCHED) { s.first("b", q, { _, _ -> gate.await() }) }
        kotlinx.coroutines.delay(700)
        assertTrue(!s.loaded)
        b.cancel()
        s.first("a", q, { _, _ -> page(1) })
        assertTrue(s.loaded)
        assertEquals(listOf(1), s.items.map { it.anilistId })
    }

    @Test fun moreIsNotAskedWhileAFilterChangeIsWaiting() = runBlocking {
        val s = state()
        s.first("a", q, { _, _ -> page(1) })
        val b = async(start = CoroutineStart.UNDISPATCHED) { s.first("b", q, { _, _ -> page(2) }) }
        val tick = s.moreTick
        s.requestMore()
        s.more { _, _ -> error("must not ask") }
        assertEquals(tick, s.moreTick)
        assertEquals(1, s.page)
        b.await()
        assertEquals(listOf(2), s.items.map { it.anilistId })
    }

    @Test fun anExpiredLoginIsRememberedFromAnyPageUntilANewLoginClearsIt() = runBlocking {
        val s = state()
        s.first("a", q, { _, _ -> AniListBrowse.Page(listOf(m(1)), 5, true, expired = true) })
        assertTrue(s.expired)
        s.first("b", q, { _, _ -> page(1) })
        assertTrue(s.expired)
        s.clearExpired()
        assertTrue(!s.expired)
        s.more { _, _ -> AniListBrowse.Page(listOf(m(2)), 5, false, expired = true) }
        assertTrue(s.expired)
    }

    @Test fun aTooManyRequestsAnswerWaitsThenAsksAgainAtMostTwiceShowingWaiting() = runBlocking {
        val s = state()
        var asks = 0
        var sawWaiting = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            s.first("a", q, { _, _ -> asks++; if (asks <= 2) throw io.github.pedrubik2000.kumapie.data.AniList.Failed(429, "AniList: too many requests", 1) else page(1) })
        }
        kotlinx.coroutines.delay(300)
        sawWaiting = s.waiting
        job.await()
        assertTrue(sawWaiting)
        assertEquals(3, asks)
        assertTrue(!s.waiting && s.loaded && s.error == null)
    }

    @Test fun aThirdTooManyRequestsAnswerIsAnError() = runBlocking {
        val s = state()
        var asks = 0
        s.first("a", q, { _, _ -> asks++; throw io.github.pedrubik2000.kumapie.data.AniList.Failed(429, "AniList: too many requests", 1) })
        assertEquals(3, asks)
        assertEquals("AniList: too many requests", s.error)
    }

    @Test fun requestsAreSpacedByTheGapAndNeverOverlap() = runBlocking {
        browseGapMs = 300
        val s = state()
        val starts = ArrayList<Long>()
        var running = 0
        var most = 0
        val fetch: BrowseFetch = { _, _ ->
            starts += System.nanoTime() / 1_000_000
            running++; most = maxOf(most, running)
            kotlinx.coroutines.delay(50)
            running--
            page(1)
        }
        s.first("a", q, fetch)
        val more = async { s.more(fetch) }
        more.await()
        assertEquals(1, most)
        assertTrue(starts[1] - starts[0] >= 280)
    }

    @Test fun aFilterChangeCancelsAMoreThatIsWaitingOutATooManyRequestsAnswer() = runBlocking {
        val s = state()
        s.first("a", q, { _, _ -> page(1) })
        val more = async(start = CoroutineStart.UNDISPATCHED) {
            s.more { _, _ -> throw io.github.pedrubik2000.kumapie.data.AniList.Failed(429, "AniList: too many requests", 60) }
        }
        kotlinx.coroutines.delay(50)
        assertTrue(s.waiting)
        val t = System.nanoTime()
        s.first("b", q, { _, _ -> page(2) })
        assertTrue((System.nanoTime() - t) / 1_000_000 < 3_000)
        more.join()
        assertTrue(more.isCancelled)
        assertEquals(listOf(2), s.items.map { it.anilistId })
        assertTrue(!s.waiting)
    }

    @Test fun theSpacingAndTheLockAreSharedByEveryBrowseState() = runBlocking {
        browseGapMs = 300
        val starts = ArrayList<Long>()
        val fetch: BrowseFetch = { _, _ -> starts += System.nanoTime() / 1_000_000; page(1) }
        state().first("a", q, fetch)
        state().first("a", q, fetch)
        assertTrue(starts[1] - starts[0] >= 280)
    }
}
