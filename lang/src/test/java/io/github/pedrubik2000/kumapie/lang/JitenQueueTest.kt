package io.github.pedrubik2000.kumapie.lang

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JitenQueueTest {
    private var now = 1_000_000L
    private val file = File.createTempFile("jitenq", ".json")
    private val ids = mapOf("kitsu:1" to 154587, "kitsu:2" to 2, "kitsu:3" to 3)
    private var idle = true
    private val resolved = mutableListOf<String>()

    private fun queue(t: JitenTransport, store: JitenStore = JitenStore(file) { now }): JitenQueue {
        val paced = JitenPaced(t, 500, { now }, { now += it })
        return JitenQueue(store, JitenApi(paced), { resolved += it; ids[it] }, { idle }, { now })
    }

    private fun drain(q: JitenQueue) = runBlocking { while (q.step()) Unit }

    @Test fun priorityAsksGoFirstInTheirOrder() {
        val t = Canned(::jitenOk)
        val q = queue(t)
        q.ask(listOf("kitsu:3"), false)
        q.ask(listOf("kitsu:1", "kitsu:2"), true)
        drain(q)
        assertEquals(listOf("kitsu:1", "kitsu:2", "kitsu:3"), resolved)
        assertEquals(2.68, q.cached("kitsu:1")!!.raw, 0.0)
        assertNull(q.cached("kitsu:2"))
        assertTrue(q.results.value.containsKey("kitsu:2"))
        assertNotNull(q.results.value["kitsu:1"])
    }

    @Test fun aFailedIdLookupIsNotKeptAsNoMappingAndDoesNotStopTheQueue() {
        var offline = true
        val store = JitenStore(file) { now }
        val paced = JitenPaced(Canned(::jitenOk), 500, { now }, { now += it })
        val q = JitenQueue(store, JitenApi(paced), { if (offline) error("offline") else ids[it] }, { idle }, { now })
        q.ask(listOf("kitsu:1"))
        drain(q)
        assertTrue(!store.knowsKitsu("kitsu:1"))
        offline = false
        now += JitenQueue.RETRY_MS
        q.ask(listOf("kitsu:1"))
        drain(q)
        assertEquals(2.68, q.cached("kitsu:1")!!.raw, 0.0)
    }

    @Test fun twoRequestsASecondAtMost() {
        val stamps = mutableListOf<Long>()
        val q = queue(Canned { stamps += now; jitenOk(it) })
        q.ask(listOf("kitsu:1", "kitsu:2", "kitsu:3"))
        drain(q)
        assertEquals(4, stamps.size)
        assertTrue(stamps.zipWithNext().all { (a, b) -> b - a >= 500 })
    }

    @Test fun notOnJitenIsCachedAWeek() {
        val t = Canned(::jitenOk)
        val q = queue(t)
        q.ask(listOf("kitsu:2"))
        drain(q)
        val n = t.calls.size
        q.ask(listOf("kitsu:2"))
        drain(q)
        assertEquals(n, t.calls.size)
        now += JitenStore.WEEK_MS + 1
        q.ask(listOf("kitsu:2"))
        drain(q)
        assertEquals(n + 1, t.calls.size)
    }

    @Test fun aFailureIsNotCachedAndRetriedLater() {
        var fail = true
        val t = Canned { if (fail) Reply(500, "") else jitenOk(it) }
        val q = queue(t)
        q.ask(listOf("kitsu:1"))
        drain(q)
        assertNull(q.cached("kitsu:1"))
        assertNull(JitenStore(file) { now }.entry(154587))
        fail = false
        q.ask(listOf("kitsu:1"))
        drain(q)
        assertNull(q.cached("kitsu:1"))
        now += JitenQueue.RETRY_MS
        q.ask(listOf("kitsu:1"))
        drain(q)
        assertNotNull(q.cached("kitsu:1"))
    }

    @Test fun retryAfterIsHonoured() {
        var limited = true
        val t = Canned { if (limited) Reply(429, "", 30) else jitenOk(it) }
        val q = queue(t)
        q.ask(listOf("kitsu:1"))
        drain(q)
        assertEquals(1, t.calls.size)
        now += 29_000
        drain(q)
        assertEquals(1, t.calls.size)
        limited = false
        now += 1_000
        drain(q)
        assertNotNull(q.cached("kitsu:1"))
    }

    @Test fun refusedBacksOffSixHoursAndLogsOnce() {
        val logs = mutableListOf<String>()
        val t = Canned { Reply(418, "") }
        val q = JitenQueue(JitenStore(file) { now }, JitenApi(t), { ids[it] }, { true }, { now }, { logs += it })
        q.ask(listOf("kitsu:1"))
        drain(q)
        now += JitenQueue.REFUSED_MS - 1
        drain(q)
        assertEquals(1, t.calls.size)
        now += 1
        drain(q)
        assertEquals(2, t.calls.size)
        assertEquals(2, logs.size)
    }

    @Test fun nothingRunsWhileAStreamPlays() {
        val t = Canned(::jitenOk)
        val q = queue(t)
        q.ask(listOf("kitsu:1"))
        idle = false
        drain(q)
        assertEquals(0, t.calls.size)
        idle = true
        drain(q)
        assertEquals(2, t.calls.size)
    }

    @Test fun levelOfReturnsTheCacheAndAsksWhenMissing() {
        val t = Canned(::jitenOk)
        val q = queue(t)
        assertNull(q.levelOf("kitsu:1"))
        drain(q)
        assertEquals(54, q.levelOf("kitsu:1")!!.percent)
        drain(q)
        assertEquals(2, t.calls.size)
    }

    @Test fun staleValueStaysShownWhileRefreshing() {
        val q = queue(Canned(::jitenOk))
        q.ask(listOf("kitsu:1"))
        drain(q)
        now += JitenStore.WEEK_MS + 1
        assertNotNull(q.levelOf("kitsu:1"))
        assertEquals(listOf("kitsu:1"), resolved)
    }

    @Test fun idsKnownFromTheListAreNotResolvedAgain() {
        val q = queue(Canned(::jitenOk))
        q.know(mapOf("kitsu:1" to 154587))
        q.ask(listOf("kitsu:1"))
        drain(q)
        assertEquals(emptyList<String>(), resolved)
        assertEquals(2.68, q.cached("kitsu:1")!!.raw, 0.0)
    }

    @Test fun droppingAnOwnerRemovesOnlyItsLowPriorityAsksNobodyElseWants() {
        val q = queue(Canned(::jitenOk))
        q.ask(listOf("kitsu:1", "kitsu:2"), false, "library")
        q.ask(listOf("kitsu:2", "kitsu:3"), false)
        q.drop("library")
        drain(q)
        assertEquals(listOf("kitsu:2", "kitsu:3"), resolved)
    }
}
