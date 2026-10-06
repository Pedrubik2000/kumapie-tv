package io.github.pedrubik2000.kumapie.mobile.german

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * spaCy's large German model (de_core_news_lg 3.8.0, the one morphs uses on the PC; ~610 MB unpacked). It is
 * downloaded once from spaCy's GitHub releases into the app's external folder, never part of the APK. A test
 * build can also get it pushed there over adb (`models/de_core_news_lg/` with config.cfg inside).
 */
class GermanModel(private val context: Context) {
    private val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    val dir = File(base, NAME)
    val isReady: Boolean get() = File(dir, "config.cfg").exists()
    private val work get() = WorkManager.getInstance(context)

    fun download() {
        val request = OneTimeWorkRequestBuilder<ModelWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** Download progress 0..1, a failure message, or null when no download is running. */
    fun state(): Flow<String?> = work.getWorkInfosForUniqueWorkFlow(WORK).map { infos ->
        val info = infos.firstOrNull() ?: return@map null
        when (info.state) {
            WorkInfo.State.RUNNING -> info.progress.getString(ModelWorker.STAGE) ?: "Starting…"
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting for a connection…"
            WorkInfo.State.FAILED -> "Failed: " + (info.outputData.getString(ModelWorker.ERROR) ?: "unknown error")
            else -> null
        }
    }

    companion object {
        const val NAME = "de_core_news_lg"
        const val VERSION = "3.8.0"
        const val URL = "https://github.com/explosion/spacy-models/releases/download/$NAME-$VERSION/$NAME-$VERSION-py3-none-any.whl"
        private const val WORK = "german-model"
    }
}

/** Downloads the model wheel (resumable), unpacks the model folder from it and deletes the wheel. */
class ModelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val model = GermanModel(context)

    override suspend fun doWork(): Result {
        if (model.isReady) return Result.success()
        runCatching { setForeground(foreground(0f)) }
        return try {
            withContext(Dispatchers.IO) { fetchAndUnpack() }
            Result.success()
        } catch (e: IOException) {
            Log.w("kumapie", "German model: $e")
            if (runAttemptCount < 8) Result.retry() else Result.failure(workDataOf(ERROR to (e.message ?: "network error")))
        }
    }

    private suspend fun fetchAndUnpack() {
        val base = model.dir.parentFile!!.apply { mkdirs() }
        val wheel = File(base, "${GermanModel.NAME}.whl.part")
        val have = if (wheel.exists()) wheel.length() else 0L
        val conn = URL(GermanModel.URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val append = code == 206
            val total = (if (append) have else 0L) + conn.contentLengthLong.coerceAtLeast(0)
            var done = if (append) have else 0L
            conn.inputStream.use { input ->
                FileOutputStream(wheel, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (isStopped) throw IOException("stopped")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) report("Downloading: ${done / 1_000_000} of ${total / 1_000_000} MB", 0.9f * done / total)
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("connection closed early")
        } finally {
            conn.disconnect()
        }

        // The wheel holds <name>/<name>-<version>/... : that inner folder is what spaCy loads.
        report("Unpacking…", 0.95f)
        val prefix = "${GermanModel.NAME}/${GermanModel.NAME}-${GermanModel.VERSION}/"
        val tmp = File(base, "${GermanModel.NAME}.tmp").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(wheel.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                val out = File(tmp, entry.name.removePrefix(prefix))
                if (!out.canonicalPath.startsWith(tmp.canonicalPath)) continue
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
        }
        if (!File(tmp, "config.cfg").exists()) throw IOException("the download is not a spaCy model")
        model.dir.deleteRecursively()
        if (!tmp.renameTo(model.dir)) throw IOException("couldn't move the model into place")
        wheel.delete()
    }

    private var lastReport = 0L

    private suspend fun report(stage: String, progress: Float) {
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
            .setContentTitle("Downloading the German model")
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION, notification)
        }
    }

    companion object {
        const val STAGE = "stage"
        const val ERROR = "error"
        private const val CHANNEL = "downloads" // shared with episode downloads
        private const val NOTIFICATION = 999
    }
}
