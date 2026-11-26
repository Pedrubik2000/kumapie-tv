package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.torrent.TorrentFault
import io.github.pedrubik2000.kumapie.torrent.TorrentPhase
import io.github.pedrubik2000.kumapie.torrent.TorrentStatus
import io.github.pedrubik2000.kumapie.torrent.bufferText
import io.github.pedrubik2000.kumapie.torrent.waitText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentWaitTextTest {
    private val mb = 1L shl 20

    @Test fun findingPeersSaysSoAndPromisesNothingExact() {
        val none = waitText(TorrentStatus())
        assertEquals("STARTING SOON", none.label)
        assertEquals("Finding peers…", none.headline)
        assertEquals("", none.detail)
        assertEquals("Usually ready in 10–15 s", none.hint)
        val some = waitText(TorrentStatus(peers = 4))
        assertEquals("4 peers found", some.headline)
        assertEquals("looking for more", some.detail)
        assertFalse(some.failed)
    }

    @Test fun oneAndOnlyOnePeerIsSingular() {
        assertEquals("1 peer found", waitText(TorrentStatus(peers = 1)).headline)
        assertEquals("1 peer", io.github.pedrubik2000.kumapie.torrent.peerCount(1))
        assertEquals("2 peers", io.github.pedrubik2000.kumapie.torrent.peerCount(2))
        assertEquals("0 peers", io.github.pedrubik2000.kumapie.torrent.peerCount(0))
    }

    @Test fun downloadingShowsPercentOfTheStartRateAndBytes() {
        val s = TorrentStatus(TorrentPhase.DOWNLOADING, peers = 23, rate = 3 * mb, have = 2 * mb, need = 6 * mb)
        val t = waitText(s)
        assertEquals("33%", t.headline)
        assertEquals("of the start · about 2 s", t.detail)
        assertEquals("3.0 MB/s · 23 peers · 2.0 MB of 6.0 MB", t.stats)
        assertEquals("", t.hint)
    }

    @Test fun noEstimateWhenTheRateIsTooLowOrTheStartIsDone() {
        assertNull(TorrentStatus(TorrentPhase.DOWNLOADING, rate = 1000, have = mb, need = 6 * mb).etaSeconds)
        assertNull(TorrentStatus(TorrentPhase.DOWNLOADING, rate = mb, have = 6 * mb, need = 6 * mb).etaSeconds)
        assertNull(TorrentStatus(TorrentPhase.DOWNLOADING, rate = 60_000, have = 0, need = 6 * mb).etaSeconds)
        assertEquals("of the start", waitText(TorrentStatus(TorrentPhase.DOWNLOADING, rate = 0, have = mb, need = 6 * mb)).detail)
        assertEquals(100, TorrentStatus(TorrentPhase.DOWNLOADING, have = 9 * mb, need = 6 * mb).percent)
        assertEquals(0, TorrentStatus(TorrentPhase.DOWNLOADING).percent)
    }

    @Test fun theStartNeedIsHeadAndTailButNeverMoreThanTheFile() {
        assertEquals(6 * mb, TorrentStatus.startNeed(1000 * mb))
        assertEquals(3 * mb, TorrentStatus.startNeed(3 * mb))
    }

    @Test fun everyFaultIsAPlainSentence() {
        fun text(f: TorrentFault, m: String = "x") = waitText(TorrentStatus.failed(f, m, 0, "r"))
        assertEquals("COULDN'T START", text(TorrentFault.OFF).label)
        assertEquals("No one is sharing this file", text(TorrentFault.NO_PEERS).headline)
        assertEquals("0 peers after 60 s", text(TorrentFault.NO_PEERS).detail)
        assertEquals("Torrent streaming is off", text(TorrentFault.OFF).headline)
        assertEquals("Not enough free space", text(TorrentFault.NO_SPACE).headline)
        assertEquals("This video needs 1.4 GB and 500 MB spare.", text(TorrentFault.NO_SPACE, "Not enough free space: this video needs 1.4 GB and 500 MB spare.").detail)
        assertEquals("The download stalled", text(TorrentFault.STALLED).headline)
        assertEquals("something else", text(TorrentFault.OTHER, "something else").headline)
        assertTrue(text(TorrentFault.OTHER).failed)
    }

    @Test fun bufferingAfterASeekIsPercentOfWhatPlaybackNeeds() {
        val (title, stats) = bufferText(TorrentStatus(TorrentPhase.DOWNLOADING, peers = 19, rate = 2936012, ahead = 2 * mb))
        assertEquals("Buffering 50%", title)
        assertEquals("2.8 MB/s · 19 peers", stats)
        assertEquals(100, TorrentStatus(ahead = 40 * mb).bufferPercent)
        assertEquals(0, TorrentStatus().bufferPercent)
    }
}
