package io.github.pedrubik2000.kumapie.lang

import android.media.MediaCodec
import android.media.MediaMuxer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

/**
 * Lets Media3 Transformer write WebM (VP9/VP8 + Opus/Vorbis) through Android's MediaMuxer: the card template shows a
 * clip as video only when it is a .webm (like the PC's clips, VP9 480p + Opus); Transformer itself writes only MP4.
 */
@OptIn(UnstableApi::class)
class WebmMuxer private constructor(path: String) : Muxer {
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
    private var started = false

    override fun addTrack(format: Format): Int = try {
        muxer.addTrack(MediaFormatUtil.createMediaFormatFromFormat(format))
    } catch (e: RuntimeException) {
        throw MuxerException("WebM can't take ${format.sampleMimeType}", e)
    }

    override fun writeSampleData(trackId: Int, data: ByteBuffer, info: BufferInfo) {
        if (!started) { // Transformer adds every track before the first sample
            muxer.start()
            started = true
        }
        val flags = if (info.flags and C.BUFFER_FLAG_KEY_FRAME != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        val frame = MediaCodec.BufferInfo().apply { set(data.position(), info.size, info.presentationTimeUs, flags) }
        try {
            muxer.writeSampleData(trackId, data, frame)
        } catch (e: RuntimeException) {
            throw MuxerException("WebM write failed", e)
        }
    }

    override fun addMetadataEntry(entry: Metadata.Entry) {}

    override fun close() {
        try {
            if (started) muxer.stop()
            muxer.release()
        } catch (e: RuntimeException) {
            throw MuxerException("WebM close failed", e)
        }
    }

    class Factory : Muxer.Factory {
        override fun create(path: String): Muxer = WebmMuxer(path)

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
            C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_VP8)
            C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS)
            else -> ImmutableList.of()
        }
    }
}
