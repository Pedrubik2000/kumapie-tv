package io.github.pedrubik2000.kumapie.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/**
 * Keeps the device's sound output awake while the word player is paused (a scene's end, a line), for at most a minute:
 * a silent looping track. Asleep, it took ~1 s to wake and ExoPlayer held the picture until the sound ran: Right at a
 * scene's end showed the next scene 1.2 s later on the Chromecast (HDMI audio in standby), 0.9 s on the tablet
 * (2026-11-18). ponytail: one minute, then the output may sleep; longer if long pauses feel slow to resume.
 */
class AudioAwake : Player.Listener {
    private var track: AudioTrack? = null
    private val handler = Handler(Looper.getMainLooper())
    private val off = Runnable { stop() }

    override fun onIsPlayingChanged(isPlaying: Boolean) = if (isPlaying) stop() else start()

    private fun start() {
        if (track == null) track = runCatching { silence() }.getOrNull()?.also { runCatching { it.play() } }
        handler.removeCallbacks(off)
        handler.postDelayed(off, 60_000)
    }

    fun stop() {
        handler.removeCallbacks(off)
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    private fun silence(): AudioTrack {
        val frames = 4800 // 0.1 s, looped
        val bytes = frames * 4
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(bytes).build()
            .apply { write(ByteArray(bytes), 0, bytes); setLoopPoints(0, frames, -1) }
    }
}
