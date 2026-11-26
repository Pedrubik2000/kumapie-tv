package io.github.pedrubik2000.kumapie.lang

import androidx.media3.common.PlaybackException
import io.github.pedrubik2000.kumapie.player.DecodeRetry
import io.github.pedrubik2000.kumapie.player.DecodeRetry.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DecodeRetry] (core plan step 11): "decoding failed" on the Chromecast (Dandadan 12, line 78) stopped the episode
 * behind a popup. Now: prepare again at the same place up to 3 times, 1 s further on when it fails at the same spot
 * twice, the popup only after that; once playback has gone on, a new failure starts over.
 */
class DecodeRetryTest {
    private val failed = PlaybackException.ERROR_CODE_DECODING_FAILED

    @Test fun retryTwiceThenSkipThenGiveUp() {
        val r = DecodeRetry()
        assertEquals(Step.Retry(60_000), r.step(failed, 60_000))
        assertEquals(Step.Retry(60_010), r.step(failed, 60_010))
        assertEquals(Step.Skip(61_020), r.step(failed, 60_020))
        assertEquals(Step.GiveUp, r.step(failed, 61_020))
    }

    @Test fun differentSpotsRetryWithoutSkipping() {
        val r = DecodeRetry()
        assertEquals(Step.Retry(10_000), r.step(failed, 10_000))
        assertEquals(Step.Retry(11_000), r.step(failed, 11_000))
        assertEquals(Step.Retry(11_900), r.step(failed, 11_900))
        assertEquals(Step.GiveUp, r.step(failed, 11_950))
    }

    @Test fun startsOverOncePlaybackWentOn() {
        val r = DecodeRetry()
        repeat(3) { r.step(failed, 5_000) }
        assertEquals(Step.GiveUp, r.step(failed, 6_000))
        assertEquals(Step.Retry(30_000), r.step(failed, 30_000))
        assertEquals(Step.Retry(30_000), r.step(failed, 30_000))
    }

    @Test fun decoderErrorsOnly() {
        val r = DecodeRetry()
        for (c in listOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED)) assertEquals(Step.Retry(1_000), DecodeRetry().step(c, 1_000))
        for (c in listOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)) assertEquals(Step.GiveUp, r.step(c, 1_000))
    }

    private val bad = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

    @Test fun malformedRetriesHereThenPastTheBufferThenGivesUp() {
        val r = DecodeRetry()
        assertEquals(Step.Retry(1_139_044), r.step(bad, 1_139_044, 1_179_000))
        assertEquals(Step.Retry(1_184_000), r.step(bad, 1_139_044, 1_179_000))
        assertEquals(Step.Retry(1_189_000), r.step(bad, 1_139_044, 1_184_000))
        assertEquals(Step.GiveUp, r.step(bad, 1_139_044, 1_189_000))
    }

    @Test fun malformedNeverLoopsOnAndOnAtTheSameSpot() {
        val r = DecodeRetry()
        repeat(3) { r.step(bad, 1_000, 1_000) }
        repeat(5) { assertEquals(Step.GiveUp, r.step(bad, 1_000, 1_000)) }
    }

    @Test fun malformedCountStartsOverOncePlaybackPassedTheSpot() {
        val r = DecodeRetry()
        repeat(3) { r.step(bad, 1_000, 30_000) }
        assertEquals(Step.Retry(40_000), r.step(bad, 40_000, 40_000))
    }
}
