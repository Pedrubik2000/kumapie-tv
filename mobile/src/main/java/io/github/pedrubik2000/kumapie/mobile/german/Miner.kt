package io.github.pedrubik2000.kumapie.mobile.german

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import io.github.pedrubik2000.kumapie.data.Cue
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.LineDef
import io.github.pedrubik2000.kumapie.data.Scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Mines a card from kumapie into kuma3 Anki (deck [DECK]), on the device: a new note of the German note type with
 * the line, a video clip of the whole scene (cut here with Media3 Transformer, WebM like the PC's), the English and, for a word
 * card, the word, its audio and the meaning picked, in the same layout as the Core 1000 cards. Mined cards count
 * as known only once reviewed (their words are new until then).
 */
class Miner(
    private val context: Context,
    private val known: KnownWords,
    private val dictionary: Dictionary,
    private val voice: () -> GermanVoice,
) {
    private val anki = AnkiCards(context)
    private val dir = File(context.cacheDir, "mining")

    /** The line mined; [word] set for a word card. */
    data class Request(
        val episode: EpisodeDetail,
        val scene: Scene,
        val line: Int,
        val word: Word? = null,
    )

    /** A word card's word: as written, its morph key ("steht auf"), dictionary form, and the meaning picked. */
    data class Word(
        val surface: String,
        val key: String?,
        val lemma: String?,
        val gloss: String,
        val example: Pair<String, String>? = null,
        val definition: LineDef? = null,
    )

    /** Adds the card; answers a line for the user ("Added to Deutsch::Mined"). */
    suspend fun mine(r: Request, progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val pkg = known.ankiApp() ?: error("No kuma3 Anki on this device.")
        if (!known.hasPermission(pkg)) error("kumapie may not use Anki yet: allow it in Settings.")
        val (mid, fieldNames) = anki.noteType(pkg, known.noteTypes)
            ?: error("The German note type isn't in Anki.")
        val (sentence, cues) = sentence(r.scene, r.line)
        val slug = slug(r.episode.show)
        dir.mkdirs()

        progress("Cutting the scene…")
        val clipFile = File(dir, "clip.webm").apply { delete() }
        val start = (r.scene.start * 1000).toLong()
        val end = (r.scene.end * 1000).toLong()
        clip(r.episode.video, start, end, clipFile)
        progress("Adding to Anki…")
        val clip = anki.addMedia(pkg, clipFile, "${slug}_$start-$end")

        val fields = HashMap<String, String>()
        fields["Sentence"] = esc(sentence)
        fields["Video"] = "[audio:$clip]"
        fields["Notes"] = esc(english(r.scene, cues))
        fields["Context"] = esc("Mined in kumapie: ${r.episode.show} · ${r.episode.title}, scene ${r.scene.index + 1}.")
        val tags = mutableListOf(slug, "kumapie")
        r.word?.let { w ->
            val audio = wordAudio(w.surface)?.let { anki.addMedia(pkg, it, "kumapie-${slug(w.surface)}") }
            val written = w.key?.takeIf { ' ' in it } ?: w.surface
            fields["Word"] = esc(written) + (w.lemma?.takeIf { !it.equals(written, true) }?.let { "[→ ${esc(it)}]" } ?: "")
            if (audio != null) fields["Word Audio"] = "[audio:$audio]"
            fields[KnownWords.DEF_BI] = definition(written, w)
            w.definition?.germanText?.takeIf { it.isNotBlank() }?.let { fields[KnownWords.DEF_MONO] = esc(it) }
            tags += KnownWords.MINED_WORD
        } ?: tags.add("_de::sentence")

        val missing = (listOf("Sentence", "Video") + if (r.word != null) listOf(KnownWords.DEF_BI, KnownWords.DEF_MONO) else emptyList())
            .filter { it !in fieldNames }
        if (missing.isNotEmpty()) error("The note type has no field ${missing.joinToString()}.")
        val deck = anki.deck(pkg, DECK)
        anki.addNote(pkg, mid, fieldNames.map { fields[it] ?: "" }, tags, deck)
        clipFile.delete()
        "Added to $DECK" + (r.word?.let { ": ${it.surface}" } ?: "")
    }

    /**
     * The scene from [startMs] to [endMs] as WebM like the PC's clips (VP9 480 p + Opus, ~450 kbps: the card template
     * shows only .webm clips as video), from the downloaded file or the PC's stream.
     */
    @OptIn(UnstableApi::class)
    internal suspend fun clip(source: String, startMs: Long, endMs: Long, out: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val uri = if (source.startsWith("/")) Uri.fromFile(File(source)) else Uri.parse(source)
            val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
            ).build()
            val edited = EditedMediaItem.Builder(item)
                .setEffects(Effects(listOf(), listOf(Presentation.createForHeight(480))))
                .build()
            val transformer = Transformer.Builder(context)
                .setMuxerFactory(WebmMuxer.Factory())
                .setVideoMimeType(if (EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_VP9).isNotEmpty()) MimeTypes.VIDEO_VP9 else MimeTypes.VIDEO_VP8)
                .setAudioMimeType(MimeTypes.AUDIO_OPUS)
                .setEncoderFactory(DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(400_000).build()).build())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                        Log.w("kumapie", "clip: $e")
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Couldn't cut the scene: ${e.errorCodeName}", e))
                    }
                })
                .build()
            transformer.start(edited, out.path)
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }

    /** A person's recording (saved or fetched), else the device's voice saying it; null if neither works. */
    internal suspend fun wordAudio(surface: String): File? {
        val recording = dictionary.recording(surface)
        if (recording != null && !recording.startsWith("http")) return File(recording)
        if (recording != null) {
            val out = File(dir, "word.mp3").apply { delete() }
            val ok = runCatching {
                val conn = URL(recording).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", AssetWorker.USER_AGENT)
                try {
                    if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
            }.isSuccess
            if (ok) return out
        }
        val wav = File(dir, "word.wav").apply { delete() }
        return if (voice().toFile(surface, wav)) wav else null
    }

    /** The Core 1000 layout of the bilingual definition: "<b>word</b> (lemma)", the meaning, an example. */
    private fun definition(written: String, w: Word): String {
        val lemma = w.lemma?.takeIf { !it.equals(written, true) }?.let { " (${esc(it)})" } ?: ""
        val example = w.example?.let { "<br>${esc(it.first)} = ${esc(it.second)}" } ?: ""
        return "<span style=\"font-size:1.4em\"><b>${esc(written)}</b>$lemma</span><br><b>${esc(w.gloss)}</b>$example"
    }

    /** The English of those cues: the scene's English lines that overlap them, else all of them. */
    internal fun english(scene: Scene, cues: List<Cue>): String {
        val start = cues.first().start
        val end = cues.last().end
        val overlapping = scene.english.filter { it.end > start + 0.1 && it.start < end - 0.1 }
        return (overlapping.ifEmpty { scene.english }).joinToString(" ") { it.text }
    }

    private fun esc(s: String) = TextUtils.htmlEncode(s)

    /** "You and I Are Polar Opposites" -> "you_and_i_are_polar_opposites", the show tag of the PC's cards. */
    internal fun slug(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')

    companion object {
        const val DECK = "Deutsch::Mined"

        /**
         * The whole sentence cue [line] is part of: subtitle cues split sentences ("Aber warum…" / "fühle ich mich …" /
         * "ausgelaugt?"), so the scene's cues are joined and cut at sentence ends (. ! ? followed by a capital); "…"
         * before a small letter goes on. Answers the sentence and its cues.
         */
        fun sentence(scene: Scene, line: Int): Pair<String, List<Cue>> {
            val cues = scene.cues
            fun ends(i: Int): Boolean {
                val text = cues[i].text.trimEnd()
                val next = cues.getOrNull(i + 1)?.text?.trimStart() ?: return true
                return text.isEmpty() || (text.last() in ".!?…\"»“" && next.firstOrNull()?.isUpperCase() != false)
            }
            var first = line
            while (first > 0 && !ends(first - 1)) first--
            var last = line
            while (last < cues.lastIndex && !ends(last)) last++
            val part = cues.subList(first, last + 1)
            return part.joinToString(" ") { it.text.trim() } to part
        }
    }
}
