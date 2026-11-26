package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.torrent.TorrentEngine
import io.github.pedrubik2000.kumapie.torrent.TorrentException
import io.github.pedrubik2000.kumapie.torrent.TorrentRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TorrentOffTest {
    private val context = RuntimeEnvironment.getApplication()
    private val ref = TorrentRef("08ada5a7a6183aae1e09d831df6748d566095a10", -1, "Sintel.mp4", emptyList())

    private fun open(token: Any): TorrentException {
        try {
            TorrentEngine.open(context, ref, token)
        } catch (e: TorrentException) {
            return e
        }
        fail("opened with torrents off")
        throw IllegalStateException()
    }

    @Test fun withTheOptionOffTheEngineNeverStartsAndSaysSo() {
        assertFalse(Settings(context).torrents)
        val e = open(Any())
        assertEquals("Torrent streaming is off: turn it on in Settings.", e.message)
        assertTrue(TorrentEngine.failed)
        assertEquals(e.message, TorrentEngine.line.value)
        assertFalse(java.io.File(context.cacheDir, "torrent").exists())
        TorrentEngine.release()
    }

    @Test fun theSameOwnerIsTurnedAwayAtOnceAndAFailureClearsOnRecovery() {
        val token = Any()
        open(token)
        assertNotNull(open(token).message)
        TorrentEngine.recovered()
        assertFalse(TorrentEngine.failed)
        assertEquals(null, TorrentEngine.line.value)
        TorrentEngine.release()
        assertFalse(TorrentEngine.failed)
    }

    @Test fun aSavedTorrentLinkAfterTheStreamIsGoneFailsCleanlyForMining() {
        try {
            TorrentEngine.local(ref.uri())
            fail("gave a path")
        } catch (e: TorrentException) {
            assertTrue(e.message.orEmpty().contains("mine words"))
        }
        assertEquals("/data/x.mkv", TorrentEngine.local("/data/x.mkv"))
    }
}
