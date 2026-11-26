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

    private val reopened = ArrayDeque<Long>()

    fun step(code: Int, atMs: Long, nowMs: Long = System.currentTimeMillis()): Step {
        if (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) {
            reopened.removeAll { nowMs - it >= REOPEN_WINDOW_MS }
            if (reopened.size >= TRIES) return Step.GiveUp
            reopened += nowMs
            return Step.Retry(atMs)
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
        const val REOPEN_WINDOW_MS = 120_000L
        const val SKIP_MS = 1_000L
        const val SAME_MS = 500L
        const val ADVANCED_MS = 2_000L

        private val players = WeakHashMap<Player, DecodeRetry>()

        fun reopening(e: PlaybackException) = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

        fun recovered(player: Player, e: PlaybackException): Boolean {
            val r = synchronized(players) { players.getOrPut(player) { DecodeRetry() } }
            if (r.seen === e) return r.recovered
            r.seen = e
            val s = r.step(e.errorCode, player.currentPosition)
            when (s) {
                is Step.Retry -> player.seekTo(s.atMs)
                is Step.Skip -> player.seekTo(s.toMs)
                Step.GiveUp -> {}
            }
            r.recovered = s != Step.GiveUp
            if (r.recovered) player.prepare()
            android.util.Log.w("kumapie", "player: ${e.errorCodeName} at ${player.currentPosition} ms -> $s (${e.cause?.message})")
            return r.recovered
        }
    }
}
