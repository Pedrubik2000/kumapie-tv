package io.github.pedrubik2000.kumapie.mobile.lang

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A big file the app downloads once (the German model, the dictionary): a foreground job with a notification,
 * resumed with HTTP Range after a break, retried by WorkManager. Subclasses fetch with [fetch] and unpack.
 */
abstract class AssetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    /** "the German model": the notification says "Downloading <what>". */
    abstract val what: String
    abstract val notificationId: Int
    abstract suspend fun run()

    override suspend fun doWork(): Result {
        runCatching { setForeground(foreground(0f)) }
        return try {
            withContext(Dispatchers.IO) { run() }
            Result.success()
        } catch (e: IOException) {
            Log.w("kumapie", "$what: $e")
            if (runAttemptCount < 8) Result.retry() else Result.failure(workDataOf(ERROR to (e.message ?: "network error")))
        }
    }

    /** GET [url] into [part], continuing a partial file; progress 0..[share] of the bar. */
    protected suspend fun fetch(url: String, part: File, share: Float = 0.9f) {
        val have = if (part.exists()) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            if (code == 416 && have > 0) return // already complete
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val append = code == 206
            val total = (if (append) have else 0L) + conn.contentLengthLong.coerceAtLeast(0)
            var done = if (append) have else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (isStopped) throw IOException("stopped")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) report("Downloading: ${done / 1_000_000} of ${total / 1_000_000} MB", share * done / total)
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("connection closed early")
        } finally {
            conn.disconnect()
        }
    }

    private var lastReport = 0L

    protected suspend fun report(stage: String, progress: Float) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 700) return
        lastReport = now
        setProgress(workDataOf(STAGE to stage))
        runCatching { setForeground(foreground(progress)) }
    }

    private fun foreground(progress: Float): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $what")
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val STAGE = "stage"
        const val ERROR = "error"
        private const val CHANNEL = "downloads" // shared with episode downloads
        /** Wikimedia asks for a descriptive User-Agent with a way to reach the maintainer: the public repo. */
        const val USER_AGENT = "kumapie/1 (https://github.com/Pedrubik2000/kumapie-tv)"

        /** A unique download's state for Settings: a progress line, a failure, or null when none is running. */
        fun state(work: WorkManager, name: String): Flow<String?> = work.getWorkInfosForUniqueWorkFlow(name).map { infos ->
            val info = infos.firstOrNull() ?: return@map null
            when (info.state) {
                WorkInfo.State.RUNNING -> info.progress.getString(STAGE) ?: "Starting…"
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting for a connection…"
                WorkInfo.State.FAILED -> "Failed: " + (info.outputData.getString(ERROR) ?: "unknown error")
                else -> null
            }
        }
    }
}
