package io.github.pedrubik2000.kumapie.mobile.local

import io.github.pedrubik2000.kumapie.data.Lang
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaCodec
import android.net.Uri
import android.provider.OpenableColumns
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.mobile.lang.GermanModel
import io.github.pedrubik2000.kumapie.mobile.lang.JapaneseModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The Soniox key and where the English comes from, kept in the app's private settings (never in the repo). */
var Settings.sonioxKey: String
    get() = prefs.getString("soniox_key", "") ?: ""
    set(value) = prefs.edit().putString("soniox_key", value.trim()).apply()

/** The Real-Debrid API token (real-debrid.com/apitoken), typed into Settings by the user, kept only here. */
var Settings.rdToken: String
    get() = prefs.getString("rd_token", "") ?: ""
    set(value) = prefs.edit().putString("rd_token", value.trim()).apply()

/** The Jimaku API key (jimaku.cc > Account) for Japanese subtitles, typed into Settings by the user, kept only here. */
var Settings.jimakuKey: String
    get() = prefs.getString("jimaku_key", "") ?: ""
    set(value) = prefs.edit().putString("jimaku_key", value.trim()).apply()

/** The highest video quality for new episodes (360 / 480 / 720 / 1080, H.264), remembered. */
var Settings.videoHeight: Int
    get() = prefs.getInt("video_height", 720)
    set(value) = prefs.edit().putInt("video_height", value).apply()

/**
 * "device" (Google's on-device translator: free, offline, instant, rough), "gemma" (Gemma on the device: free, offline,
 * about 3 s a line, good), "soniox" (Soniox translates while transcribing: best) or "none" (no English line).
 */
var Settings.englishSource: String
    get() = prefs.getString("english_source", "device") ?: "device"
    set(value) = prefs.edit().putString("english_source", value).apply()

/**
 * Makes a kumapie episode on the device, like the PC's pipeline, from a YouTube link (yt-dlp + QuickJS, MediaMuxer),
 * a Real-Debrid magnet / link (python/realdebrid.py: a season becomes one job per episode) or a video file on the
 * device (content://) → one MP4 with the German audio (Transformer) → Soniox (German, and English if chosen) → German cues → English (device translator, unless
 * Soniox gave it) → scenes and words (spaCy) → `local/<id>/episode.json`. A foreground job with a notification; all jobs
 * run one after another in one queue (one transcription at a time). A failure is not retried, so Soniox is never paid
 * twice for one link, and doesn't stop the jobs queued after it.
 */
class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val local = LocalEpisodes(context)
    private val settings = Settings(context)
    private var height = 720
    private var lang = Lang.GERMAN
    /** This job's choices, picked in Add an episode ("soniox" / "parakeet"; "soniox" / "gemma" / "device" / "none"). */
    private var transcriber = "soniox"
    private var english = "device"

    override suspend fun doWork(): Result {
        val url = inputData.getString(URL) ?: return Result.failure()
        val show = inputData.getString(SHOW)?.takeIf { it.isNotBlank() }
        height = inputData.getInt(HEIGHT, 720)
        lang = Lang.of(inputData.getString(LANG))
        transcriber = inputData.getString(TRANSCRIBER) ?: settings.transcriber
        english = inputData.getString(ENGLISH) ?: settings.englishSource
        val id = idFor(url)
        runCatching { setForeground(foreground("Starting…", 0f)) }
        return try {
            Result.success(workDataOf(STAGE to withContext(Dispatchers.IO) { process(id, url, show) }))
        } catch (e: Throwable) {
            Log.w("kumapie", "process $url: $e")
            // Succeeds anyway, so the episodes queued after this one still run.
            Result.success(workDataOf(STAGE to "Failed: " + (e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.toString())))
        }
    }

    private suspend fun process(id: String, url: String, showName: String?): String {
        if (local.json(id).exists()) return "Already here: " + (local.entries().firstOrNull { it.id == id }?.title ?: url)
        val youtube = isYouTube(url)
        val file = url.startsWith("content:")
        val rdToken = if (youtube || file) "" else settings.rdToken.ifBlank { error("Add your Real-Debrid token in Settings first.") }
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        val py = Python.getInstance()
        val logger = Logger()
        val rd = py.getModule("realdebrid")

        // A magnet or link not yet split: one job per video file in it, queued after this one.
        if (!youtube && !file && !inputData.getBoolean(PART, false)) {
            report("Asking Real-Debrid…", 0.02f)
            val files = JSONArray(ticking(0.02f) { rd.callAttr("episodes", rdToken, url, logger).toString() })
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val about = JSONObject(rd.callAttr("describe", f.getString("name"), f.getBoolean("single")).toString())
                // Japanese: blank stays blank, so the episode takes its AniList name.
                enqueue(applicationContext, f.getString("link"), showName ?: about.getString("show").takeIf { lang != Lang.JAPANESE }, height, lang, transcriber, english, part = true)
            }
            return "Real-Debrid: ${files.length()} episode(s) queued"
        }

        // Japanese: subtitles from Jimaku instead of a transcription (kumapie_languages_plan.md, step 4).
        val japanese = lang == Lang.JAPANESE
        if (japanese && youtube) error("Japanese episodes come from Real-Debrid or a video file (subtitles from Jimaku).")
        val jimakuKey = if (japanese) settings.jimakuKey.ifBlank { error("Add your Jimaku API key in Settings first.") } else ""
        if (japanese && !JapaneseModel(applicationContext).isReady) error("Download the Japanese words dictionary in Settings first.")
        val parakeetHere = !japanese && transcriber == "parakeet"
        val key = if (parakeetHere || japanese) "" else settings.sonioxKey.ifBlank { error("Add your Soniox key in Settings first.") }
        if (parakeetHere && !Parakeet(applicationContext).isReady) error("Download the speech model (Parakeet) in Settings first.")
        val gemma = Gemma(applicationContext)
        if (english == "gemma" && !gemma.isReady) error("Download the translation model (Gemma) in Settings first.")
        val model = GermanModel(applicationContext)
        if (!japanese && !model.isReady) error("Download the German model in Settings first.")
        val dir = local.dir(id).apply { mkdirs() }
        val dl = File(dir, "download").apply { mkdirs() }
        val embedded = File(dir, "embedded").apply { mkdirs() }
        fun inside(code: String) = embedded.listFiles()?.firstOrNull { it.name.startsWith("embedded.$code.") }

        // 1. Download, and 2. one MP4 for the player (the episode's language) + the audio for Soniox / Parakeet / the sync.
        val video = local.video(id)
        val audio = File(dir, "audio.m4a")
        val title: String
        var show: String
        var sourceName = ""
        var season: Int? = null
        var number: Int? = null
        if (youtube) {
            report("Downloading…", 0.02f)
            val qjs = File(applicationContext.applicationInfo.nativeLibraryDir, "libqjs.so").path
            val got = JSONObject(ticking(0.02f) { py.getModule("youtube").callAttr("download", url, dl.path, qjs, logger, height).toString() })
            title = got.getString("title")
            show = showName ?: got.optString("channel").ifBlank { "YouTube" }
            report("Putting the video together…", 0.35f)
            val audioPart = got.optString("audio").takeIf { it.isNotBlank() && it != "null" }
            if (audioPart != null) {
                mux(got.getString("video"), audioPart, video)
                File(audioPart).copyTo(audio, overwrite = true)
            } else {
                File(got.getString("video")).copyTo(video, overwrite = true)
                extractAudio(video, audio)
            }
        } else {
            // A retry after a later step failed: the thumbnail is written only once the video is whole, so the download
            // and the conversion are skipped (the file's name is kept in source.txt).
            val kept = File(dir, "source.txt")
            val converted = local.thumb(id).exists() && kept.exists()
            val source = if (file) Uri.parse(url) else if (converted) null else {
                report("Downloading…", 0.02f)
                Uri.fromFile(File(ticking(0.02f) { rd.callAttr("download", rdToken, url, dl.path, logger).toString() }))
            }
            val name = if (converted) kept.readText() else if (file) displayName(source!!) else source!!.lastPathSegment ?: "video"
            if (!converted) kept.writeText(name)
            sourceName = name
            val about = JSONObject(rd.callAttr("describe", name, file).toString())
            title = about.getString("title")
            show = showName ?: about.getString("show")
            season = about.optInt("season").takeIf { !about.isNull("season") }
            number = about.optInt("number").takeIf { !about.isNull("number") }
            // Subtitles inside an MKV are made for this very video (no sync): read them before it is converted.
            val mkv = source?.takeIf { it.scheme == "file" }?.path?.takeIf { it.endsWith(".mkv", ignoreCase = true) }
            if (mkv != null && embedded.list().isNullOrEmpty()) {
                report("Reading the subtitles inside the video…", 0.3f)
                for (code in setOf(lang.code, "en")) py.getModule("mkvsubs").callAttr("extract", mkv, code, embedded.path)
            }
            if (!converted) {
                report("Converting the video (${lang.name} audio)…", 0.35f)
                remux(source!!, video)
            }
            if (!converted || !audio.exists()) extractAudio(video, audio)
        }
        thumbnail(video, local.thumb(id))
        val duration = durationOf(video)

        // 3. Japanese: Jimaku's subtitles fitted to the audio. Else Soniox, or Parakeet on the tablet (then the English
        // comes from Gemma or the device's translator).
        val soniox = english == "soniox" && !parakeetHere && !japanese
        var sonioxEnglish = JSONArray()
        var synced = ""
        val cues = if (japanese && inside("ja") != null) {
            synced = "inside the video"
            py.getModule("jimaku").callAttr("cues", inside("ja")!!.path, "ja").toString()
        } else if (japanese) {
            report("Finding Japanese subtitles on Jimaku…", 0.45f)
            val jm = py.getModule("jimaku")
            val found = JSONObject(jm.callAttr("candidates", jimakuKey, show, season, number, sourceName).toString())
            if (showName == null) found.optJSONObject("anilist")?.optString("romaji")?.takeIf { it.isNotBlank() }?.let { show = it }
            val files = found.getJSONArray("files")
            if (files.length() == 0) error("No Japanese subtitles on Jimaku for $show" + (number?.let { " episode $it" } ?: ""))
            val pick = files.getJSONObject(0)
            val raw = File(jm.callAttr("download", jimakuKey, pick.getString("url"), pick.getString("name"), number, dl.path).toString())
            report("Fitting the subtitles to the audio…", 0.55f)
            val fitted = File(dir, "subtitles." + raw.extension.lowercase())
            // The video's own English subtitles (timed to it) are the best reference; else the speech in the audio.
            val reference = inside("en")?.let { JSONArray(py.getModule("jimaku").callAttr("cues", it.path, "en").toString()) }
            synced = SubSync(applicationContext).sync(audio, raw, fitted, reference)
            File(dir, "subtitles.txt").writeText("${pick.getString("name")}\nsynced: $synced\n") // for a manual fix later
            jm.callAttr("cues", fitted.path).toString()
        } else {
            val kept = File(dir, "transcript.json")
            val transcript = if (kept.exists()) kept.readText() else if (parakeetHere) { // a retry: never pay Soniox twice
                Parakeet(applicationContext).transcribe(audio) { report(it, 0.55f) }
            } else {
                report("Transcribing with Soniox…", 0.45f)
                py.getModule("newepisode").callAttr("transcribe", audio.path, key, "de", if (soniox) "en" else "", logger).toString()
            }
            kept.writeText(transcript) // Soniox's answer, kept (redoing it would cost again)
            sonioxEnglish = JSONObject(transcript).getJSONArray("english")
            py.getModule("newepisode").callAttr("cues", transcript).toString()
        }

        // 4. English: the video's own English subtitles first, else Soniox's, else a translation (or none).
        val englishFile = inside("en")
        val englishCues = when {
            englishFile != null -> py.getModule("jimaku").callAttr("cues", englishFile.path, "en").toString()
            soniox && sonioxEnglish.length() > 0 -> sonioxEnglish.toString()
            english == "none" -> "[]"
            english == "gemma" -> withTranslator { translator ->
                gemma.englishCues(JSONArray(cues), { report("Translating to English with Gemma: $it%", 0.75f + 0.15f * it / 100) }, lang.name) {
                    translator.translate(it).await() // a line Gemma skipped
                }
            }
            else -> {
                report("Translating to English on the device…", 0.75f)
                translate(JSONArray(cues))
            }
        }

        // 5. Scenes and words (Japanese words from Sudachi here, German ones from spaCy in Python).
        report("Finding scenes and words…", 0.9f)
        val parsed = if (!japanese) "" else {
            val texts = JSONArray(cues).let { a -> (0 until a.length()).map { a.getJSONArray(it).getString(2) } }
            JSONArray(JapaneseModel(applicationContext).words(texts).map { tokens ->
                JSONArray(tokens.map { t -> JSONArray().put(t.surface).put(t.begin).put(if (t.isWord) t.base else JSONObject.NULL).put(JSONObject.NULL) })
            }).toString()
        }
        val episode = py.getModule("newepisode").callAttr("build", model.dir.path, id, show, title, duration,
            video.path, cues, englishCues, lang.code, parsed).toString()
        local.json(id).writeText(episode)
        audio.delete() // kept until here, so a retry after a failure needs no new download or conversion
        dl.deleteRecursively()
        embedded.deleteRecursively()
        local.add(LocalEpisodes.Entry(id, show, title, duration, url, season, number))
        val done = "Done: $title" + (if (synced.isNotEmpty()) " (subtitles $synced)" else "") +
            (if (englishFile != null) " (English from the video)" else "")
        report(done, 1f)
        return done
    }

    /** A Python call that blocks for long (download, Real-Debrid), its last progress line shown every second. */
    private suspend fun <T> ticking(progress: Float, block: () -> T): T = coroutineScope {
        val ticker = launch { while (true) { delay(1000); if (lastLine.isNotEmpty()) report(lastLine, progress) } }
        try { block() } finally { ticker.cancel(); lastLine = "" }
    }

    /** A picked file's name ("Dark S01E03.mkv"). */
    private fun displayName(uri: Uri): String = applicationContext.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "video"

    /**
     * Any video the device can read (MKV, MP4…) → an MP4 with its German audio track (the first one if none is tagged
     * German), the video copied as it is and the audio as AAC (re-encoded only when it isn't AAC already).
     */
    @OptIn(UnstableApi::class)
    private suspend fun remux(source: Uri, out: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val ctx = applicationContext
            val german = DefaultTrackSelector.Parameters.Builder(ctx).setPreferredAudioLanguage(lang.code)
                .setForceHighestSupportedBitrate(true).setConstrainAudioChannelCountToDeviceCapabilities(false).build()
            val loader = ExoPlayerAssetLoader.Factory(ctx, DefaultDecoderFactory(ctx), Clock.DEFAULT, DefaultMediaSourceFactory(ctx),
                { c -> DefaultTrackSelector(c).apply { setParameters(german) } }, null, DefaultLoadControl())
            val transformer = Transformer.Builder(ctx)
                .setAssetLoaderFactory(loader)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                        Log.w("kumapie", "remux: $e")
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Couldn't convert the video: ${e.errorCodeName}", e))
                    }
                })
                .build()
            transformer.start(EditedMediaItem.Builder(MediaItem.fromUri(source)).build(), out.path)
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }

    /** German cues → English cues with the same times, by Google's on-device translator (model ~30 MB, once). */
    private suspend fun translate(cues: JSONArray): String = withTranslator { translator ->
        val out = JSONArray()
        for (i in 0 until cues.length()) {
            val c = cues.getJSONArray(i)
            out.put(JSONArray().put(c.getDouble(0)).put(c.getDouble(1)).put(translator.translate(c.getString(2)).await()))
            if (i % 20 == 0) report("Translating to English: ${i * 100 / cues.length()}%", 0.75f + 0.15f * i / cues.length())
        }
        out.toString()
    }

    /** Google's on-device German → English translator (model ~30 MB, downloaded once), closed afterwards. */
    private suspend fun <T> withTranslator(use: suspend (com.google.mlkit.nl.translate.Translator) -> T): T {
        val translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.fromLanguageTag(lang.code)!!).setTargetLanguage(TranslateLanguage.ENGLISH).build())
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            return use(translator)
        } finally {
            translator.close()
        }
    }

    /** H.264 video + AAC audio files → one MP4, without re-encoding. */
    private fun mux(videoPath: String, audioPath: String, out: File) {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = listOf(videoPath, audioPath).map { path ->
            MediaExtractor().apply { setDataSource(path) }.let { ex ->
                val track = (0 until ex.trackCount).first { t ->
                    val mime = ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME) ?: ""
                    if (path == videoPath) mime.startsWith("video/") else mime.startsWith("audio/")
                }
                ex.selectTrack(track)
                Triple(ex, muxer.addTrack(ex.getTrackFormat(track)), track)
            }
        }
        muxer.start()
        val buf = ByteBuffer.allocate(4 shl 20)
        val info = MediaCodec.BufferInfo()
        for ((ex, track, _) in sources) {
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                info.set(0, size, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buf, info)
                ex.advance()
            }
            ex.release()
        }
        muxer.stop()
        muxer.release()
    }

    /** The audio track of an MP4 → .m4a for Soniox (copied, not re-encoded). */
    private fun extractAudio(video: File, out: File) {
        val ex = MediaExtractor().apply { setDataSource(video.path) }
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val t = muxer.addTrack(ex.getTrackFormat(track))
        muxer.start()
        val buf = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            info.set(0, size, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(t, buf, info)
            ex.advance()
        }
        muxer.stop(); muxer.release(); ex.release()
    }

    private fun thumbnail(video: File, out: File) = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(video.path)
            r.getFrameAtTime(15_000_000)?.let { bmp -> out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        }
    }

    private fun durationOf(video: File): Double = runCatching {
        MediaMetadataRetriever().use { r -> r.setDataSource(video.path); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000 }
    }.getOrDefault(0.0)

    /** Python's progress lines (download %, Soniox stages) → the notification. */
    inner class Logger {
        fun log(line: String) {
            Log.i("kumapie", "process: $line")
            lastLine = line
        }
    }

    @Volatile private var lastLine = ""
    private var lastReport = 0L

    private suspend fun report(stage: String, progress: Float) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 500 && progress < 1f) return
        lastReport = now
        setProgress(workDataOf(STAGE to stage))
        runCatching { setForeground(foreground(stage, progress)) }
    }

    private fun foreground(stage: String, progress: Float): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("New episode")
            .setContentText(stage)
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(997, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(997, n)
    }

    companion object {
        const val URL = "url"
        const val SHOW = "show"
        const val HEIGHT = "height"
        /** The episode's language code (Lang): "de" (default) or "ja". */
        const val LANG = "lang"
        const val TRANSCRIBER = "transcriber"
        const val ENGLISH = "english"
        /** A file of a magnet / link already split into episodes. */
        const val PART = "part"
        const val STAGE = "stage"
        const val ERROR = "error"
        private const val CHANNEL = "downloads"
        private const val TAG = "process"
        private const val QUEUE = "process-queue"

        fun isYouTube(url: String) = Regex("""^https?://([\w-]+\.)?(youtube\.com|youtu\.be)/""").containsMatchIn(url)

        /** One id per video: a YouTube link counts by its video id, so links shared with tracking (`?si=`) don't repeat it. */
        fun idFor(url: String): String {
            val video = Regex("""(?:youtu\.be/|[?&]v=|/shorts/|/live/|/embed/)([\w-]{11})""").find(url)?.groupValues?.get(1)
            return "local-" + MessageDigest.getInstance("SHA-1").digest((video?.let { "youtube:$it" } ?: url.trim()).toByteArray())
                .joinToString("") { "%02x".format(it) }.take(12)
        }

        /** Queues [url] (YouTube / magnet / Real-Debrid link / content:// file) after the jobs already waiting. */
        fun start(context: Context, url: String, show: String?, height: Int, lang: Lang, transcriber: String, english: String) =
            enqueue(context, url.trim(), show, height, lang, transcriber, english, part = false)

        private fun enqueue(context: Context, url: String, show: String?, height: Int, lang: Lang, transcriber: String,
                            english: String, part: Boolean) {
            // A file on the device with Parakeet needs no connection.
            val offline = url.startsWith("content:") && transcriber == "parakeet" && lang == Lang.GERMAN && english != "soniox"
            val req = OneTimeWorkRequestBuilder<ProcessWorker>()
                .setInputData(workDataOf(URL to url, SHOW to show, HEIGHT to height, LANG to lang.code, TRANSCRIBER to transcriber,
                    ENGLISH to english, PART to part))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (offline) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build())
                .addTag(TAG).build()
            WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        /** Every job's line for the home screen: "<url>: stage" / failed / done. */
        fun states(context: Context): Flow<List<String>> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
            infos.filter { it.state != WorkInfo.State.CANCELLED }.sortedBy { it.state.isFinished }.map { info ->
                when (info.state) {
                    WorkInfo.State.RUNNING -> info.progress.getString(STAGE) ?: "Working…"
                    WorkInfo.State.ENQUEUED -> "Waiting for a connection…"
                    WorkInfo.State.BLOCKED -> "Queued"
                    WorkInfo.State.FAILED -> "Failed: " + (info.outputData.getString(ERROR) ?: "unknown error")
                    WorkInfo.State.SUCCEEDED -> info.outputData.getString(STAGE) ?: "Done"
                    else -> ""
                }
            }.filter { it.isNotEmpty() }
        }
    }
}

/** A Play-services Task as a suspending call. */
internal suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
