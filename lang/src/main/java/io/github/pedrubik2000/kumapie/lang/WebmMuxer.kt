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
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Lets Media3 Transformer write WebM (VP9/VP8 + Opus/Vorbis) through Android's MediaMuxer: the card template shows a
 * clip as video only when it is a .webm (like the PC's clips, VP9 480p + Opus); Transformer itself writes only MP4.
 * Mined audio (Ogg Opus) goes through [OggMuxer] instead.
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
        if (!started) {
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

@OptIn(UnstableApi::class)
class OggMuxer private constructor(path: String, private val durationUs: Long?) : Muxer {
    private val file = File(path)
    private val out = file.outputStream().buffered()
    private var ogg: OggOpusWriter? = null

    override fun addTrack(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_OPUS || ogg != null) throw MuxerException("Ogg can't take this track", IllegalArgumentException("${format.sampleMimeType}"))
        ogg = try {
            OggOpusWriter(out, OggOpusWriter.opusHead(format.initializationData, format.channelCount, format.sampleRate))
        } catch (e: IllegalArgumentException) {
            throw MuxerException("Ogg: ${e.message}", e)
        }
        return 0
    }

    override fun writeSampleData(trackId: Int, data: ByteBuffer, info: BufferInfo) {
        if (info.size == 0) return
        val p = ByteArray(info.size).also { data.duplicate().get(it) }
        try {
            requireNotNull(ogg) { "no track" }.packet(p)
        } catch (e: IOException) {
            throw MuxerException("Ogg write failed", e)
        }
    }

    override fun addMetadataEntry(entry: Metadata.Entry) {}

    override fun close() {
        var ok = false
        try {
            requireNotNull(ogg) { "no audio track" }.finish(durationUs?.let { it * 48_000 / 1_000_000 })
            ok = true
        } catch (e: IOException) {
            throw MuxerException("Ogg close failed", e)
        } catch (e: IllegalStateException) {
            throw MuxerException("Ogg: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw MuxerException("Ogg: ${e.message}", e)
        } finally {
            runCatching { out.close() }
            if (!ok) file.delete()
        }
    }

    class Factory(private val durationUs: Long? = null) : Muxer.Factory {
        override fun create(path: String): Muxer = OggMuxer(path, durationUs)

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
            if (trackType == C.TRACK_TYPE_AUDIO) ImmutableList.of(MimeTypes.AUDIO_OPUS) else ImmutableList.of()
    }
}
