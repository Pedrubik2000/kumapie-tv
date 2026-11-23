package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.AniList.Entry
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AniListSyncReadTest {
    private val prefs = Settings(RuntimeEnvironment.getApplication()).prefs
    private val gate = CompletableDeferred<Unit>()
    private var server = Entry(status = "CURRENT", progress = 1, episodes = 3)

    private val api = AniListSync.Api(
        lookup = { 5 }, entry = { _, _ -> server },
        save = { _, _, _, after -> gate.await(); server = after; after },
        format = { "POINT_10" },
    )

    private val sync get() = AniListSync(prefs, api, CoroutineScope(Dispatchers.Unconfined), { "2026-11-23" })

    @Before fun login() { prefs.edit().clear().putString("anilist_token", "t").commit() }

    @Test fun aReadWhileHisSaveIsInFlightWaitsAndReturnsTheNewValue() = runBlocking {
        val s = sync
        s.status("kitsu:5", "COMPLETED")
        val read = async(Dispatchers.Default) { s.read("t", "kitsu:5") }
        delay(150)
        assertFalse(read.isCompleted)
        gate.complete(Unit)
        assertEquals("COMPLETED", read.await().entry.status)
    }

    @Test fun aChangeAcceptedWhileAnotherIsInFlightIsNotMissedByTheRead() = runBlocking {
        val s = sync
        s.status("kitsu:5", "COMPLETED")
        s.status("kitsu:5", "PAUSED")
        val read = async(Dispatchers.Default) { s.read("t", "kitsu:5") }
        delay(150)
        gate.complete(Unit)
        assertEquals("PAUSED", read.await().entry.status)
    }

    @Test fun aReadWithNothingPendingReturnsTheServersEntry() = runBlocking {
        assertEquals("CURRENT", sync.read("t", "kitsu:5").entry.status)
    }
}
