package io.github.pedrubik2000.kumapie.lang

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** LAN sync often: the debouncer, the 15-minute rule and a peer's ratings landing on the cards at once. */
class SyncDebounceTest {
    private fun wait(until: () -> Boolean) { val end = System.currentTimeMillis() + 5_000; while (!until() && System.currentTimeMillis() < end) Thread.sleep(5) }

    @Test fun aBurstIsOneSync() {
        val runs = AtomicInteger()
        val d = SyncDebounce(CoroutineScope(Dispatchers.Default), 100) { runs.incrementAndGet() }
        repeat(10) { d.request(); Thread.sleep(3) }
        wait { runs.get() >= 1 }
        Thread.sleep(300)
        assertEquals(1, runs.get())
        d.request(); wait { runs.get() == 2 }
        assertEquals(2, runs.get())
    }

    @Test fun aRequestDuringASyncMakesExactlyOneFollowUp() {
        val runs = AtomicInteger()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val d = SyncDebounce(CoroutineScope(Dispatchers.Default), 50) { if (runs.incrementAndGet() == 1) { inside.countDown(); release.await() } }
        d.request()
        inside.await()
        repeat(5) { d.request() }
        release.countDown()
        wait { runs.get() >= 2 }
        Thread.sleep(300)
        assertEquals(2, runs.get())
    }

    @Test fun aFailureIsSilentAndTheNextRequestStillRuns() {
        val runs = AtomicInteger()
        val d = SyncDebounce(CoroutineScope(Dispatchers.Default), 20) { runs.incrementAndGet(); error("offline") }
        d.request(); wait { runs.get() == 1 }; Thread.sleep(100)
        d.request(); wait { runs.get() == 2 }
        assertEquals(2, runs.get())
    }

    @Test fun openingTheAppSyncsUnlessTheLastGoodOneIsUnderAMinuteOld() {
        var now = 10_000_000L
        val last = now - 50_000
        assertFalse(DeviceSync.due(last, now))
        now += 20_000
        assertTrue(DeviceSync.due(last, now))
        assertTrue(DeviceSync.due(0L, now)) // never synced
    }

    @Test fun aPeersEventForACardHereIsTakenOnceAndNotAgainAfterItLanded() {
        val events = JSONArray().put(JSONObject().put("id", 7L).put("key", "ja|犬").put("time", 1L).put("ease", 3).put("device", "Tab"))
            .put(JSONObject().put("id", 8L).put("key", "ja|猫").put("time", 2L).put("ease", 3).put("device", "Tab")) // no card here
        val cards = mapOf(42L to "ja|犬")
        val first = WordSync.toAdd(events, cards, emptySet())
        assertEquals(listOf(7L to 42L), first.map { it.first.getLong("id") to it.second })
        // Srs.take inserts the id (CONFLICT_IGNORE on the revlog id); the next share() has it in revlogIds: nothing to add.
        assertTrue(WordSync.toAdd(events, cards, setOf(7L)).isEmpty())
    }

    @Test fun noRequestIsLostWhenItLandsAsASyncEnds() {
        val last = java.util.concurrent.atomic.AtomicLong()
        val d = SyncDebounce(CoroutineScope(Dispatchers.Default), 0) { last.set(System.nanoTime()); Thread.sleep(1) }
        repeat(300) {
            val asked = System.nanoTime()
            d.request()
            wait { last.get() > asked }
            assertTrue("request $it never got a run after it", last.get() > asked)
        }
    }

    @Test fun ratingsSyncOnlyOnAnUnmeteredNetworkAndNotForFifteenMinutesAfterAFailure() {
        val now = 100_000_000L
        assertTrue(DeviceSync.allowed(true, 0L, now))
        assertFalse(DeviceSync.allowed(false, 0L, now))
        assertFalse(DeviceSync.allowed(true, now - 5 * 60_000, now))
        assertTrue(DeviceSync.allowed(true, now - 16 * 60_000, now))
    }

    @Test fun cardReloadsAreOneAtATimeAndARequestMeanwhileMakesOneMore() {
        val runs = AtomicInteger()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reload = SyncDebounce(CoroutineScope(Dispatchers.Default), 0) { if (runs.incrementAndGet() == 1) { inside.countDown(); release.await() } }
        reload.request()
        inside.await()
        repeat(4) { reload.request() }
        assertEquals(1, runs.get())
        release.countDown()
        wait { runs.get() >= 2 }
        Thread.sleep(200)
        assertEquals(2, runs.get())
    }

    @Test fun aMeteredVpnOverUnmeteredWifiStillSyncsButCellularOnlyDoesNot() {
        assertTrue(DeviceSync.cheapLink(true, listOf(false to false, true to true))) // Tailscale active (metered) + Wi-Fi
        assertFalse(DeviceSync.cheapLink(true, listOf(false to false))) // cellular only
        assertFalse(DeviceSync.cheapLink(true, listOf(true to false))) // metered hotspot Wi-Fi
        assertTrue(DeviceSync.cheapLink(false, emptyList()))
    }
}
