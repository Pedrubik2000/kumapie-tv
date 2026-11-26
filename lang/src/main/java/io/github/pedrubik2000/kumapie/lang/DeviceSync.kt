package io.github.pedrubik2000.kumapie.lang

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
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
import io.github.pedrubik2000.kumapie.lang.DeviceFile.objects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    const val PC_SERVICE = "_kumapiepc._tcp."
    const val ANSWERS = "sync_answers"
    const val BACKGROUND = "sync_background"
    private const val WITH = "sync_with"
    private const val PEERS = "sync_peers"
    private const val LAST = "sync_last"
    private const val OK = "sync_ok"
    internal const val EVERY_MS = 15 * 60_000L
    private const val BACKSTOP_S = 15L
    private const val WORK = "device-sync"
    private const val CONFLICT_MS = 10 * 60_000L
    internal const val STALE_MS = 20_000L
    private const val DEAD_MS = 120_000L
    private const val TWO = "Two devices asked with the same name: pair again."

    class Peer(val name: String, val address: String, val secret: String)
    class Asking(val device: String, val secret: String, val pub: String = "") {
        @Volatile var seen = System.currentTimeMillis()
        val code: String get() = code(secret, pub)
    }
    class Asked(val code: String, val old: Boolean = false)
    class Answer(val status: Int, val body: ByteArray, val sig: String? = null)
    class Conflict(line: String) : IOException(line)

    val asking = MutableStateFlow<List<Asking>>(emptyList())
    val conflicts = MutableStateFlow<Set<String>>(emptySet())
    val found = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val last = MutableStateFlow("")
    private val lock = Mutex()

    fun code(secret: String, pub: String = ""): String = BigInteger(1, mac(secret, "pair".toByteArray() + pub.toByteArray())).mod(BigInteger.valueOf(10_000)).toString().padStart(4, '0')

    fun live(list: List<Asking>, now: Long = System.currentTimeMillis()): List<Asking> = list.filter { now - it.seen <= STALE_MS }

    fun withPort(address: String): String = address.trim().let { if (it.isEmpty() || ':' in it) it else "$it:$PORT" }

    fun sign(secret: String, body: ByteArray): String = mac(secret, body).joinToString("") { "%02x".format(it) }

    fun verify(secret: String, body: ByteArray, sig: String?): Boolean = sig != null && MessageDigest.isEqual(sign(secret, body).toByteArray(), sig.toByteArray())

    private fun mac(secret: String, data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }.doFinal(data)

    fun refusal(server: String, secret: String?, body: ByteArray, sig: String?): Answer? = when {
        server.isNotEmpty() -> Answer(409, tr("This device syncs with its PC.").toByteArray())
        secret == null -> Answer(403, tr("Not paired: allow this device on the other one first.").toByteArray())
        !verify(secret, body, sig) -> Answer(403, tr("Wrong signature: pair the devices again.").toByteArray())
        else -> null
    }

    fun moved(counts: List<DeviceFile.Count>): String = counts.filter { it.add + it.change > 0 }
        .joinToString(", ") { "${tr(it.what)} +${it.add}" + if (it.change > 0) " ~${it.change}" else "" }.ifEmpty { tr("nothing new") }

    fun counts(plan: DeviceFile.Plan) = JSONArray(plan.counts.map { JSONObject().put("what", it.what).put("add", it.add).put("change", it.change).put("keep", it.keep) })

    fun gave(answer: JSONObject): String = answer.optJSONArray("moved")?.let { a ->
        moved(a.objects().map { DeviceFile.Count(it.getString("what"), it.getInt("add"), it.getInt("change"), it.optInt("keep")) })
    } ?: answer.optString("moved")

    fun with(settings: Settings): Peer? = settings.prefs.getString(WITH, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.let { Peer(it.getString("name"), it.getString("address"), it.getString("secret")) }

    fun forget(settings: Settings) = settings.prefs.edit().remove(WITH).apply()

    fun unpair(context: Context) {
        forget(Settings(context))
        WorkManager.getInstance(context).run { cancelUniqueWork(WORK); cancelUniqueWork("$WORK-now") }
    }

    private fun peers(prefs: SharedPreferences) = runCatching { JSONObject(prefs.getString(PEERS, "{}").orEmpty()) }.getOrDefault(JSONObject())

    private fun files(context: Context) = DeviceFiles(context, LangKit.get(context, Settings(context)).languages.all)

    private fun now() = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun say(settings: Settings, line: String) {
        last.value = line
        settings.prefs.edit().putString(LAST, line).apply()
    }

    fun start(context: Context) { listen(context); schedule(context) }

    fun listen(context: Context) {
        val settings = Settings(context)
        last.value = settings.prefs.getString(LAST, "").orEmpty()
        if (settings.server.isNotEmpty()) return
        if (!settings.prefs.getBoolean(ANSWERS, false)) return
        SyncListener.start(context)
        if (settings.prefs.getBoolean(BACKGROUND, false)) SyncService.start(context)
    }

    fun schedule(context: Context) {
        val settings = Settings(context)
        if (settings.server.isNotEmpty()) return
        if (with(settings) != null) WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeviceSyncWorker>(1, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())
        enqueueNow(context)
        watch(context)
    }

    fun soon(context: Context) {
        val settings = Settings(context)
        if (settings.server.isNotEmpty()) return
        if (with(settings) != null) requested(context) else enqueueNow(context)
    }

    fun rated(context: Context) {
        val settings = Settings(context)
        if (settings.server.isEmpty() && with(settings) != null && allowed(unmetered(context), failedAt, System.currentTimeMillis())) requested(context)
    }

    internal fun allowed(unmetered: Boolean, failedAt: Long, now: Long): Boolean = unmetered && now - failedAt > EVERY_MS

    @Volatile private var failedAt = 0L

    internal fun cheapLink(activeMetered: Boolean, links: List<Pair<Boolean, Boolean>>): Boolean = !activeMetered || links.any { (lan, notMetered) -> lan && notMetered }

    private fun unmetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cheapLink(cm.isActiveNetworkMetered, cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.map {
            (it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) to
                it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) })
    }.getOrDefault(true)

    private fun paired(context: Context) {
        val settings = Settings(context)
        if (settings.server.isEmpty() && with(settings) != null) requested(context)
    }

    internal fun due(lastOk: Long, now: Long): Boolean = now - lastOk > EVERY_MS

    private val sync = SyncDebounce(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        wanted?.let { c ->
            val asked = requests.get()
            syncNow(c).let { if (it.isNotEmpty()) Log.i("kumapie", "device sync: $it") }
            if (failedAt == 0L && requests.get() == asked) runCatching { WorkManager.getInstance(c).cancelUniqueWork("$WORK-now") }
        }
    }
    val syncing: Boolean get() = lock.isLocked
    @Volatile private var wanted: Context? = null
    private val requests = java.util.concurrent.atomic.AtomicLong()

    private fun requested(context: Context) {
        wanted = context.applicationContext
        requests.incrementAndGet()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DeviceSyncWorker>().setInitialDelay(BACKSTOP_S, TimeUnit.SECONDS).build()) }
        sync.request()
    }

    private val ticks = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = 0
    private var ticker: Job? = null
    private var watching = false

    @Synchronized private fun watch(context: Context) {
        if (watching) return
        val app = context.applicationContext as? Application ?: return
        watching = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { if (synchronized(this@DeviceSync) { ++started == 1 }) foreground(app) }
            override fun onActivityStopped(a: Activity) { if (synchronized(this@DeviceSync) { started = maxOf(0, started - 1); started == 0 }) { ticker?.cancel(); ticker = null } }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        val info = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        if (info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) { if (started == 0) { started = 1; foreground(app) } }
    }

    @Synchronized private fun foreground(context: Context) {
        val settings = Settings(context)
        if (settings.server.isNotEmpty()) return
        if (due(settings.prefs.getLong(OK, 0L), System.currentTimeMillis()) && unmetered(context)) paired(context)
        ticker?.cancel()
        ticker = ticks.launch { while (true) { delay(EVERY_MS); if (unmetered(context)) paired(context) } }
    }

    private fun enqueueNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DeviceSyncWorker>().build())
    }

    suspend fun pair(context: Context, address: String): String {
        if (Settings(context).server.isNotEmpty()) return tr("This device syncs with its PC.")
        val code = ask(context, "", address).code
        start(context)
        val name = with(Settings(context))?.name.orEmpty()
        return tr("On %1\$s, allow %2\$s (code %3\$s). Then it syncs.", name, Progress.of(context).device, code)
    }

    suspend fun ask(context: Context, name: String, typed: String): Asked = withContext(Dispatchers.IO) {
        val address = withPort(typed)
        val settings = Settings(context)
        check(settings.server.isEmpty()) { tr("This device syncs with its PC.") }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val pub = SetupBundle.begin()
        val a = post("http://$address/pair", JSONObject().put("device", Progress.of(context).device).put("secret", secret).put("pub", pub).toString().toByteArray(), emptyMap())
        if (a.status == 409 && String(a.body) == TWO) throw Conflict(conflictLine(Progress.of(context).device))
        if (a.status !in 200..202) throw IOException(String(a.body))
        val reply = JSONObject(String(a.body))
        val who = name.ifEmpty { reply.getString("device") }
        settings.prefs.edit().putString(WITH, JSONObject().put("name", who).put("address", address).put("secret", secret).toString()).apply()
        Asked(shown(secret, pub, reply), !reply.optBoolean("setup"))
    }

    internal fun shown(secret: String, pub: String, reply: JSONObject): String = if (reply.optBoolean("setup")) code(secret, pub) else code(secret)

    enum class Poll { WAITING, ALLOWED, CONFLICT, GONE, UNREACHABLE }

    internal fun polled(a: Answer): Poll = when {
        a.status == 200 -> Poll.ALLOWED
        a.status == 409 && String(a.body) == TWO -> Poll.CONFLICT
        else -> Poll.WAITING
    }

    fun conflictLine(name: String): String = tr("Two devices asked as %1\$s. Pair again.", name)

    suspend fun allowed(context: Context): Poll = withContext(Dispatchers.IO) {
        val settings = Settings(context)
        val peer = with(settings) ?: return@withContext Poll.GONE
        val pub = SetupBundle.pub() ?: return@withContext Poll.GONE
        val body = JSONObject().put("device", Progress.of(context).device).put("secret", peer.secret).put("pub", pub).toString().toByteArray()
        val poll = runCatching { polled(post("http://${peer.address}/pair", body, emptyMap())) }.getOrDefault(Poll.UNREACHABLE)
        if (poll == Poll.CONFLICT) { forget(settings); SetupBundle.clearKey() }
        poll
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
                }.onFailure { runCatching { files(context).share() } }.getOrThrow()
            }
        }.onSuccess { failedAt = 0L; settings.prefs.edit().putLong(OK, System.currentTimeMillis()).apply() }
            .getOrElse { failedAt = System.currentTimeMillis(); tr("%1\$s: not synced with %2\$s: %3\$s", now(), peer.name, it.message ?: it.toString()) }
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
        return tr("%1\$s with %2\$s · took: %3\$s · gave: %4\$s", now(), peer.name, moved(plan.counts), gave(o))
    }

    internal fun answer(context: Context, path: String, device: String?, sig: String?, body: ByteArray): Answer {
        val settings = Settings(context)
        if (settings.server.isNotEmpty()) return Answer(409, tr("This device syncs with its PC.").toByteArray())
        val secret = device?.let { peers(settings.prefs).optString(it).ifEmpty { null } }
        return when (path) {
            "/pair" -> {
                val o = JSONObject(String(body))
                requested(settings.prefs, o.getString("device"), o.getString("secret"), o.optString("pub"), Progress.of(context).device)
            }
            "/setup" -> SetupBundle.serve(settings.server, secret, device, settings.prefs, body, sig, System.currentTimeMillis())
            "/sync" -> {
                refusal(settings.server, secret, body, sig) ?: runBlocking {
                    lock.withLock {
                        runCatching {
                            val files = files(context)
                            val o = JSONObject(String(body))
                            val plan = files.plan(o)
                            files.apply(plan, o, files.isNew(plan.from))
                            say(settings, tr("%1\$s %2\$s synced · took: %3\$s", now(), plan.from, moved(plan.counts)))
                            val out = files.payload().apply { put("moved", counts(plan)) }.toString().toByteArray()
                            Answer(200, out, sign(requireNotNull(secret), out))
                        }.getOrElse { Answer(422, (it.message ?: it.toString()).toByteArray()) }
                    }
                }
            }
            else -> Answer(404, ByteArray(0))
        }
    }

    private val conflictAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun dismissConflict(name: String) {
        conflictAt.remove(name)
        conflicts.value = conflicts.value - name
    }

    internal fun requested(prefs: SharedPreferences, who: String, asked: String, pub: String, me: String, now: Long = System.currentTimeMillis()): Answer {
        val reply = JSONObject().put("device", me).put("setup", true).toString().toByteArray()
        if (peers(prefs).optString(who) == asked) return Answer(200, reply)
        asking.value = asking.value.filter { now - it.seen <= DEAD_MS }
        if (who in conflicts.value && now - (conflictAt[who] ?: 0L) > CONFLICT_MS) dismissConflict(who)
        if (who in conflicts.value) return Answer(409, TWO.toByteArray())
        val old = asking.value.firstOrNull { it.device == who }
        if (old != null && (old.secret != asked || old.pub != pub)) {
            if (now - old.seen <= STALE_MS || code(asked, pub) == old.code) {
                asking.value = asking.value.filter { it !== old }
                conflictAt[who] = now
                conflicts.value = conflicts.value + who
                return Answer(409, TWO.toByteArray())
            }
            asking.value = asking.value.filter { it !== old }
        }
        (asking.value.firstOrNull { it.device == who } ?: Asking(who, asked, pub).also { asking.value = asking.value + it }).seen = now
        return Answer(202, reply)
    }

    fun allow(context: Context, a: Asking) = allow(Settings(context).prefs, a, System.currentTimeMillis())

    internal fun allow(prefs: SharedPreferences, a: Asking, now: Long) {
        SetupBundle.grant(prefs, a.device, a.pub, now)
        prefs.edit().putString(PEERS, peers(prefs).put(a.device, a.secret).toString()).apply()
        asking.value = asking.value.filter { it !== a }
    }

    internal fun post(url: String, body: ByteArray, headers: Map<String, String>): Answer = (URL(url).openConnection() as HttpURLConnection).run {
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

    suspend fun discover(context: Context, ms: Long): List<Pair<String, String>> =
        NsdScan.scan(context, ms, SERVICE, Progress.of(context).device) { l -> found.value = l.map { it.name to it.address } }.map { it.name to it.address }
}

class DeviceSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val line = DeviceSync.syncNow(applicationContext)
        if (line.isNotEmpty()) Log.i("kumapie", "device sync: $line")
        return Result.success()
    }
}
