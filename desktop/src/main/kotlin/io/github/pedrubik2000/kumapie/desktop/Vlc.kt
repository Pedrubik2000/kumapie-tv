package io.github.pedrubik2000.kumapie.desktop

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/** libVLC, found in KUMAPIE_VLC (a VLC 3 folder, e.g. the portable zip) or where VLC is installed. */
object Vlc {
    private val folder = System.getenv("KUMAPIE_VLC")?.takeIf { it.isNotBlank() }

    val factory: MediaPlayerFactory by lazy {
        folder?.let { System.setProperty("jna.library.path", it) } // VLCJ's discovery looks there first
        val found = NativeDiscovery().discover()
        check(found) { "libVLC not found: install VLC 3 or set KUMAPIE_VLC to its folder" }
        MediaPlayerFactory("--no-video-title-show", "--quiet")
    }
}

/**
 * The episode's video for :core's SceneController: libVLC in a Swing component (the window shows it in a SwingPanel).
 * libVLC reports its time in steps of ~250 ms while playing; SceneController.livePosition() runs on from the clock.
 */
class VlcPlayer : Player {
    val component = EmbeddedMediaPlayerComponent(Vlc.factory, null, null, null, null).apply {
        videoSurfaceComponent().isFocusable = false // keys stay with the Compose window
        background = java.awt.Color.BLACK
    }
    private val mp get() = component.mediaPlayer()
    private var speed = 1f
    @Volatile override var playWhenReady = false
        private set

    override val currentPosition: Long get() = mp.status().time().coerceAtLeast(0)
    override val isPlaying: Boolean get() = mp.status().isPlaying
    override val playbackState: Int get() = if (playWhenReady && !isPlaying) Player.STATE_BUFFERING else Player.STATE_READY
    override val playbackParameters get() = PlaybackParameters(speed)

    /** Opens [url] paused: VLC can seek only once the media has started, so it starts and pauses at once. */
    fun open(url: String) { mp.media().startPaused(url) }

    override fun seekTo(positionMs: Long) { mp.controls().setTime(positionMs) }
    override fun play() { playWhenReady = true; mp.controls().play() }
    override fun pause() { playWhenReady = false; mp.controls().setPause(true) }
    override fun setPlaybackSpeed(speed: Float) { this.speed = speed; mp.controls().setRate(speed) }

    fun release() { mp.controls().stop(); component.release() }
}
