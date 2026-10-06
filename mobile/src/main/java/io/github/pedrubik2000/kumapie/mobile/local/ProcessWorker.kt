package io.github.pedrubik2000.kumapie.mobile.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaCodec
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
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.mobile.german.GermanModel
import kotlinx.coroutines.Dispatchers
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

/** "device" (Google's on-device translator, free, offline) or "soniox" (Soniox translates while transcribing). */
var Settings.englishSource: String
    get() = prefs.getString("english_source", "device") ?: "device"
    set(value) = prefs.edit().putString("english_source", value).apply()

/**
 * Makes a kumapie episode from a YouTube link on the device, like the PC's pipeline: download (yt-dlp + QuickJS) →
 * one MP4 (MediaMuxer) → Soniox (German, and English if chosen) → German cues → English (device translator, unless
 * Soniox gave it) → scenes and words (spaCy) → `local/<id>/episode.json`. A foreground job with a notification; it is
 * not retried after a failure, so Soniox is never paid twice for one link.
 */
class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val local = LocalEpisodes(context)
    private val settings = Settings(context)

    override suspend fun doWork(): Result {
        val url = inputData.getString(URL) ?: return Result.failure()
        val show = inputData.getString(SHOW)?.takeIf { it.isNotBlank() }
        val id = idFor(url)
        runCatching { setForeground(foreground("Starting…", 0f)) }
        return try {
            withContext(Dispatchers.IO) { process(id, url, show) }
            Result.success(workDataOf(STAGE to "Done"))
        } catch (e: Throwable) {
            Log.w("kumapie", "process $url: $e")
            Result.failure(workDataOf(ERROR to (e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.toString())))
        }
    }

    private suspend fun process(id: String, url: String, showName: String?) {
        val key = settings.sonioxKey.ifBlank { error("Add your Soniox key in Settings first.") }
        val model = GermanModel(applicationContext)
        if (!model.isReady) error("Download the German model in Settings first.")
        val dir = local.dir(id).apply { mkdirs() }
        val dl = File(dir, "download").apply { mkdirs() }
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        val py = Python.getInstance()
        val logger = Logger()

        // 1. Download.
        report("Downloading…", 0.02f)
        val qjs = File(applicationContext.applicationInfo.nativeLibraryDir, "libqjs.so").path
        val got = JSONObject(py.getModule("youtube").callAttr("download", url, dl.path, qjs, logger).toString())
        val title = got.getString("title")
        val show = showName ?: got.optString("channel").ifBlank { "YouTube" }

        // 2. One MP4 for the player, and the audio for Soniox.
        report("Putting the video together…", 0.35f)
        val video = local.video(id)
        val audio = File(dir, "audio.m4a")
        val audioPart = got.optString("audio").takeIf { it.isNotBlank() && it != "null" }
        if (audioPart != null) {
            mux(got.getString("video"), audioPart, video)
            File(audioPart).copyTo(audio, overwrite = true)
        } else {
            File(got.getString("video")).copyTo(video, overwrite = true)
            extractAudio(video, audio)
        }
        dl.deleteRecursively()
        thumbnail(video, local.thumb(id))
        val duration = got.optDouble("duration").takeIf { it > 0 } ?: durationOf(video)

        // 3. Soniox.
        val soniox = settings.englishSource == "soniox"
        report("Transcribing with Soniox…", 0.45f)
        val transcript = py.getModule("newepisode").callAttr("transcribe", audio.path, key, "de", if (soniox) "en" else "", logger).toString()
        audio.delete()
        File(dir, "transcript.json").writeText(transcript) // Soniox's answer, kept (redoing it would cost again)
        val cues = py.getModule("newepisode").callAttr("cues", transcript).toString()

        // 4. English.
        val sonioxEnglish = JSONObject(transcript).getJSONArray("english")
        val english = if (soniox && sonioxEnglish.length() > 0) sonioxEnglish.toString() else {
            report("Translating to English on the device…", 0.75f)
            translate(JSONArray(cues))
        }

        // 5. Scenes and words.
        report("Finding scenes and words…", 0.9f)
        val episode = py.getModule("newepisode").callAttr("build", model.dir.path, id, show, title, duration,
            video.path, cues, english).toString()
        local.json(id).writeText(episode)
        local.add(LocalEpisodes.Entry(id, show, title, duration, url))
        report("Done: $title", 1f)
    }

    /** German cues → English cues with the same times, by Google's on-device translator (model ~30 MB, once). */
    private suspend fun translate(cues: JSONArray): String {
        val translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.GERMAN).setTargetLanguage(TranslateLanguage.ENGLISH).build())
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val out = JSONArray()
            for (i in 0 until cues.length()) {
                val c = cues.getJSONArray(i)
                out.put(JSONArray().put(c.getDouble(0)).put(c.getDouble(1)).put(translator.translate(c.getString(2)).await()))
                if (i % 20 == 0) report("Translating to English: ${i * 100 / cues.length()}%", 0.75f + 0.15f * i / cues.length())
            }
            return out.toString()
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
        const val STAGE = "stage"
        const val ERROR = "error"
        private const val CHANNEL = "downloads"
        private const val TAG = "process"

        fun idFor(url: String) = "local-" + MessageDigest.getInstance("SHA-1").digest(url.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)

        fun start(context: Context, url: String, show: String?) {
            val req = OneTimeWorkRequestBuilder<ProcessWorker>()
                .setInputData(workDataOf(URL to url.trim(), SHOW to show))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG).build()
            WorkManager.getInstance(context).enqueueUniqueWork(TAG + idFor(url), ExistingWorkPolicy.KEEP, req)
        }

        /** Every job's line for the home screen: "<url>: stage" / failed / done. */
        fun states(context: Context): Flow<List<String>> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
            infos.filter { it.state != WorkInfo.State.CANCELLED }.sortedBy { it.state.isFinished }.map { info ->
                when (info.state) {
                    WorkInfo.State.RUNNING -> info.progress.getString(STAGE) ?: "Working…"
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting for a connection…"
                    WorkInfo.State.FAILED -> "Failed: " + (info.outputData.getString(ERROR) ?: "unknown error")
                    WorkInfo.State.SUCCEEDED -> info.outputData.getString(STAGE) ?: "Done"
                    else -> ""
                }
            }.filter { it.isNotEmpty() }
        }
    }
}

/** A Play-services Task as a suspending call. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
