package io.github.pedrubik2000.kumapie.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * An ExoPlayer for videos: as ExoPlayer's own, but a FLAC audio track gets a decoder input buffer of 1 MB. A Blu-ray
 * file streamed from Discover had FLAC frames of 33-34 KB; Samsung's FLAC decoder (tablet) got ExoPlayer's 32 KB,
 * every bigger frame was dropped ("Buffer too small") and the video froze and paused with the audio (2026-11-15).
 */
fun videoPlayer(context: Context): ExoPlayer.Builder = ExoPlayer.Builder(context, object : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        audioSink: AudioSink, eventHandler: Handler, eventListener: AudioRendererEventListener, out: ArrayList<Renderer>,
    ) {
        out += object : MediaCodecAudioRenderer(context, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink) {
            override fun getCodecMaxInputSize(codecInfo: MediaCodecInfo, format: Format, streamFormats: Array<Format>): Int =
                super.getCodecMaxInputSize(codecInfo, format, streamFormats)
                    .let { if (format.sampleMimeType == MimeTypes.AUDIO_FLAC) maxOf(it, 1 shl 20) else it }
        }
    }
})
