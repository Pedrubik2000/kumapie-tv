package io.github.pedrubik2000.kumapie.desktop

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import javax.swing.SwingUtilities
import org.jetbrains.skia.Rect as SkRect

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
 * The episode's video for :core's SceneController. libVLC decodes into a buffer and each frame becomes a Skia image that
 * [VideoFrame] draws, so Compose can put the subtitles and the word card over the picture, as the tablet does (a Swing
 * video panel always paints over Compose). libVLC reports its time in ~250 ms steps; SceneController runs on from the clock.
 */
class VlcPlayer : Player {
    private val mp = Vlc.factory.mediaPlayers().newEmbeddedMediaPlayer()
    /** The newest frame (set on the UI thread); null until the first one. */
    var frame by mutableStateOf<Image?>(null)
        private set
    private val old = ArrayDeque<Image>() // frames drawn lately, closed a few frames later (the renderer may still hold one)
    private var info: ImageInfo? = null
    private var bytes = ByteArray(0)
    private var rowBytes = 0
    private var speed = 1f
    @Volatile override var playWhenReady = false
        private set

    init {
        mp.videoSurface().set(Vlc.factory.videoSurfaces().newVideoSurface(object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                // At most 1920 wide: a 4K source would copy 33 MB a frame for no visible gain on this screen.
                val scale = minOf(1f, 1920f / sourceWidth)
                val w = (sourceWidth * scale).toInt() and 1.inv()
                val h = (sourceHeight * scale).toInt() and 1.inv()
                rowBytes = w * 4
                bytes = ByteArray(rowBytes * h)
                info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE) // VLC's RV32 is B, G, R, X
                return RV32BufferFormat(w, h)
            }
            override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {}
            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
        }, object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) {}
            override fun unlock(mediaPlayer: MediaPlayer) {}
            override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
                val inf = info ?: return
                nativeBuffers[0].duplicate().apply { rewind() }.get(bytes, 0, minOf(bytes.size, nativeBuffers[0].capacity()))
                val image = Image.makeRaster(inf, bytes, rowBytes) // copies: the buffer is reused for the next frame
                SwingUtilities.invokeLater {
                    frame?.let { old.addLast(it) }
                    frame = image
                    while (old.size > 3) old.removeFirst().close()
                }
            }
        }, true))
    }

    override val currentPosition: Long get() = mp.status().time().coerceAtLeast(0)
    override val isPlaying: Boolean get() = mp.status().isPlaying
    override val playbackState: Int get() = if (playWhenReady && !isPlaying) Player.STATE_BUFFERING else Player.STATE_READY
    override val playbackParameters get() = PlaybackParameters(speed)
    val seekable: Boolean get() = mp.status().isSeekable

    /** Opens [url] paused: VLC can seek only once the media has started, so it starts and pauses at once. */
    fun open(url: String) { mp.media().startPaused(url) }

    override fun seekTo(positionMs: Long) { mp.controls().setTime(positionMs) }
    override fun play() { playWhenReady = true; mp.controls().play() }
    override fun pause() { playWhenReady = false; mp.controls().setPause(true) }
    override fun setPlaybackSpeed(speed: Float) { this.speed = speed; mp.controls().setRate(speed) }

    fun release() {
        mp.controls().stop()
        mp.release()
        SwingUtilities.invokeLater { old.forEach { it.close() }; old.clear() } // the last frame is left to the GC
    }
}

/** The video, fitted and centred, letterboxed in black. */
@Composable
fun VideoFrame(player: VlcPlayer, modifier: Modifier) {
    val image = player.frame
    Canvas(modifier.background(Color.Black)) {
        val img = image ?: return@Canvas
        val scale = minOf(size.width / img.width, size.height / img.height)
        val w = img.width * scale
        val h = img.height * scale
        drawIntoCanvas {
            it.skiaCanvas.drawImageRect(img, SkRect.makeWH(img.width.toFloat(), img.height.toFloat()),
                SkRect.makeXYWH((size.width - w) / 2, (size.height - h) / 2, w, h),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), null, true)
        }
    }
}
