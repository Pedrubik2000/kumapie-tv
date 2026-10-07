package io.github.pedrubik2000.kumapie.mobile.offline

import android.app.NotificationChannel
import io.github.pedrubik2000.kumapie.i18n.tr
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one episode for offline viewing: the episode (scenes, words, definitions), the original video
 * (resumed with HTTP Range after a break), then the audio of every word and definition in it. The PC makes
 * audio it hasn't made before with edge-tts, one at a time, so a first download can take a while there.
 * Runs as a foreground job with a notification; WorkManager retries it when the connection drops.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val downloads = Downloads(context)

    override suspend fun doWork(): Result {
        val id = inputData.getString(ID) ?: return Result.failure()
        val title = inputData.getString(TITLE) ?: tr("episode")
        val api = Api(Settings(applicationContext).server)
        runCatching { setForeground(foreground(title, 0f)) }

        return try {
            withContext(Dispatchers.IO) { download(api, id, title) }
            Result.success()
        } catch (e: IOException) {
            Log.w("kumapie", "download $id: $e")
            if (runAttemptCount < 8) Result.retry() else Result.failure(workDataOf(ERROR to (e.message ?: tr("network error"))))
        }
    }

    private suspend fun download(api: Api, id: String, title: String) {
        val dir = downloads.dir(id).apply { mkdirs() }
        val json = api.episodeJson(id)
        val episode = api.parseEpisode(json)

        // The video: 0..85 % of the progress bar.
        val video = downloads.video(id)
        if (!video.exists()) {
            val part = File(dir, "video.mp4.part")
            fetch(episode.video, part, resume = true) { done, total ->
                report(title, "video", 0.85f * done / total)
            }
            part.renameTo(video)
        }

        // Audio: every word of the subtitles and of the definitions, and every definition read aloud.
        val words = (episode.scenes.flatMap { s -> s.cues.flatMap { c -> c.segments } } +
            episode.scenes.flatMap { s -> s.defs.values.flatMap { it.target } })
            .filter { it.word != null }.map { it.text.lowercase() }.distinct()
        val defs = episode.scenes.flatMap { s ->
            s.defs.keys.map { k -> Triple(s.id, k.substringBefore('|').toInt(), k.substringAfter('|')) }
        }
        val jobs = words.map { w -> api.wordAudio(w) to downloads.wordFile(w) } +
            defs.map { (scene, line, word) -> api.definitionAudio(scene, line, word) to downloads.definitionFile(scene, line, word) }
        var failed = 0
        jobs.forEachIndexed { i, (url, file) ->
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".part")
                // A missing audio file is not worth failing the episode for: it plays from the PC when online.
                runCatching { fetch(url, tmp, resume = false) { _, _ -> }; tmp.renameTo(file) }
                    .onFailure { failed++; tmp.delete() }
                if (failed > 50 && failed > i / 2) throw IOException(tr("the PC stopped answering"))
            }
            if (i % 10 == 0) report(title, "audio", 0.85f + 0.15f * i / jobs.size)
        }

        // Pictures for the lists offline (not worth failing for).
        inputData.getString(THUMB)?.let { url -> runCatching { fetch(url, downloads.thumb(id), resume = false) { _, _ -> } } }
        val show = inputData.getString(SHOW)
        val poster = inputData.getString(POSTER)
        if (show != null && poster != null && !downloads.poster(show).exists()) {
            downloads.poster(show).parentFile?.mkdirs()
            runCatching { fetch(poster, downloads.poster(show), resume = false) { _, _ -> } }
        }

        downloads.episodeJson(id).writeText(json)
        downloads.markComplete(id)
    }

    private var lastReport = 0L

    private suspend fun report(title: String, stage: String, progress: Float) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 700) return
        lastReport = now
        setProgress(workDataOf(STAGE to stage, PROGRESS to progress))
        runCatching { setForeground(foreground(title, progress)) }
    }

    /** GET [url] into [out]; with [resume] a partial file continues where it stopped. */
    private suspend fun fetch(url: String, out: File, resume: Boolean, progress: suspend (Long, Long) -> Unit) {
        val have = if (resume && out.exists()) out.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000 // the PC may be making the audio first
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            val append = code == 206
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val total = (if (append) have else 0L) + conn.contentLengthLong.coerceAtLeast(0)
            var done = if (append) have else 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(out, append).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (isStopped) throw IOException("stopped")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress(done, total)
                    }
                }
            }
            if (total > 0 && done < total) throw IOException(tr("connection closed early"))
        } finally {
            conn.disconnect()
        }
    }

    private fun foreground(title: String, progress: Float): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Downloads"), NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("Downloading %s", title))
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val notificationId = 1000 + (inputData.getString(ID).hashCode() and 0xffff)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val ID = "id"
        const val TITLE = "title"
        const val THUMB = "thumb"
        const val SHOW = "show"
        const val POSTER = "poster"
        const val STAGE = "stage"
        const val PROGRESS = "progress"
        const val ERROR = "error"
        private const val CHANNEL = "downloads"
    }
}
