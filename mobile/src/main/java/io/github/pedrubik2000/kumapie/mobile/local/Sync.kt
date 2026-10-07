package io.github.pedrubik2000.kumapie.mobile.local

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.data.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Episodes made on this device go to the PC (dojo-feed's /api/sync, the hub); the episodes the owner's other devices
 * made come back from it, on Wi-Fi (plan: kumapie_languages_plan.md, "PC sync and Android <-> Android").
 * An episode deleted here is never fetched here again ([forget]); it stays on the PC and the other devices.
 */
class Sync(private val context: Context) {
    private val local = LocalEpisodes(context)
    /** Episodes the PC has (uploaded from here, or downloaded from it). */
    private val onPc = File(local.base, "synced.txt")
    /** Episodes deleted on this device: never downloaded here again. */
    private val never = File(local.base, "never.txt")

    private fun read(f: File) = runCatching { f.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
    private fun add(f: File, id: String) = synchronized(Sync::class.java) { if (id !in read(f)) f.appendText("$id\n") }

    /** Deletes a device episode here only, and keeps it from coming back. */
    fun forget(id: String) {
        add(never, id)
        local.remove(id)
    }

    /** Uploads what is new here, then downloads what is new on the PC. Answers one line per episode moved. */
    fun run(server: String, owner: String, progress: (String, Float) -> Unit = { _, _ -> }): List<String> {
        // Another sync already running (the hourly one and a "now" one): it does the work, this one leaves.
        if (!RUNNING.tryLock()) return emptyList()
        try { this.progress = progress; return runOnce(server, owner) } finally { RUNNING.unlock() }
    }

    /** "↑ title" / "↓ title" and how far (0..1), for the worker's notification. */
    private var progress: (String, Float) -> Unit = { _, _ -> }

    private fun runOnce(server: String, owner: String): List<String> {
        val base = "$server/api/sync/$owner"
        val done = ArrayList<String>()
        val have = read(onPc)
        for (e in local.entries()) {
            if (e.id in have || !local.video(e.id).exists()) continue
            runCatching { upload(base, e); add(onPc, e.id); done += "↑ ${e.title}" }
                .onFailure { Log.w("kumapie", "sync up ${e.id}: $it") }
        }
        val list = JSONArray(String(open("$base").inputStream.use { it.readBytes() }))
        val here = local.entries().map { it.id }.toSet()
        val skip = read(never)
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val id = e.getString("id")
            if (id in here || id in skip) continue
            runCatching { download(base, e); add(onPc, id); done += "↓ ${e.getString("title")}" }
                .onFailure { Log.w("kumapie", "sync down $id: $it") }
        }
        return done
    }

    private fun upload(base: String, e: LocalEpisodes.Entry) {
        val url = "$base/${e.id}"
        val got = JSONObject(String(open("$url/status").inputStream.use { it.readBytes() }))
        val files = mapOf("episode.json" to local.json(e.id), "thumb.jpg" to local.thumb(e.id), "video.mp4" to local.video(e.id))
        for ((name, file) in files) {
            if (!file.exists()) continue
            var offset = got.optLong(name, 0L)
            file.inputStream().use { input ->
                input.skip(offset)
                val buf = ByteArray(CHUNK)
                while (offset < file.length()) {
                    if (name == "video.mp4") progress("↑ ${e.title}", offset.toFloat() / file.length())
                    var n = 0
                    while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    if (n == 0) break
                    val c = open("$url/$name?offset=$offset", "PUT")
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(n)
                    c.outputStream.use { it.write(buf, 0, n) }
                    if (c.responseCode != 200) throw IOException("upload $name: HTTP ${c.responseCode}")
                    offset = JSONObject(String(c.inputStream.use { it.readBytes() })).getLong("size")
                }
            }
        }
        val meta = JSONObject().put("show", e.show).put("title", e.title).put("lang", e.lang)
            .put("translation", Lang.of(e.lang).translation).put("kind", e.kind).put("season", e.season ?: JSONObject.NULL)
            .put("number", e.number ?: JSONObject.NULL).put("duration", e.duration).put("source", e.source)
        val c = open("$url/done", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(meta.toString().toByteArray()) }
        if (c.responseCode != 200) throw IOException("upload done: HTTP ${c.responseCode}")
    }

    private fun download(base: String, e: JSONObject) {
        val id = e.getString("id")
        val url = "$base/$id"
        local.dir(id).mkdirs()
        // The video first (resumable), episode.json last: the episode shows up only once it is whole.
        fetch("$url/video.mp4", local.video(id), e.getString("title"))
        runCatching { fetch("$url/thumb.jpg", local.thumb(id)) }
        val episode = JSONObject(String(open("$url/episode.json").inputStream.use { it.readBytes() }))
        episode.put("video", local.video(id).path)
        local.json(id).writeText(episode.toString())
        local.add(LocalEpisodes.Entry(id, e.getString("show"), e.getString("title"), e.optDouble("duration"), e.optString("source"),
            e.optInt("season").takeIf { _ -> !e.isNull("season") }, e.optInt("number").takeIf { _ -> !e.isNull("number") },
            e.optInt("scenes", -1), e.optString("lang", "de"), e.optString("kind")))
    }

    /** [url] into [out], continuing a .part left by a dropped download. */
    private fun fetch(url: String, out: File, label: String = out.name) {
        val part = File(out.path + ".part")
        val c = open(url)
        if (part.length() > 0) c.setRequestProperty("Range", "bytes=${part.length()}-")
        val append = c.responseCode == 206
        if (c.responseCode != 200 && !append) throw IOException("download: HTTP ${c.responseCode}")
        val total = (if (append) part.length() else 0L) + c.contentLengthLong.coerceAtLeast(0L)
        c.inputStream.use { input ->
            java.io.FileOutputStream(part, append).use { out ->
                val buf = ByteArray(1 shl 16)
                var got = if (append) part.length() else 0L
                var shown = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    if (got - shown > (8 shl 20) && total > 0) { shown = got; progress("↓ $label", got.toFloat() / total) }
                }
            }
        }
        out.delete()
        if (!part.renameTo(out)) throw IOException("couldn't move ${out.name} into place")
    }

    private fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
        }

    companion object {
        private const val CHUNK = 4 shl 20 // 4 MB a request
        /** One sync at a time in this process (the hourly one and a "now" one can start together). */
        private val RUNNING = java.util.concurrent.locks.ReentrantLock()
    }
}

/** On Wi-Fi: hourly, after a new episode is made here, and when the app starts. Only when the device has an owner. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        if (settings.owner.isBlank() || settings.server.isBlank()) return Result.success()
        // In the foreground (a notification): a big episode takes longer than a background job may run, and goes on
        // with the screen off.
        runCatching { setForeground(foreground(tr("Syncing with the PC…"), -1f)) }
        return runCatching { Sync(applicationContext).run(settings.server, settings.owner) { what, f ->
            runCatching { setForegroundAsync(foreground(what, f)) }
        } }
            .fold({ lines -> if (lines.isNotEmpty()) Log.i("kumapie", "sync: ${lines.joinToString()}"); Result.success() },
                { Log.w("kumapie", "sync: $it"); Result.retry() })
    }

    private fun foreground(what: String, progress: Float): androidx.work.ForegroundInfo {
        val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(android.app.NotificationChannel(CHANNEL, tr("Sync with the PC"), android.app.NotificationManager.IMPORTANCE_LOW))
        }
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(tr("Syncing with the PC"))
            .setContentText(what)
            .setProgress(100, (progress * 100).toInt().coerceAtLeast(0), progress < 0f)
            .setOngoing(true).setSilent(true).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) androidx.work.ForegroundInfo(NOTIFICATION, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else androidx.work.ForegroundInfo(NOTIFICATION, n)
    }

    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIFICATION = 7301
        private const val WORK = "sync"
        private val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(wifi).build())
        }

        /** A sync soon (a new episode is ready, the app opened). */
        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(wifi).build())
        }
    }
}
