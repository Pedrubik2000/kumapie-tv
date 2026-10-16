package androidx.media3.common

/** Desktop stand-in: the part of media3's Player that :core's SceneController drives (VlcPlayer implements it). */
interface Player {
    val currentPosition: Long
    val isPlaying: Boolean
    val playWhenReady: Boolean
    val playbackState: Int
    val playbackParameters: PlaybackParameters
    fun seekTo(positionMs: Long)
    fun play()
    fun pause()
    fun setPlaybackSpeed(speed: Float)

    companion object {
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
    }
}

class PlaybackParameters(val speed: Float)
