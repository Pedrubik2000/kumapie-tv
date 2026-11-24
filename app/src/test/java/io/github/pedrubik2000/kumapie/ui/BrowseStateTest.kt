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
    private fun state() = BrowseState(BrowseFilters(), Settings(RuntimeEnvironment.getApplication()))

    private fun m(id: Int) = Mine(id, "k$id", "n$id", "n$id", "", "", "TV", 12, "", 0, 0.0, 80, emptyList(), 2020)

    private fun page(vararg ids: Int, next: Boolean = true) = AniListBrowse.Page(ids.map(::m), 100, next)

    private val q = AniListBrowse.Query()

    @Test fun aLateMorePageFromAnOldQueryIsDroppedAndDoesNotMovePaging() = runBlocking {
        val s = state()
        s.first("a", q, { _, _ -> page(1) })
        val late = CompletableDeferred<AniListBrowse.Page>()
        val more = async(start = CoroutineStart.UNDISPATCHED) { s.more { _, _ -> late.await() } }
        s.first("b", q, { _, _ -> page(2) })
        late.complete(page(3))
        more.await()
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

    @Test fun anExpiredLoginIsRememberedFromAnyPageAndClearsOnTheNextLoad() = runBlocking {
        val s = state()
        s.first("a", q, { _, _ -> AniListBrowse.Page(listOf(m(1)), 5, true, expired = true) })
        assertTrue(s.expired)
        s.first("b", q, { _, _ -> page(1) })
        assertTrue(!s.expired)
        s.more { _, _ -> AniListBrowse.Page(listOf(m(2)), 5, false, expired = true) }
        assertTrue(s.expired)
    }
}
