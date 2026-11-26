package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.torrent.TorrentEngine
import io.github.pedrubik2000.kumapie.torrent.TorrentPhase
import io.github.pedrubik2000.kumapie.torrent.TorrentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TorrentFailTest {
    @Before fun fresh() = TorrentEngine.release()

    @Test fun aPlayerErrorBeforeTheVideoStartsFailsTheWait() {
        TorrentEngine.playerFailed("The video stopped: boom")
        assertTrue(TorrentEngine.failed)
        assertEquals(TorrentPhase.FAILED, TorrentEngine.status.value?.phase)
        assertEquals("The video stopped: boom", TorrentEngine.status.value?.message)
        assertEquals("The video stopped: boom", TorrentEngine.line.value)
    }

    @Test fun aPlayerErrorAfterAnEngineFailureKeepsTheEnginesReason() {
        TorrentEngine.fail("No peers")
        TorrentEngine.playerFailed("The video stopped")
        assertEquals("No peers", TorrentEngine.status.value?.message)
    }

    @Test fun aWatchTickNeverOverwritesAFailedStatus() {
        TorrentEngine.fail("No peers")
        TorrentEngine.tick(TorrentStatus(TorrentPhase.DOWNLOADING, peers = 3), "Downloading 1%")
        assertEquals(TorrentPhase.FAILED, TorrentEngine.status.value?.phase)
        assertEquals("No peers", TorrentEngine.line.value)
    }

    @Test fun aWatchTickWritesWhileNothingFailed() {
        TorrentEngine.tick(TorrentStatus(TorrentPhase.DOWNLOADING, peers = 3), "Downloading 1%")
        assertEquals(TorrentPhase.DOWNLOADING, TorrentEngine.status.value?.phase)
    }
}
