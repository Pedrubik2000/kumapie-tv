package io.github.pedrubik2000.kumapie.mobile.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.lang.YomitanDictionaries
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * kumapie copied from one device to another on the same Wi-Fi, without the PC (kumapie_languages_plan.md "PC sync and
 * Android <-> Android"): the sending device serves its episodes, dictionaries, models and keys over HTTP, found through
 * Android's network service discovery and guarded by a 4-digit code it shows; the receiving one copies what it doesn't
 * have yet. Each episode / dictionary / model is a folder or file under the app's external files, copied into
 * `.transfer/` first (resumable) and moved into place whole. Progress, known words and the owner stay per device.
 */
class Transfer(private val context: Context) {
    private val base = context.getExternalFilesDir(null) ?: context.filesDir

    class Item(val path: String, val category: String, val title: String, val files: List<Pair<String, Long>>, val extra: JSONObject?) {
        val size get() = files.sumOf { it.second }
    }

    /** What this device can give, by [CATEGORIES]. */
    fun items(): List<Item> {
        val out = ArrayList<Item>()
        fun add(path: String, category: String, title: String, extra: JSONObject? = null) {
            val root = File(base, path)
            val files = root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }
                .map { it.relativeTo(base).invariantSeparatorsPath to it.length() }.toList()
            if (files.isNotEmpty()) out += Item(path, category, title, files, extra)
        }
        LocalEpisodes(context).entries().forEach { add("local/${it.id}", "episodes", "${it.show} · ${it.title}", LocalEpisodes.toJson(it)) }
        File(base, "episodes").listFiles()?.filter { File(it, "done").exists() }?.forEach { dir ->
            val e = runCatching { JSONObject(File(dir, "episode.json").readText()) }.getOrNull()
            add("episodes/${dir.name}", "episodes", e?.let { "${it.optString("show")} · ${it.optString("title")}" } ?: dir.name)
        }
        YomitanDictionaries.get(context).all.value.forEach { add("yomitan/${it.folder}", "dictionaries", it.title, YomitanDictionaries.toJson(it)) }
        File(base, "dictionary").listFiles()?.forEach { add("dictionary/${it.name}", "dictionaries", it.name) }
        File(base, "models").listFiles()?.filter { !it.name.endsWith(".part") }?.forEach {
            add("models/${it.name}", if (it.name == "ja-audio.sqlite") "dictionaries" else "models", it.name)
        }
        return out
    }

    // ------------------------------------------------------------------ sending

    /** The sending side: answers /list and /file (with Range) for [code] until [close]d. */
    inner class Server(private val code: String) : AutoCloseable {
        private val socket = ServerSocket(0)
        val port: Int get() = socket.localPort
        @Volatile var lastUse = System.currentTimeMillis()
            private set
        @Volatile private var allowed: Set<String> = emptySet()

        fun start() = Thread {
            while (!socket.isClosed) {
                // ponytail: one request at a time; the receiver copies one file after another anyway
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { s.use(::handle) }.onFailure { Log.w("kumapie", "transfer: $it") }
            }
        }.apply { isDaemon = true; start() }

        private fun handle(s: Socket) {
            val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val target = input.readLine()?.split(" ")?.getOrNull(1) ?: return
            var range = 0L
            while (true) {
                val h = input.readLine() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Range:", true)) range = h.substringAfter("bytes=").substringBefore("-").trim().toLongOrNull() ?: 0L
            }
            val query = target.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val out = s.getOutputStream()
            fun head(status: String, type: String, length: Long, extra: String = "") =
                out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: $length\r\n${extra}Connection: close\r\n\r\n".toByteArray())
            if (query["code"] != code) return head("403 Forbidden", "text/plain", 0)
            lastUse = System.currentTimeMillis()
            when (target.substringBefore('?')) {
                "/list" -> {
                    val items = items()
                    allowed = items.flatMap { i -> i.files.map { it.first } }.toSet()
                    val settings = Settings(context)
                    val keys = JSONObject().apply { KEYS.forEach { k -> settings.prefs.getString(k, null)?.takeIf { it.isNotBlank() }?.let { put(k, it) } } }
                    val body = JSONObject().put("device", Build.MODEL).put("keys", keys).put("items", JSONArray(items.map { i ->
                        JSONObject().put("path", i.path).put("category", i.category).put("title", i.title).put("extra", i.extra)
                            .put("files", JSONArray(i.files.map { JSONArray().put(it.first).put(it.second) }))
                    })).toString().toByteArray()
                    head("200 OK", "application/json", body.size.toLong())
                    out.write(body)
                }
                "/file" -> {
                    val path = query["path"].orEmpty()
                    val f = File(base, path)
                    if (path !in allowed || !f.isFile) return head("404 Not Found", "text/plain", 0)
                    val from = range.coerceIn(0, f.length())
                    head(if (from > 0) "206 Partial Content" else "200 OK", "application/octet-stream", f.length() - from,
                        if (from > 0) "Content-Range: bytes $from-${f.length() - 1}/${f.length()}\r\n" else "")
                    f.inputStream().use { it.skip(from); it.copyTo(out, 1 shl 16) }
                    lastUse = System.currentTimeMillis()
                }
                else -> head("404 Not Found", "text/plain", 0)
            }
            out.flush()
        }

        override fun close() = socket.close()
    }

    // ------------------------------------------------------------------ receiving

    /** Copies from [address] ("192.168.4.57:41234") the [categories] it has and this device doesn't. Answers a summary. */
    fun receive(address: String, code: String, categories: Set<String>, progress: (String, Float) -> Unit): String {
        val url = "http://$address"
        val c = "code=" + URLEncoder.encode(code, "UTF-8")
        val list = JSONObject(open("$url/list?$c").let { conn ->
            if (conn.responseCode == 403) throw IOException(tr("Wrong code."))
            String(conn.inputStream.use { it.readBytes() })
        })
        val wanted = list.getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { it.getString("category") in categories && !File(base, it.getString("path")).exists() }
        val total = wanted.sumOf { i -> i.getJSONArray("files").let { f -> (0 until f.length()).sumOf { f.getJSONArray(it).getLong(1) } } }
            .coerceAtLeast(1)
        // Phones fill up: never start what doesn't fit (with 0.5 GB to spare).
        val free = base.usableSpace - (1L shl 29)
        if (total > free) throw IOException(tr("Not enough space here: %1\$.1f GB needed, %2\$.1f GB free.", total / 1e9, free.coerceAtLeast(0) / 1e9))
        var done = 0L
        val counts = HashMap<String, Int>()
        val stage = File(base, ".transfer")
        for (item in wanted) {
            val title = item.getString("title")
            val files = item.getJSONArray("files")
            for (k in 0 until files.length()) {
                val (rel, size) = files.getJSONArray(k).let { it.getString(0) to it.getLong(1) }
                val out = File(stage, rel)
                if (out.length() != size) fetch("$url/file?$c&path=" + URLEncoder.encode(rel, "UTF-8"), out) { got ->
                    progress(title, (done + got).toFloat() / total)
                }
                done += size
            }
            val dest = File(base, item.getString("path"))
            dest.parentFile!!.mkdirs()
            if (!File(stage, item.getString("path")).renameTo(dest)) throw IOException("couldn't move ${dest.name} into place")
            item.optJSONObject("extra")?.let { extra ->
                when (item.getString("path").substringBefore('/')) {
                    "local" -> {
                        LocalEpisodes(context).add(LocalEpisodes.fromJson(extra))
                        Sync(context).markOnPc(extra.getString("id")) // the sender's copy is its own: not uploaded again from here
                    }
                    "yomitan" -> YomitanDictionaries.get(context).addCopied(YomitanDictionaries.fromJson(extra))
                }
            }
            counts.merge(item.getString("category"), 1, Int::plus)
        }
        stage.deleteRecursively()
        var keys = 0
        if ("keys" in categories) {
            // Only where this device has none: a key typed here is never replaced.
            val settings = Settings(context)
            val given = list.getJSONObject("keys")
            for (k in KEYS) if (given.has(k) && settings.prefs.getString(k, null).isNullOrBlank()) {
                settings.prefs.edit().putString(k, given.getString(k)).apply()
                keys++
            }
        }
        return listOfNotNull(
            counts["episodes"]?.let { tr("%d episodes", it) }, counts["dictionaries"]?.let { tr("%d dictionaries", it) },
            counts["models"]?.let { tr("%d models", it) }, keys.takeIf { it > 0 }?.let { tr("%d keys", it) },
        ).joinToString(", ").ifEmpty { tr("nothing new") }
    }

    /** [url] into [out], continuing its .part (Range). */
    private fun fetch(url: String, out: File, progress: (Long) -> Unit) {
        out.parentFile!!.mkdirs()
        val part = File(out.path + ".part")
        val conn = open(url)
        if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
        val append = conn.responseCode == 206
        if (conn.responseCode != 200 && !append) throw IOException("HTTP ${conn.responseCode}: ${out.name}")
        var got = if (append) part.length() else 0L
        var shown = 0L
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, append).use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    got += n
                    if (got - shown > (4 shl 20)) { shown = got; progress(got) }
                }
            }
        }
        out.delete()
        if (!part.renameTo(out)) throw IOException("couldn't move ${out.name} into place")
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 60_000
        // Opened explicitly: a 403 / Range answer is read before the body.
        connect()
    }

    companion object {
        const val SERVICE = "_kumapie._tcp."
        val CATEGORIES = listOf("episodes", "dictionaries", "models", "keys")
        /** Settings copied with "keys": the PC's address and the services' keys (only where the receiver has none). */
        private val KEYS = listOf("server", "soniox_key", "rd_token", "jimaku_key")

        /** This device's Wi-Fi address (for typing it on the other device when discovery finds nothing). */
        fun address(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .sortedByDescending { it.name.startsWith("wlan") }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        fun send(context: Context, code: String) = WorkManager.getInstance(context).enqueueUniqueWork(SEND, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SendWorker>().setInputData(workDataOf("code" to code)).build())

        fun receive(context: Context, address: String, code: String, categories: Set<String>) =
            WorkManager.getInstance(context).enqueueUniqueWork(RECEIVE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ReceiveWorker>()
                .setInputData(workDataOf("address" to address, "code" to code, "categories" to categories.toTypedArray())).build())

        const val SEND = "transfer-send"
        const val RECEIVE = "transfer-receive"

        internal fun notification(context: Context, id: Int, title: String, text: String, progress: Float): ForegroundInfo {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel("transfer") == null) {
                manager.createNotificationChannel(android.app.NotificationChannel("transfer", tr("Copy between devices"), android.app.NotificationManager.IMPORTANCE_LOW))
            }
            val n = androidx.core.app.NotificationCompat.Builder(context, "transfer")
                .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(title).setContentText(text)
                .setProgress(100, (progress * 100).toInt().coerceAtLeast(0), progress < 0f).setOngoing(true).setSilent(true).build()
            return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(id, n)
        }
    }
}

/** Sends while it runs: the server, announced on the Wi-Fi; stops after 15 minutes without a request (or Stop). */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val code = inputData.getString("code") ?: return Result.failure()
        val nsd = applicationContext.getSystemService(NsdManager::class.java)
        Transfer(applicationContext).Server(code).use { server ->
            server.start()
            val info = NsdServiceInfo().apply { serviceName = "kumapie ${Build.MODEL}"; serviceType = Transfer.SERVICE; port = server.port }
            val registration = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w("kumapie", "transfer: nsd $e") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            setProgress(workDataOf("address" to "${Transfer.address()}:${server.port}", "code" to code))
            runCatching { setForeground(Transfer.notification(applicationContext, 7311, tr("Sending to another device"), tr("Code %s", code), -1f)) }
            try {
                while (System.currentTimeMillis() - server.lastUse < 15 * 60_000L) delay(2000)
            } finally {
                runCatching { nsd.unregisterService(registration) }
            }
        }
        return Result.success()
    }
}

/** Copies from the sending device, in the foreground (episodes are big). */
class ReceiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val address = inputData.getString("address") ?: return Result.failure()
        val code = inputData.getString("code").orEmpty()
        val categories = inputData.getStringArray("categories")?.toSet().orEmpty()
        runCatching { setForeground(Transfer.notification(applicationContext, 7312, tr("Copying from another device"), "", -1f)) }
        var shown = 0L
        return runCatching {
            Transfer(applicationContext).receive(address, code, categories) { what, f ->
                val now = System.currentTimeMillis()
                if (now - shown >= 1000) {
                    shown = now
                    setProgressAsync(workDataOf("what" to what, "progress" to f))
                    runCatching { setForegroundAsync(Transfer.notification(applicationContext, 7312, tr("Copying from another device"), what, f)) }
                }
            }
        }.fold({ Result.success(workDataOf("done" to it)) }, {
            Log.w("kumapie", "transfer: $it")
            Result.failure(workDataOf("error" to (it.message ?: it.toString())))
        })
    }
}
