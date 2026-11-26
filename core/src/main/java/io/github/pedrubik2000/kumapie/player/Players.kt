package io.github.pedrubik2000.kumapie.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * An ExoPlayer for videos in [lang] (its audio track picked, also by the track's name: [SpokenAudio]): as ExoPlayer's
 * own, but a FLAC audio track gets a decoder input buffer of 1 MB. A Blu-ray file streamed from Discover had FLAC frames
 * of 33-34 KB; Samsung's FLAC decoder (tablet) got ExoPlayer's 32 KB, every bigger frame was dropped ("Buffer too
 * small") and the video froze and paused with the audio (2026-11-15).
 * Its buffer stops at [BUFFER_BYTES]: ExoPlayer's own aims at 50 s up to ~130 MB, in the Java heap; a 4K "AI upscale"
 * Dandadan 10 filled 86 MB of it in seconds and, with Jiten's ~60 MB lexicon, the Chromecast's 256 MB heap ran out
 * (crash, 2026-11-19; heap dump in work\kumapie\oom).
 */
fun videoPlayer(context: Context, lang: String?): ExoPlayer = ExoPlayer.Builder(context, object : DefaultRenderersFactory(context) {
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
}.setEnableDecoderFallback(true)).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
    io.github.pedrubik2000.kumapie.torrent.torrentAware(context))).setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
    .setTargetBufferBytes(BUFFER_BYTES).setPrioritizeTimeOverSizeThresholds(false).build()
).build().apply {
    if (lang != null) {
        trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredAudioLanguage(lang).build()
        addListener(SpokenAudio(this, lang, context))
    }
}

/** The most video+audio [videoPlayer] holds ahead: ~10 s of a 40 Mbps 4K file, ~40 s of a usual 10 Mbps one. */
const val BUFFER_BYTES = 48 shl 20

/**
 * The [lang] audio track when its language tag doesn't say so: a dual-audio file named its tracks "Eng" (default) and
 * "Jap" with no language at all, and ExoPlayer played the English one (Dandadan 2 [RUBaDUB], bedroom TV, 2026-11-15).
 */
class SpokenAudio(private val player: ExoPlayer, lang: String, private val context: Context? = null) : Player.Listener {
    private val names = NAMES[lang] ?: listOf(lang)

    private fun spoken(f: Format) = f.language?.lowercase()?.let { l -> names.any { l == it || l.startsWith("$it-") } } == true ||
        f.label?.lowercase()?.let { label -> names.any { Regex("(^|[^a-z])$it").containsMatchIn(label) } } == true

    override fun onTracksChanged(tracks: Tracks) {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
        if (audio.size < 2 || audio.any { it.isSelected && spoken(it.getTrackFormat(0)) }) return
        // Only a track this device can decode: forcing the Japanese DTS-HD MA track of a Blu-ray rip stopped the video on
        // the Chromecast (no DTS decoder, 2026-11-18). None playable: the device's pick plays on, and the person is told.
        val spokenOnes = audio.filter { spoken(it.getTrackFormat(0)) }
        val want = spokenOnes.firstOrNull { it.isTrackSupported(0) } ?: run {
            spokenOnes.firstOrNull()?.getTrackFormat(0)?.let { f ->
                android.util.Log.w("kumapie", "audio: '${f.label}' (${f.sampleMimeType}) can't play here")
                context?.let { android.widget.Toast.makeText(it, io.github.pedrubik2000.kumapie.i18n.tr(
                    "Its spoken audio (%1\$s) can't play on this device: choose another source", f.label ?: f.sampleMimeType), android.widget.Toast.LENGTH_LONG).show() }
            }
            return
        }
        android.util.Log.i("kumapie", "audio: ${want.getTrackFormat(0).let { "'${it.label}' (${it.language})" }} by its name")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(want.mediaTrackGroup, 0)).build()
    }

    private companion object {
        val NAMES = mapOf(
            "ja" to listOf("ja", "jp", "jpn", "jap", "japanese", "日本"),
            "de" to listOf("de", "ger", "deu", "german", "deutsch"),
            "en" to listOf("en", "eng", "english"),
        )
    }
}
