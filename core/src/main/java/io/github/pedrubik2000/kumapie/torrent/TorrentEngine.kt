package io.github.pedrubik2000.kumapie.torrent

import android.content.Context
import android.os.StatFs
import androidx.media3.common.Player
import io.github.pedrubik2000.kumapie.data.Pending
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.player.AppScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.util.Locale
import java.util.UUID

object TorrentEngine {
    const val UPLOAD_BYTES_PER_S = 50_000
    const val MAX_CONNECTIONS = 50
    const val NARROW_CONNECTIONS = 30
    const val NO_PEERS_MS = 60_000L
    const val STALL_MS = 60_000L
    const val RETRY_AFTER_MS = 15_000L
    private const val LOG = "kumapie-torrent"
    private const val POLL_MS = 250L
    private const val STATUS_MS = 500L
    private const val LOG_EVERY = 4
    private const val BINARY = "libtorrsrv.so"

    private val shown = MutableStateFlow<String?>(null)
    val line: StateFlow<String?> = shown
    private val state = MutableStateFlow<TorrentStatus?>(null)
    val status: StateFlow<TorrentStatus?> = state
    @Volatile private var releaseName = ""

    @Volatile var failed = false
        private set
    @Volatile var waiting = false
    @Volatile private var failedAt = 0L
    @Volatile private var generation = 0
    @Volatile private var teardown: Job? = null
    private val lock = Any()
    private val owners = java.util.WeakHashMap<Player, Any>()
    private var owner: Any? = null
    private var sidecar: Sidecar? = null
    private var current: TorrentStream? = null
    private val firstUse = FirstUse()

    fun tie(player: Player, token: Any) {
        synchronized(owners) { owners[player] = token }
    }

    fun release(player: Player) {
        val token = synchronized(owners) { owners[player] }
        if (token != null && token === owner) release()
    }

    fun sweep(context: Context) {
        val dir = File(context.applicationContext.cacheDir, "torrent")
        synchronized(lock) {
            if (generation != 0 || teardown?.isActive == true) return
            teardown = Pending.track(AppScope.launch { dir.deleteRecursively() })
        }
    }

    fun open(context: Context, ref: TorrentRef, token: Any): TorrentStream {
        current?.takeIf { it.ref.hash == ref.hash && it.sidecar.alive && owner === token }?.let { return it }
        if (failed && owner === token && !TorrentPlan.retryable(failedAt, System.currentTimeMillis(), RETRY_AFTER_MS)) throw TorrentException(shown.value.orEmpty())
        release()
        val gen = synchronized(lock) { owner = token; ++generation }
        failed = false
        waiting = true
        shown.value = tr("Finding peers…")
        releaseName = ref.name
        state.value = TorrentStatus(release = ref.name)
        try {
            if (!Settings(context).torrents) throw TorrentException(tr("Torrent streaming is off: turn it on in Settings."), TorrentFault.OFF)
            return begin(context.applicationContext, ref, gen)
        } catch (e: InterruptedException) {
            throw InterruptedIOException()
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: TorrentException) {
            if (owner === token) fail(e.message.orEmpty(), e.fault)
            throw e
        } catch (e: Throwable) {
            val said = tr("The torrent engine did not start.")
            if (owner === token) fail(said)
            throw TorrentException(said).also { it.initCause(e) }
        }
    }

    private fun begin(context: Context, ref: TorrentRef, gen: Int): TorrentStream {
        while (synchronized(lock) { teardown }?.isActive == true) {
            alive(gen)
            Thread.sleep(POLL_MS)
        }
        if (firstUse.take()) firstUse.wipe(File(context.cacheDir, "torrent"))
        val dir = File(context.cacheDir, "torrent/$gen").apply { deleteRecursively(); mkdirs() }
        val car = spawn(context, ref, dir)
        synchronized(lock) {
            if (gen != generation) { AppScope.launch { car.stop() }; throw InterruptedException() }
            sidecar = car
        }
        try {
            return stream(car, ref, gen)
        } catch (e: Throwable) {
            synchronized(lock) { if (gen == generation) { generation++; sidecar = null } }
            AppScope.launch { car.stop() }
            throw e
        }
    }

    private fun spawn(context: Context, ref: TorrentRef, dir: File): Sidecar {
        val cap = TorrentPlan.cap(!narrow, StatFs(context.cacheDir.path).availableBytes)
        val token = UUID.randomUUID().toString()
        val bin = File(context.applicationInfo.nativeLibraryDir, BINARY).path
        val process = ProcessBuilder(bin, "-dir", dir.path, "-cap", cap.toString(), "-hash", ref.hash, "-token", token,
            "-upload", UPLOAD_BYTES_PER_S.toString(), "-conns", (if (narrow) NARROW_CONNECTIONS else MAX_CONNECTIONS).toString(), "-tr", ref.trackers.joinToString(","))
            .redirectError(File(context.cacheDir, "torrsrv.log")).start()
        val first = process.inputStream.bufferedReader().readLine().orEmpty()
        val port = first.removePrefix("PORT ").trim().toIntOrNull()
        if (port == null) {
            process.destroy()
            throw TorrentException(tr("The torrent engine did not start."))
        }
        android.util.Log.i(LOG, "sidecar on $port, window ${cap shr 20} MB")
        return Sidecar(process, port, token, dir)
    }

    private fun stream(car: Sidecar, ref: TorrentRef, gen: Int): TorrentStream {
        val started = System.currentTimeMillis()
        watch(gen, ref, car)
        val deadline = started + NO_PEERS_MS
        var s = car.status()
        while (!s.optBoolean("hasInfo")) {
            alive(gen)
            if (!car.alive) throw TorrentException(tr("The torrent engine did not start."))
            if (System.currentTimeMillis() > deadline) {
                val none = s.optInt("peers") == 0
                throw TorrentException(if (none) tr("No peers found for this video. Try another source.") else tr("Found peers but they sent nothing. Try another source."),
                    if (none) TorrentFault.NO_PEERS else TorrentFault.STALLED)
            }
            Thread.sleep(POLL_MS)
            s = car.status()
        }
        val files = s.optJSONArray("files")
        val count = files?.length() ?: 0
        val names = (0 until count).map { files?.optJSONObject(it)?.optString("name").orEmpty() }
        val sizes = (0 until count).map { files?.optJSONObject(it)?.optLong("size") ?: 0L }
        val index = TorrentPlan.pick(names, sizes, ref.index)
        if (index < 0) throw TorrentException(tr("This torrent has no video file."))
        android.util.Log.i(LOG, "metadata after ${System.currentTimeMillis() - started} ms, ${s.optInt("peers")} peers, $count files, file $index ${sizes[index]} bytes")
        val stream = TorrentStream(ref, car, index, sizes[index])
        synchronized(lock) {
            if (gen != generation) throw InterruptedIOException()
            current = stream
        }
        return stream
    }

    private val narrow: Boolean get() = android.os.Build.SUPPORTED_64_BIT_ABIS.isEmpty()

    private fun alive(gen: Int) {
        if (gen != generation || Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun watch(gen: Int, ref: TorrentRef, car: Sidecar) {
        var ticks = 0
        Thread {
            while (gen == generation) {
                runCatching {
                    val s = car.status()
                    val next = progress(s)
                    tick(next, when {
                        !waiting -> null
                        next.phase == TorrentPhase.PEERS -> tr("Finding peers…") + if (next.peers > 0) " · " + tr("%d peers", next.peers) else ""
                        else -> tr("Downloading %1\$d%% · %2\$s/s · %3\$d peers", next.percent, megabytes(next.rate), next.peers)
                    })
                    if (++ticks % LOG_EVERY == 0) android.util.Log.i(LOG, "down ${next.rate / 1000} kB/s peers ${next.peers} seeds ${s.optInt("seeds")} ahead ${next.ahead shr 10} kB cache ${s.optLong("cacheSize") shr 20} MB")
                }
                try { Thread.sleep(STATUS_MS) } catch (e: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "torrent-status-${ref.hash.take(6)}" }.start()
    }

    private fun progress(s: JSONObject): TorrentStatus {
        val peers = s.optInt("peers")
        val stream = current
        if (!s.optBoolean("hasInfo") || stream == null) return TorrentStatus(TorrentPhase.PEERS, peers = peers, release = releaseName)
        val need = TorrentStatus.startNeed(stream.size)
        val ahead = s.optLong("ahead")
        return TorrentStatus(TorrentPhase.DOWNLOADING, peers = peers, rate = s.optLong("rate"), have = minOf(ahead, need), need = need, ahead = ahead, size = stream.size, release = releaseName)
    }

    fun tick(next: TorrentStatus, text: String?) {
        state.update { if (failed || it?.phase == TorrentPhase.FAILED) it else next }
        shown.update { if (failed) it else text }
    }

    fun playerFailed(message: String) {
        if (!failed) fail(message)
    }

    fun fail(message: String, fault: TorrentFault = TorrentFault.OTHER) {
        failedAt = System.currentTimeMillis()
        failed = true
        state.value = TorrentStatus.failed(fault, message, state.value?.peers ?: 0, releaseName)
        shown.value = message
    }

    fun recovered() {
        if (!failed) return
        failed = false
        shown.value = null
        state.value = null
    }

    fun local(source: String): String {
        if (!source.startsWith("${TorrentRef.SCHEME}://")) return source
        return current?.takeIf { source.contains(it.ref.hash) }?.url ?: throw TorrentException(tr("The video is no longer on this device: mine words while it plays."))
    }

    fun megabytes(bytes: Long): String =
        if (bytes >= 1L shl 30) String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0) else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)

    fun release() {
        synchronized(lock) {
            generation++
            val car = sidecar
            current = null
            sidecar = null
            if (car != null) teardown = Pending.track(AppScope.launch { car.stop() })
        }
        waiting = false
        failed = false
        shown.value = null
        state.value = null
    }
}
