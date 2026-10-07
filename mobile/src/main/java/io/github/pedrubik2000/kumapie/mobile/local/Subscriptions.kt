package io.github.pedrubik2000.kumapie.mobile.local

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Followed YouTube channels: their shorts or normal videos download by themselves, newest first, then older ones not
 * here yet, at most [Sub.perRun] a check and only between [Settings.subFrom] and [Settings.subTo] o'clock (night by
 * default). Kept in `subscriptions.json`; checked hourly by [SubscriptionWorker], which queues normal
 * [ProcessWorker] jobs (so they show on the Downloads page).
 */
class Subscriptions(private val context: Context) {
    data class Sub(val url: String, val name: String, val kind: String, val lang: String, val perRun: Int, val lastCheck: Long = 0)

    private val file = File(context.filesDir, "subscriptions.json")

    @Synchronized
    fun all(): List<Sub> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            Sub(o.getString("url"), o.optString("name"), o.optString("kind", "shorts"), o.optString("lang", "de"), o.optInt("perRun", 5), o.optLong("last"))
        } }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(list: List<Sub>) = file.writeText(JSONArray(list.map {
        JSONObject().put("url", it.url).put("name", it.name).put("kind", it.kind).put("lang", it.lang).put("perRun", it.perRun).put("last", it.lastCheck)
    }).toString())

    fun add(sub: Sub) { save(all().filter { !(it.url == sub.url && it.kind == sub.kind) } + sub); SubscriptionWorker.schedule(context) }
    fun remove(sub: Sub) = save(all().filter { !(it.url == sub.url && it.kind == sub.kind) })
}

/** Hourly: inside the allowed hours, each followed channel's next videos not here yet become download jobs. */
class SubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        val now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (!inHours(now, settings.subFrom, settings.subTo) && !inputData.getBoolean(NOW, false)) return Result.success()
        val subs = Subscriptions(applicationContext)
        val local = LocalEpisodes(applicationContext)
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        val yt = Python.getInstance().getModule("youtube")
        val qjs = File(applicationContext.applicationInfo.nativeLibraryDir, "libqjs.so").path
        // Videos already here or already queued (made here: their source link).
        val have = local.entries().map { ProcessWorker.idFor(it.source) }.toMutableSet()
        for (sub in subs.all()) runCatching {
            // Look a bit deeper than one run's share, so older videos are found once the newest are here.
            val urls = JSONArray(yt.callAttr("listing", sub.url, sub.kind, 200, qjs).toString())
            var queued = 0
            for (i in 0 until urls.length()) {
                if (queued >= sub.perRun) break
                val u = urls.getString(i)
                val id = ProcessWorker.idFor(u)
                if (id in have || queuedIds(applicationContext).contains(id)) continue
                ProcessWorker.start(applicationContext, u, sub.name.ifBlank { null }, settings.videoHeight, Lang.of(sub.lang),
                    settings.transcriber, settings.englishSource)
                have += id
                queued++
            }
            subs.save(subs.all().map { if (it.url == sub.url && it.kind == sub.kind) it.copy(lastCheck = System.currentTimeMillis()) else it })
        }
        return Result.success()
    }

    companion object {
        const val NOW = "now"
        private const val WORK = "subscriptions"

        /** [h] inside [from]..[to] o'clock, across midnight too (22..6). */
        fun inHours(h: Int, from: Int, to: Int) = if (from <= to) h in from until to else h >= from || h < to

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SubscriptionWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** "Check now" in Settings: ignores the hours once. */
        fun checkNow(context: Context) = WorkManager.getInstance(context).enqueue(
            androidx.work.OneTimeWorkRequestBuilder<SubscriptionWorker>().setInputData(androidx.work.workDataOf(NOW to true)).build())

        /** Ids of the jobs waiting or running, so a check doesn't queue a video twice. */
        private fun queuedIds(context: Context): Set<String> = WorkManager.getInstance(context).getWorkInfosByTag("process").get()
            .filter { !it.state.isFinished }.mapNotNull { info -> info.tags.firstOrNull { it.startsWith("id:") }?.removePrefix("id:") }.toSet()
    }
}

/** The hours subscriptions may download in (default 1 to 6 o'clock, at night). */
var Settings.subFrom: Int
    get() = prefs.getInt("sub_from", 1)
    set(value) = prefs.edit().putInt("sub_from", value).apply()
var Settings.subTo: Int
    get() = prefs.getInt("sub_to", 6)
    set(value) = prefs.edit().putInt("sub_to", value).apply()
