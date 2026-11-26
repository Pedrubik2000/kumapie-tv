package io.github.pedrubik2000.kumapie.torrent

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StatFs
import androidx.media3.common.Player
import io.github.pedrubik2000.kumapie.data.Pending
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.player.AppScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.io.InterruptedIOException
import java.util.Locale

object TorrentEngine {
    const val UPLOAD_BYTES_PER_S = 50_000
    const val MAX_CONNECTIONS = 50
    const val NARROW_CONNECTIONS = 30
    const val REQUEST_QUEUE = 12
    const val PEER_LIST = 200
    const val SPARE_BYTES = 500L shl 20
    const val NO_PEERS_MS = 60_000L
    const val STALL_MS = 60_000L
    const val RETRY_AFTER_MS = 15_000L
    private const val LOG = "kumapie-torrent"
    private const val POLL_MS = 100L
    private const val STATUS_MS = 500L
    private const val LOG_EVERY = 4
    private const val FAKE_FREE = "debug_free_bytes"

    private val shown = MutableStateFlow<String?>(null)
    val line: StateFlow<String?> = shown

    @Volatile var failed = false
        private set
    @Volatile var waiting = false
    @Volatile private var failedAt = 0L
    @Volatile private var generation = 0
    @Volatile private var teardown: Job? = null
    private val lock = Any()
    private val owners = java.util.WeakHashMap<Player, Any>()
    private var owner: Any? = null
    private var session: SessionManager? = null
    private var current: TorrentStream? = null
    private var root: File? = null

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
        current?.takeIf { it.ref.hash == ref.hash && it.handle.isValid && owner === token }?.let { return it }
        if (failed && owner === token && !TorrentPlan.retryable(failedAt, System.currentTimeMillis(), RETRY_AFTER_MS)) throw TorrentException(shown.value.orEmpty())
        release()
        val gen = synchronized(lock) { owner = token; ++generation }
        failed = false
        waiting = true
        shown.value = tr("Finding peers…")
        try {
            if (!Settings(context).torrents) throw TorrentException(tr("Torrent streaming is off: turn it on in Settings."))
            return begin(context.applicationContext, ref, gen)
        } catch (e: InterruptedException) {
            throw InterruptedIOException()
        } catch (e: TorrentException) {
            if (gen == generation) fail(e.message.orEmpty())
            throw e
        }
    }

    private fun begin(context: Context, ref: TorrentRef, gen: Int): TorrentStream {
        while (synchronized(lock) { teardown }?.isActive == true) {
            alive(gen)
            Thread.sleep(POLL_MS)
        }
        val dir = File(context.cacheDir, "torrent/$gen").apply { deleteRecursively(); mkdirs() }
        val sm = synchronized(lock) {
            if (gen != generation) throw InterruptedException()
            root = dir
            session ?: SessionManager(false).also { s -> s.start(SessionParams(pack()).apply { setPosixDiskIO() }); session = s }
        }
        val hash = Sha1Hash.parseHex(ref.hash)
        try {
            return stream(context, sm, hash, ref, dir, gen)
        } catch (e: Throwable) {
            abandon(sm, hash, gen)
            throw e
        }
    }

    private fun stream(context: Context, sm: SessionManager, hash: Sha1Hash, ref: TorrentRef, dir: File, gen: Int): TorrentStream {
        sm.download(ref.magnet(), dir, TorrentFlags.DEFAULT_DONT_DOWNLOAD)
        val handle = sm.find(hash) ?: throw TorrentException(tr("The torrent engine did not start."))
        val started = System.currentTimeMillis()
        watch(gen, ref, handle)
        val deadline = started + NO_PEERS_MS
        while (handle.torrentFile() == null) {
            alive(gen)
            if (System.currentTimeMillis() > deadline) throw TorrentException(
                if (handle.status().numPeers() == 0) tr("No peers found for this video. Try another source.") else tr("Found peers but they sent nothing. Try another source."))
            Thread.sleep(POLL_MS)
        }
        val info = requireNotNull(handle.torrentFile()) { "metadata" }
        android.util.Log.i(LOG, "metadata after ${System.currentTimeMillis() - started} ms, ${handle.status().numPeers()} peers, ${info.numFiles()} files, piece ${info.pieceLength()}")
        val files = info.files()
        val count = info.numFiles()
        val index = TorrentPlan.pick((0 until count).map { files.fileName(it) }, (0 until count).map { files.fileSize(it) }, ref.index)
        if (index < 0) throw TorrentException(tr("This torrent has no video file."))
        val need = files.fileSize(index)
        if (!TorrentPlan.admits(need, free(context, dir), SPARE_BYTES)) {
            throw TorrentException(tr("Not enough free space: this video needs %s and 500 MB spare.", megabytes(need)))
        }
        handle.prioritizeFiles(Array(count) { if (it == index) Priority.DEFAULT else Priority.IGNORE })
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val stream = TorrentStream(ref, handle, info, index, dir)
        stream.start()
        android.util.Log.i(LOG, "file $index $need bytes, started after ${System.currentTimeMillis() - started} ms")
        synchronized(lock) {
            if (gen != generation) throw InterruptedIOException()
            current = stream
        }
        return stream
    }

    private fun free(context: Context, dir: File): Long {
        val real = StatFs(dir.path).availableBytes
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return real
        return File(context.cacheDir, FAKE_FREE).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: real
    }

    private fun abandon(sm: SessionManager, hash: Sha1Hash, gen: Int) {
        synchronized(lock) { if (gen == generation) generation++ }
        runCatching { sm.find(hash)?.let { sm.remove(it, SessionHandle.DELETE_FILES) } }
        android.util.Log.i(LOG, "abandoned ${hash.toHex().take(8)}")
    }

    private val narrow: Boolean get() = android.os.Build.SUPPORTED_64_BIT_ABIS.isEmpty()

    private fun pack(): SettingsPack = SettingsPack()
        .uploadRateLimit(UPLOAD_BYTES_PER_S).connectionsLimit(if (narrow) NARROW_CONNECTIONS else MAX_CONNECTIONS).activeDownloads(1).maxQueuedDiskBytes(1 shl 20)
        .setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), REQUEST_QUEUE)
        .setInteger(settings_pack.int_types.max_peerlist_size.swigValue(), PEER_LIST)
        .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
        .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false)

    private fun alive(gen: Int) {
        if (gen != generation || Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    fun check(stream: TorrentStream) {
        if (current !== stream) throw InterruptedIOException()
    }

    private fun watch(gen: Int, ref: TorrentRef, handle: TorrentHandle) {
        var ticks = 0
        Thread {
            while (gen == generation) {
                runCatching {
                    val s = handle.status()
                    val peers = s.numPeers()
                    if (!failed) shown.value = when {
                        !waiting -> null
                        !s.hasMetadata() -> tr("Finding peers…") + if (peers > 0) " · " + tr("%d peers", peers) else ""
                        else -> tr("Downloading %1\$d%% · %2\$s/s · %3\$d peers", percent(), megabytes(s.downloadPayloadRate().toLong()), peers)
                    }
                    if (++ticks % LOG_EVERY == 0) android.util.Log.i(LOG, "down ${s.downloadPayloadRate() / 1000} kB/s up ${s.uploadPayloadRate() / 1000} kB/s peers $peers seeds ${s.numSeeds()} have ${percent()}%")
                    if (current?.let { it.haveBytes() >= it.size } == true) handle.pause()
                }
                try { Thread.sleep(STATUS_MS) } catch (e: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "torrent-status-${ref.hash.take(6)}" }.start()
    }

    private fun percent(): Int = current?.let { (it.haveBytes() * 100 / it.size.coerceAtLeast(1)).toInt() } ?: 0

    fun fail(message: String) {
        failedAt = System.currentTimeMillis()
        failed = true
        shown.value = message
    }

    fun recovered() {
        if (!failed) return
        failed = false
        shown.value = null
    }

    fun local(source: String): String {
        if (!source.startsWith("${TorrentRef.SCHEME}://")) return source
        return current?.takeIf { source.contains(it.ref.hash) }?.path?.path ?: throw TorrentException(tr("The video is no longer on this device: mine words while it plays."))
    }

    fun megabytes(bytes: Long): String =
        if (bytes >= 1L shl 30) String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0) else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)

    fun release() {
        synchronized(lock) {
            generation++
            val dir = root
            val sm = session
            val stream = current
            current = null
            session = null
            root = null
            if (sm != null || dir != null) teardown = Pending.track(AppScope.launch {
                runCatching { stream?.handle?.let { sm?.remove(it, SessionHandle.DELETE_FILES) } }
                runCatching { sm?.stop() }
                dir?.deleteRecursively()
            })
        }
        waiting = false
        failed = false
        shown.value = null
    }
}
