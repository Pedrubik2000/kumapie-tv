package io.github.pedrubik2000.kumapie.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.util.WeakHashMap
import kotlin.math.abs

class DecodeRetry {
    sealed interface Step {
        data class Retry(val atMs: Long) : Step
        data class Skip(val toMs: Long) : Step
        data object GiveUp : Step
    }

    private var tries = 0
    private var spot = -1L
    private var atSpot = 0
    private var seen: PlaybackException? = null
    private var recovered = false

    private var reopens = 0
    private var reopenPast = -1L

    fun step(code: Int, atMs: Long, bufferedMs: Long = atMs): Step {
        if (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) {
            if (atMs > reopenPast) reopens = 0
            if (++reopens > TRIES) return Step.GiveUp
            val to = if (reopens == 1) atMs else maxOf(atMs, bufferedMs) + PAST_BUFFER_MS
            reopenPast = maxOf(to, bufferedMs)
            return Step.Retry(to)
        }
        if (code !in CODES) return Step.GiveUp
        if (spot >= 0 && atMs > spot + ADVANCED_MS) tries = 0
        if (tries > 0 && abs(atMs - spot) < SAME_MS) atSpot++ else { spot = atMs; atSpot = 1 }
        tries++
        return when {
            tries > TRIES -> Step.GiveUp
            atSpot > 2 -> Step.Skip(atMs + SKIP_MS)
            else -> Step.Retry(atMs)
        }
    }

    companion object {
        val CODES = setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED)
        const val TRIES = 3
        const val PAST_BUFFER_MS = 5_000L
        const val SKIP_MS = 1_000L
        const val SAME_MS = 500L
        const val ADVANCED_MS = 2_000L

        private val players = WeakHashMap<Player, DecodeRetry>()

        fun reopening(e: PlaybackException) = codeOf(e) == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

        fun codeOf(e: PlaybackException): Int = codeOf(e.errorCode, e.cause)

        fun codeOf(code: Int, cause: Throwable?): Int =
            if (code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED && generateSequence(cause) { it.cause }.take(10).any { it is ArrayIndexOutOfBoundsException }) PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            else code

        fun recovered(player: Player, e: PlaybackException): Boolean {
            val r = synchronized(players) { players.getOrPut(player) { DecodeRetry() } }
            if (r.seen === e) return r.recovered
            r.seen = e
            val buffered = player.bufferedPosition
            val s = r.step(codeOf(e), player.currentPosition, buffered)
            when (s) {
                is Step.Retry -> player.seekTo(s.atMs)
                is Step.Skip -> player.seekTo(s.toMs)
                Step.GiveUp -> {}
            }
            r.recovered = s != Step.GiveUp
            if (r.recovered) player.prepare()
            android.util.Log.w("kumapie", "player: ${e.errorCodeName} at ${player.currentPosition} ms -> $s, buffered $buffered ms (${e.cause?.message})")
            return r.recovered
        }
    }
}
