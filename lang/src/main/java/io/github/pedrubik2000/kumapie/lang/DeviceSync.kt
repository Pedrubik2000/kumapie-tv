package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object DeviceSync {
    const val PORT = 47823
    const val SERVICE = "_kumapiesync._tcp."
    const val ANSWERS = "sync_answers"
    const val BACKGROUND = "sync_background"
    private const val WITH = "sync_with"
    private const val PEERS = "sync_peers"
    private const val LAST = "sync_last"
    private const val WORK = "device-sync"

    class Peer(val name: String, val address: String, val secret: String)
    class Asking(val device: String, val secret: String) { val code: String get() = code(secret) }
    class Answer(val status: Int, val body: ByteArray, val sig: String? = null)

    val asking = MutableStateFlow<List<Asking>>(emptyList())
    val found = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val last = MutableStateFlow("")
    private val lock = Mutex()

    fun code(secret: String): String = BigInteger(1, mac(secret, "pair".toByteArray())).mod(BigInteger.valueOf(10_000)).toString().padStart(4, '0')

    fun sign(secret: String, body: ByteArray): String = mac(secret, body).joinToString("") { "%02x".format(it) }

    fun verify(secret: String, body: ByteArray, sig: String?): Boolean = sig != null && MessageDigest.isEqual(sign(secret, body).toByteArray(), sig.toByteArray())

    private fun mac(secret: String, data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }.doFinal(data)

    fun refusal(server: String, secret: String?, body: ByteArray, sig: String?): Answer? = when {
        server.isNotEmpty() -> Answer(409, tr("This device syncs with its PC.").toByteArray())
        secret == null -> Answer(403, tr("Not paired: allow this device on the other one first.").toByteArray())
        !verify(secret, body, sig) -> Answer(403, tr("Wrong signature: pair the devices again.").toByteArray())
        else -> null
    }

    fun moved(plan: DeviceFile.Plan): String = plan.counts.filter { it.add + it.change > 0 }
        .joinToString(", ") { "${it.what} +${it.add}" + if (it.change > 0) " ~${it.change}" else "" }.ifEmpty { tr("nothing new") }

    fun with(settings: Settings): Peer? = settings.prefs.getString(WITH, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.let { Peer(it.getString("name"), it.getString("address"), it.getString("secret")) }

    fun forget(settings: Settings) = settings.prefs.edit().remove(WITH).apply()

    private fun peers(settings: Settings) = runCatching { JSONObject(settings.prefs.getString(PEERS, "{}")!!) }.getOrDefault(JSONObject())

    private fun files(context: Context) = DeviceFiles(context, LangKit.get(context, Settings(context)).languages.all)

    private fun now() = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun say(settings: Settings, line: String) {
        last.value = line
        settings.prefs.edit().putString(LAST, line).apply()
    }

    fun start(context: Context) {
        val settings = Settings(context)
        last.value = settings.prefs.getString(LAST, "").orEmpty()
        if (settings.server.isNotEmpty()) return
        if (settings.prefs.getBoolean(ANSWERS, false)) SyncListener.start(context)
        if (settings.prefs.getBoolean(BACKGROUND, false)) SyncService.start(context)
        if (with(settings) != null) WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeviceSyncWorker>(1, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())
        soon(context)
    }

    fun soon(context: Context) {
        if (Settings(context).server.isNotEmpty()) return
        WorkManager.getInstance(context).enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DeviceSyncWorker>().build())
    }

    suspend fun pair(context: Context, address: String): String = withContext(Dispatchers.IO) {
        val settings = Settings(context)
        if (settings.server.isNotEmpty()) return@withContext tr("This device syncs with its PC.")
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val me = Progress.of(context).device
        val a = post("http://$address/pair", JSONObject().put("device", me).put("secret", secret).toString().toByteArray(), emptyMap())
        if (a.status !in 200..202) throw IOException(String(a.body))
        val name = JSONObject(String(a.body)).getString("device")
        settings.prefs.edit().putString(WITH, JSONObject().put("name", name).put("address", address).put("secret", secret).toString()).apply()
        start(context)
        tr("On %1\$s, allow %2\$s (code %3\$s). Then it syncs.", name, me, code(secret))
    }

    suspend fun syncNow(context: Context): String = withContext(Dispatchers.IO) {
        val settings = Settings(context)
        if (settings.server.isNotEmpty()) return@withContext ""
        val peer = with(settings) ?: return@withContext lock.withLock { runCatching { files(context).share() }; "" }
        val line = runCatching {
            lock.withLock {
                runCatching { exchange(context, peer) }.recoverCatching { e ->
                    if (e !is IOException) throw e
                    val moved = discover(context, 6_000).firstOrNull { it.first == peer.name && it.second != peer.address } ?: throw e
                    val p = Peer(peer.name, moved.second, peer.secret)
                    settings.prefs.edit().putString(WITH, JSONObject().put("name", p.name).put("address", p.address).put("secret", p.secret).toString()).apply()
                    exchange(context, p)
                }.getOrThrow()
            }
        }.getOrElse { tr("%1\$s: not synced with %2\$s: %3\$s", now(), peer.name, it.message ?: it.toString()) }
        say(settings, line)
        line
    }

    private fun exchange(context: Context, peer: Peer): String {
        val files = files(context)
        val body = files.payload().toString().toByteArray()
        val a = post("http://${peer.address}/sync", body, mapOf("X-Device" to Progress.of(context).device, "X-Sig" to sign(peer.secret, body)))
        if (a.status != 200) error(String(a.body))
        check(verify(peer.secret, a.body, a.sig)) { tr("Wrong signature: pair the devices again.") }
        val o = JSONObject(String(a.body))
        val plan = files.plan(o)
        files.apply(plan, o, files.isNew(plan.from))
        files.takeFsrs(o.optJSONObject("fsrs"))
        files.share()
        return tr("%1\$s with %2\$s · took: %3\$s · gave: %4\$s", now(), peer.name, moved(plan), o.optString("moved"))
    }

    internal fun answer(context: Context, path: String, device: String?, sig: String?, body: ByteArray): Answer {
        val settings = Settings(context)
        if (settings.server.isNotEmpty()) return Answer(409, tr("This device syncs with its PC.").toByteArray())
        val me = Progress.of(context).device
        return when (path) {
            "/pair" -> {
                val o = JSONObject(String(body))
                val who = o.getString("device")
                val secret = o.getString("secret")
                val reply = JSONObject().put("device", me).toString().toByteArray()
                if (peers(settings).optString(who) == secret) Answer(200, reply)
                else { asking.value = asking.value.filter { it.device != who } + Asking(who, secret); Answer(202, reply) }
            }
            "/sync" -> {
                val secret = device?.let { peers(settings).optString(it).ifEmpty { null } }
                refusal(settings.server, secret, body, sig) ?: runBlocking {
                    lock.withLock {
                        runCatching {
                            val files = files(context)
                            val o = JSONObject(String(body))
                            val plan = files.plan(o)
                            files.apply(plan, o, files.isNew(plan.from))
                            val moved = moved(plan)
                            say(settings, tr("%1\$s %2\$s synced · took: %3\$s", now(), plan.from, moved))
                            val out = files.payload().apply { remove("cards"); put("moved", moved) }.toString().toByteArray()
                            Answer(200, out, sign(secret!!, out))
                        }.getOrElse { Answer(422, (it.message ?: it.toString()).toByteArray()) }
                    }
                }
            }
            else -> Answer(404, ByteArray(0))
        }
    }

    fun allow(context: Context, a: Asking) {
        val settings = Settings(context)
        settings.prefs.edit().putString(PEERS, peers(settings).put(a.device, a.secret).toString()).apply()
        asking.value = asking.value.filter { it !== a }
    }

    private fun post(url: String, body: ByteArray, headers: Map<String, String>): Answer = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 5_000
        readTimeout = 120_000
        requestMethod = "POST"
        doOutput = true
        setFixedLengthStreamingMode(body.size)
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
        outputStream.use { it.write(body) }
        val code = responseCode
        Answer(code, (if (code < 400) inputStream else errorStream)?.use { it.readBytes() } ?: ByteArray(0), getHeaderField("X-Sig"))
    }

    suspend fun discover(context: Context, ms: Long): List<Pair<String, String>> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val out = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val me = Progress.of(context).device
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == me) return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        @Suppress("DEPRECATION") val host = i.host?.hostAddress ?: return
                        out.removeAll { it.first == i.serviceName }
                        out += i.serviceName to "$host:${i.port}"
                        found.value = out.toList()
                    }
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) { Log.w("kumapie", "sync: resolve ${i.serviceName}: $e") }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, e: Int) { Log.w("kumapie", "sync: discovery $e") }
            override fun onStopDiscoveryFailed(type: String, e: Int) {}
        }
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return emptyList() }
        try { delay(ms) } finally { runCatching { nsd.stopServiceDiscovery(listener) } }
        return out.toList()
    }
}

class DeviceSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val line = DeviceSync.syncNow(applicationContext)
        if (line.isNotEmpty()) Log.i("kumapie", "device sync: $line")
        return Result.success()
    }
}
