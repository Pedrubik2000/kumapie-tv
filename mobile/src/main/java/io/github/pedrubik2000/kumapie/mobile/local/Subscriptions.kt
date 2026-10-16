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
 * [ProcessWorker] jobs (so they show on the Downloads page). [Sub.pc]: the jobs only send the videos to the PC, which
 * makes them; what was sent is kept in `sent-to-pc.txt` (the episodes come back with the PC's own source).
 *
 * The list is the same on all the owner's devices (user, 2026-10-15): changes go to the PC at the next [Sync]
 * (`follows-pending.json`) and the PC's list comes back. A channel downloads only on its [Sub.device], the one that
 * followed it; its episodes reach the other devices through the PC like any other.
 */
class Subscriptions(private val context: Context) {
    data class Sub(val url: String, val name: String, val kind: String, val lang: String, val perRun: Int, val lastCheck: Long = 0,
                   val pc: Boolean = false, val device: String = "") {
        val key get() = "$kind|$url"
        /** Downloads here: followed on this device (or before follows were shared). */
        val here get() = device.isBlank() || device == THIS_DEVICE
    }

    private val file = File(context.filesDir, "subscriptions.json")

    @Synchronized
    fun all(): List<Sub> = runCatching { parse(JSONArray(file.readText())) }.getOrDefault(emptyList())

    @Synchronized
    fun save(list: List<Sub>) = file.writeText(JSONArray(list.map { json(it).put("last", it.lastCheck) }).toString())

    /** A follow made here (or changed: how many a check). */
    fun add(sub: Sub) {
        val s = if (sub.device.isBlank()) sub.copy(device = THIS_DEVICE) else sub
        val list = all()
        save(if (list.any { it.key == s.key }) list.map { if (it.key == s.key) s.copy(lastCheck = it.lastCheck) else it } else list + s)
        pending { it.getJSONObject("add").put(s.key, json(s)); it.put("remove", without(it.getJSONArray("remove"), s.key)) }
        SubscriptionWorker.schedule(context)
    }

    fun remove(sub: Sub) {
        save(all().filter { it.key != sub.key })
        pending { it.getJSONObject("add").remove(sub.key); it.getJSONArray("remove").put(sub.key) }
    }

    // ------------------------------------------------------------ the same list on every device (through the PC)

    private val pendingFile = File(context.filesDir, "follows-pending.json")
    private val sharedFlag = File(context.filesDir, "follows-shared") // this device's list has gone to the PC once

    @Synchronized
    private fun pending(change: (JSONObject) -> Unit) {
        val p = runCatching { JSONObject(pendingFile.readText()) }.getOrDefault(JSONObject())
        if (!p.has("add")) p.put("add", JSONObject())
        if (!p.has("remove")) p.put("remove", JSONArray())
        change(p)
        pendingFile.writeText(p.toString())
    }

    /** What to send the PC: changes made here; the first time, this device's whole list too ("initial"). */
    @Synchronized
    fun changes(): JSONObject {
        val p = runCatching { JSONObject(pendingFile.readText()) }.getOrDefault(JSONObject())
        val add = p.optJSONObject("add") ?: JSONObject()
        val list = add.keys().asSequence().map { add.getJSONObject(it) }.toMutableList()
        if (!sharedFlag.exists()) all().forEach { list += json(if (it.device.isBlank()) it.copy(device = THIS_DEVICE) else it) }
        return JSONObject().put("add", JSONArray(list)).put("remove", p.optJSONArray("remove") ?: JSONArray())
            .put("initial", !sharedFlag.exists())
    }

    /** The PC's list after [changes] went: it becomes this device's (each keeps its own last check). */
    @Synchronized
    fun shared(answer: JSONObject) {
        val last = all().associate { it.key to it.lastCheck }
        save(parse(answer.getJSONArray("subs")).map { it.copy(lastCheck = last[it.key] ?: 0) })
        pendingFile.delete()
        sharedFlag.writeText("1")
    }

    private fun json(s: Sub) = JSONObject().put("url", s.url).put("name", s.name).put("kind", s.kind).put("lang", s.lang)
        .put("perRun", s.perRun).put("pc", s.pc).put("device", s.device)

    private fun parse(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).let { o ->
        Sub(o.getString("url"), o.optString("name"), o.optString("kind", "shorts"), o.optString("lang", "de"), o.optInt("perRun", 5), o.optLong("last"),
            o.optBoolean("pc"), o.optString("device"))
    } }

    private fun without(a: JSONArray, key: String) = JSONArray((0 until a.length()).map { a.getString(it) }.filter { it != key })

    private val sent = File(context.filesDir, "sent-to-pc.txt")

    /** Ids ([ProcessWorker.idFor]) of the videos sent to the PC to be made there. */
    @Synchronized
    fun sentToPc(): Set<String> = if (sent.exists()) sent.readLines().toSet() else emptySet()

    @Synchronized
    fun markSent(id: String) = sent.appendText(id + "\n")

    companion object {
        /** This device's name in the shared list ("Pixel 8a", "SM-T970"). */
        val THIS_DEVICE: String = android.os.Build.MODEL
    }
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
        val have = (local.entries().map { ProcessWorker.idFor(it.source) } + subs.sentToPc()).toMutableSet()
        for (sub in subs.all().filter { it.here }) runCatching { // the others download on their own device
            // Look a bit deeper than one run's share, so older videos are found once the newest are here.
            val urls = JSONArray(yt.callAttr("listing", sub.url, sub.kind, 200, qjs).toString())
            var queued = 0
            for (i in 0 until urls.length()) {
                if (queued >= sub.perRun) break
                val u = urls.getString(i)
                val id = ProcessWorker.idFor(u)
                if (id in have || queuedIds(applicationContext).contains(id)) continue
                ProcessWorker.start(applicationContext, u, sub.name.ifBlank { null }, settings.videoHeight, Lang.of(sub.lang),
                    settings.transcriber, settings.englishSource, pc = sub.pc)
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
