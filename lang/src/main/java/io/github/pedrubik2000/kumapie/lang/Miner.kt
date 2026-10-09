package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.i18n.tr
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
 * Mines a card from kumapie into kuma3 Anki (the language's Mined deck), on the device: a new note of the language's note type with
 * the line, a video clip of the whole scene (cut here with Media3 Transformer, WebM like the PC's), the English and, for a word
 * card, the word, its audio and the meaning picked, in the same layout as the Core 1000 cards. Mined cards count
 * as known only once reviewed (their words are new until then).
 */
class Miner(
    private val context: Context,
    private val languages: Languages,
    private val dictionary: Dictionary,
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
        /** Japanese: the dictionary form's reading (hiragana), its pitch downstep, a monolingual definition. */
        val reading: String? = null,
        val pitch: Int? = null,
        val mono: String? = null,
    )

    /** Adds the card; answers a line for the user ("Added to Deutsch::Mined"). */
    suspend fun mine(r: Request, progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        // German and English cards share the layout (🐻 German / 🐻 English); Japanese has Kaishi's.
        val language = when (val l = languages.of(r.episode.lang)) { is Japanese -> return@withContext mineJapanese(r, l, progress); is Spaced -> l }
        val lang = language.lang
        val known = language.known
        val pkg = known.ankiApp() ?: error(tr("No kuma3 Anki on this device."))
        if (!known.hasPermission(pkg)) error(tr("kumapie may not use Anki yet: allow it in Settings."))
        // A fresh kuma3 (Giovanna, Jackson): the note type comes from kumapie's copy of Pedro's.
        val (mid, fieldNames) = anki.noteType(pkg, known.noteTypes) ?: anki.addBundledNoteType(pkg, lang)
            ?: error(tr("The %s note type isn't in Anki.", lang.displayName))
        val (sentence, cues) = sentence(r.scene, r.line)
        val slug = slug(r.episode.show)
        dir.mkdirs()

        val fields = HashMap<String, String>()
        // A note type with Video (🐻 German, the parents' 🐻 English): the line's audio, then a clip of the scene, so the
        // front plays word audio -> sentence audio -> video. Older 🐻 English (Core 1000 layout): line audio + screenshot.
        val video = "Video" in fieldNames
        val clipFile = File(dir, if (video) "clip.webm" else "line.m4a").apply { delete() }
        val lineFile = File(dir, "line.m4a")
        val startMs = ((cues.first().start - 0.25).coerceAtLeast(0.0) * 1000).toLong()
        val endMs = ((cues.last().end + 0.25) * 1000).toLong()
        val shot: File?
        if (video) {
            progress(tr("Cutting the line's audio…"))
            lineFile.delete()
            audioClip(r.episode.video, startMs, endMs, lineFile)
            progress(tr("Cutting the scene…"))
            val start = (r.scene.start * 1000).toLong()
            val end = (r.scene.end * 1000).toLong()
            clip(r.episode.video, start, end, clipFile)
            progress(tr("Adding to Anki…"))
            fields["Video"] = "[audio:${anki.addMedia(pkg, lineFile, "${slug}_$startMs-$endMs")}]" +
                "[audio:${anki.addMedia(pkg, clipFile, "${slug}_$start-$end")}]"
            lineFile.delete()
            shot = null
        } else {
            progress(tr("Cutting the line's audio…"))
            audioClip(r.episode.video, startMs, endMs, clipFile)
            shot = screenshot(r.episode.video, (startMs + endMs) / 2)
            progress(tr("Adding to Anki…"))
            fields["Sentence Audio"] = "[audio:${anki.addMedia(pkg, clipFile, "${slug}_$startMs-$endMs")}]"
            shot?.let { fields["Image"] = "<img src=\"${anki.addMedia(pkg, it, "${slug}_$startMs")}\">" }
        }
        fields["Sentence"] = esc(sentence)
        fields["Notes"] = esc(english(r.scene, cues))
        fields["Context"] = esc("Mined in kumapie: ${r.episode.show} · ${r.episode.title}, scene ${r.scene.index + 1}.")
        val tags = mutableListOf(slug, "kumapie")
        r.word?.let { w ->
            val audio = wordAudio(w.surface, language)?.let { anki.addMedia(pkg, it, "kumapie-${slug(w.surface)}") }
            val written = w.key?.takeIf { ' ' in it } ?: w.surface
            fields["Word"] = esc(written) + (w.lemma?.takeIf { !it.equals(written, true) }?.let { "[→ ${esc(it)}]" } ?: "")
            if (audio != null) fields["Word Audio"] = "[audio:$audio]"
            fields[KnownWords.DEF_BI] = definition(written, w)
            // The episode's own definition (PC episodes), else the one picked from a monolingual dictionary.
            (w.definition?.targetText?.takeIf { it.isNotBlank() } ?: w.mono?.takeIf { it.isNotBlank() })?.let { fields[KnownWords.DEF_MONO] = esc(it) }
            tags += lang.tag("word")
        } ?: tags.add(lang.tag("sentence"))

        val missing = (listOf("Sentence", if ("Video" in fieldNames) "Video" else "Sentence Audio") + if (r.word != null) listOf(KnownWords.DEF_BI, KnownWords.DEF_MONO) else emptyList())
            .filter { it !in fieldNames }
        if (missing.isNotEmpty()) error(tr("The note type has no field %s.", missing.joinToString()))
        val deck = anki.deck(pkg, lang.deck)
        anki.addNote(pkg, mid, fieldNames.map { fields[it] ?: "" }, tags, deck)
        clipFile.delete()
        shot?.delete()
        tr("Added to %s", lang.deck) + (r.word?.let { ": ${it.surface}" } ?: "")
    }

    /**
     * A 🐻 Japanese card (the Kaishi 1.5k layout): the tapped subtitle line with the word in bold, that line's audio cut
     * from the episode, a screenshot, the English, and for a word card `届く[とどく]:0-` (reading, pitch), the meaning
     * picked, a monolingual definition and the device voice saying the word. Into Japanese::Mined.
     */
    private suspend fun mineJapanese(r: Request, japanese: Japanese, progress: (String) -> Unit): String {
        val ja = japanese.lang
        val knownJa = japanese.known
        val pkg = knownJa.ankiApp() ?: error(tr("No kuma3 Anki on this device."))
        if (!knownJa.hasPermission(pkg)) error(tr("kumapie may not use Anki yet: allow it in Settings."))
        val (mid, fieldNames) = anki.noteType(pkg, knownJa.noteTypes) ?: error(tr("The Japanese note type isn't in Anki."))
        val cue = r.scene.cues[r.line]
        val slug = slug(r.episode.show)
        dir.mkdirs()
        val startMs = ((cue.start - 0.25).coerceAtLeast(0.0) * 1000).toLong()
        val endMs = ((cue.end + 0.25) * 1000).toLong()

        progress(tr("Cutting the line's audio…"))
        val lineAudio = File(dir, "line.m4a").apply { delete() }
        audioClip(r.episode.video, startMs, endMs, lineAudio)
        val shot = screenshot(r.episode.video, (startMs + endMs) / 2)
        progress(tr("Adding to Anki…"))
        val fields = HashMap<String, String>()
        fields["Sentence Audio"] = "[audio:${anki.addMedia(pkg, lineAudio, "${slug}_$startMs-$endMs")}]"
        shot?.let { fields["Image"] = "<img src=\"${anki.addMedia(pkg, it, "${slug}_$startMs")}\">" }
        fields["Notes"] = esc(english(r.scene, listOf(cue)))
        fields["Context"] = esc("Mined in kumapie: ${r.episode.show} · ${r.episode.title}, scene ${r.scene.index + 1}.")
        val text = cue.text.trim()
        val tags = mutableListOf(slug, "kumapie")
        val w = r.word
        if (w != null) {
            val expression = w.lemma ?: w.surface
            val kanji = expression.any { it.code in 0x3400..0x9FFF }
            val reading = w.reading?.takeIf { kanji && it.isNotBlank() }
            fields["Word"] = esc(expression) + (reading?.let { "[${esc(it)}]" } ?: "") + (w.pitch?.let { ":$it-" } ?: "")
            // The word in the line in bold; its reading too when it is written as in the dictionary (Kaishi: 私[わたし]).
            val at = text.indexOf(w.surface)
            fields["Sentence"] = if (at < 0) esc(text) else esc(text.substring(0, at)) + "<b>" + esc(w.surface) +
                (if (w.surface == expression && reading != null) "[${esc(reading)}]" else "") + "</b>" + esc(text.substring(at + w.surface.length))
            fields[KnownWords.DEF_BI] = esc(w.gloss)
            w.mono?.takeIf { it.isNotBlank() }?.let { fields[KnownWords.DEF_MONO] = esc(it) }
            // A person's recording (NHK, Shinmeikai, JapanesePod101, Forvo), else the device voice.
            val recorded = japanese.audio.recording(expression, w.reading.orEmpty())
            val wav = File(dir, "word.wav").apply { delete() }
            val audio = recorded ?: wav.takeIf { japanese.voice.toFile(w.reading ?: expression, it) }
            audio?.let { fields["Word Audio"] = "[audio:${anki.addMedia(pkg, it, "kumapie-ja-${expression}")}]" }
            tags += ja.tag("word")
        } else {
            fields["Sentence"] = esc(text)
            tags += ja.tag("sentence")
        }
        val missing = listOf("Sentence", "Sentence Audio", KnownWords.DEF_BI).filter { it !in fieldNames }
        if (missing.isNotEmpty()) error(tr("The Japanese note type has no field %s.", missing.joinToString()))
        anki.addNote(pkg, mid, fieldNames.map { fields[it] ?: "" }, tags, anki.deck(pkg, ja.deck))
        lineAudio.delete()
        shot?.delete()
        return tr("Added to %s", ja.deck) + (w?.let { ": ${it.lemma ?: it.surface}" } ?: "")
    }

    /** [startMs]..[endMs] of the episode's audio alone, as AAC in .m4a. */
    @OptIn(UnstableApi::class)
    suspend fun audioClip(source: String, startMs: Long, endMs: Long, out: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val uri = if (source.startsWith("/")) Uri.fromFile(File(source)) else Uri.parse(source)
            val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
            ).build()
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                        Log.w("kumapie", "audio clip: $e")
                        if (cont.isActive) cont.resumeWithException(IllegalStateException(tr("Couldn't cut the line's audio: %s", e.errorCodeName), e))
                    }
                })
                .build()
            transformer.start(EditedMediaItem.Builder(item).setRemoveVideo(true).build(), out.path)
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }

    /** The frame at [ms], 480 p, as WebP; null when the video can't give one. */
    private fun screenshot(source: String, ms: Long): File? = runCatching {
        val out = File(dir, "shot.webp").apply { delete() }
        android.media.MediaMetadataRetriever().use { r ->
            if (source.startsWith("/")) r.setDataSource(source) else r.setDataSource(context, Uri.parse(source))
            val sync = android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val frame = (if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(ms * 1000, sync, 854, 480)
                else r.getFrameAtTime(ms * 1000, sync)) ?: return@use null
            @Suppress("DEPRECATION")
            val webp = if (android.os.Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY else android.graphics.Bitmap.CompressFormat.WEBP
            out.outputStream().use { frame.compress(webp, 80, it) }
            out
        }
    }.getOrNull()

    /**
     * The scene from [startMs] to [endMs] as WebM like the PC's clips (VP9 480 p + Opus, ~450 kbps: the card template
     * shows only .webm clips as video), from the downloaded file or the PC's stream.
     */
    @OptIn(UnstableApi::class)
    suspend fun clip(source: String, startMs: Long, endMs: Long, out: File) = withContext(Dispatchers.Main) {
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
                        if (cont.isActive) cont.resumeWithException(IllegalStateException(tr("Couldn't cut the scene: %s", e.errorCodeName), e))
                    }
                })
                .build()
            transformer.start(edited, out.path)
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }

    /** A person's recording (saved or fetched), else the device's voice saying it; null if neither works. */
    suspend fun wordAudio(surface: String, language: Spaced = languages.german): File? {
        val recording = dictionary.recording(surface, language.lang.code)
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
        return if (language.voice.toFile(surface, wav)) wav else null
    }

    /** The Core 1000 layout of the bilingual definition: "<b>word</b> (lemma)", the meaning, an example. */
    private fun definition(written: String, w: Word): String {
        val lemma = w.lemma?.takeIf { !it.equals(written, true) }?.let { " (${esc(it)})" } ?: ""
        val example = w.example?.let { "<br>${esc(it.first)} = ${esc(it.second)}" } ?: ""
        return "<span style=\"font-size:1.4em\"><b>${esc(written)}</b>$lemma</span><br><b>${esc(w.gloss)}</b>$example"
    }

    /** The English of those cues: the scene's English lines that overlap them, else all of them. */
    fun english(scene: Scene, cues: List<Cue>): String {
        val start = cues.first().start
        val end = cues.last().end
        val overlapping = scene.english.filter { it.end > start + 0.1 && it.start < end - 0.1 }
        return (overlapping.ifEmpty { scene.english }).joinToString(" ") { it.text }
    }

    private fun esc(s: String) = TextUtils.htmlEncode(s)

    /** "You and I Are Polar Opposites" -> "you_and_i_are_polar_opposites", the show tag of the PC's cards. */
    fun slug(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')

    companion object {
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
