package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.AniList.Entry
import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.AniListAccount
import io.github.pedrubik2000.kumapie.data.AniListAccount.Last
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
    private var blockOnSocket: java.net.ServerSocket? = null
    private var sent = 0

    private val api = AniListSync.Api({ 7 }, { _, _ ->
        if (hang) delay(60_000)
        blockOnSocket?.let { ss ->
            AniList.cancellable { open ->
                val c = java.net.URL("http://127.0.0.1:${ss.localPort}/").openConnection() as java.net.HttpURLConnection
                open(c)
                c.readTimeout = 60_000
                c.inputStream.read()
                Entry()
            }
        }
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
        assertEquals(0, runBlocking { AniListAccount.sendLast(s) }.waiting)
        assertEquals(1, sent)
        assertEquals("This only signs kumapie out. Your AniList stays as it is.", AniListAccount.confirmText(Last(0, false)))
    }

    @Test fun changesThatCannotBeSentAreSaidBeforeLoggingOut() {
        val s = sync()
        failure = java.io.IOException("offline")
        s.status("kitsu:1", "CURRENT")
        assertEquals(1, runBlocking { AniListAccount.sendLast(s) }.waiting)
        assertEquals(0, sent)
        assertEquals("1 change hasn't reached AniList yet: it will be lost", AniListAccount.confirmText(Last(1, false)))
        assertEquals("3 changes haven't reached AniList yet: they'll be lost", AniListAccount.confirmText(Last(3, false)))
    }

    @Test fun aSlowAniListDoesNotHoldTheLogoutUp() {
        val s = sync()
        failure = java.io.IOException("offline")
        s.status("kitsu:1", "CURRENT")
        failure = null
        hang = true
        assertEquals(1, runBlocking { AniListAccount.sendLast(s, timeoutMs = 100) }.waiting)
    }

    @Test fun anEmptyQueueSaysNothingIsLost() = assertEquals(0, runBlocking { AniListAccount.sendLast(sync()) }.waiting)

    @Test fun aBlockedConnectionIsCutAtTheLimit() {
        val s = sync()
        failure = java.io.IOException("offline")
        s.status("kitsu:1", "CURRENT")
        failure = null
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { ss ->
            blockOnSocket = ss
            val t0 = System.nanoTime()
            val last = runBlocking { AniListAccount.sendLast(s, timeoutMs = 500) }
            val seconds = (System.nanoTime() - t0) / 1e9
            assertEquals(1, last.waiting)
            assertEquals(true, seconds < 5)
        }
    }

    @Test fun anExpiredLoginIsSaidAndNotClearedByYes() {
        val s = sync()
        failure = java.io.IOException("offline")
        s.status("kitsu:1", "CURRENT")
        failure = AniList.LoggedOut()
        val last = runBlocking { AniListAccount.sendLast(s) }
        assertEquals(true, last.expired)
        assertEquals(1, last.waiting)
        assertEquals("Your AniList login had expired: 1 change couldn't be sent", AniListAccount.confirmText(last))
        assertEquals("", prefs.getString("anilist_token", ""))
        assertEquals(true, prefs.getBoolean("anilist_logged_out", false))
    }
}
