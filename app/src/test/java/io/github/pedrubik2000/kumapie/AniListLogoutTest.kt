package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.AniList.Entry
import io.github.pedrubik2000.kumapie.data.AniListAccount
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AniListLogoutTest {
    private val prefs = Settings(RuntimeEnvironment.getApplication()).prefs
    private var failure: Exception? = null
    private var hang = false
    private var sent = 0

    private val api = AniListSync.Api({ 7 }, { _, _ ->
        if (hang) delay(60_000)
        failure?.let { throw it }
        Entry(episodes = 12)
    }, { _, _, _, after -> sent++; after }, { 0L }, { "POINT_10" })

    private fun sync() = AniListSync(prefs, api, CoroutineScope(Dispatchers.Unconfined), { "2026-11-23" }, { 1_800_000_000_000L }, { _, _ -> })

    @Before fun login() {
        prefs.edit().clear().putString("anilist_token", "t").commit()
        AniListSync.resetState()
    }

    @Test fun waitingChangesAreSentBeforeLoggingOut() {
        val s = sync()
        failure = java.io.IOException("offline")
        s.status("kitsu:1", "CURRENT")
        assertEquals(1, s.waiting())
        failure = null
        assertEquals(0, runBlocking { AniListAccount.sendLast(s) })
        assertEquals(1, sent)
        assertEquals("This only signs kumapie out. Your AniList stays as it is.", AniListAccount.confirmText(0))
    }

    @Test fun changesThatCannotBeSentAreSaidBeforeLoggingOut() {
        val s = sync()
        failure = java.io.IOException("offline")
        s.status("kitsu:1", "CURRENT")
        assertEquals(1, runBlocking { AniListAccount.sendLast(s) })
        assertEquals(0, sent)
        assertEquals("1 change hasn't reached AniList yet: it will be lost", AniListAccount.confirmText(1))
        assertEquals("3 changes haven't reached AniList yet: they'll be lost", AniListAccount.confirmText(3))
    }

    @Test fun aSlowAniListDoesNotHoldTheLogoutUp() {
        val s = sync()
        failure = java.io.IOException("offline")
        s.status("kitsu:1", "CURRENT")
        failure = null
        hang = true
        assertEquals(1, runBlocking { AniListAccount.sendLast(s, timeoutMs = 100) })
    }

    @Test fun anEmptyQueueSaysNothingIsLost() = assertEquals(0, runBlocking { AniListAccount.sendLast(sync()) })
}
