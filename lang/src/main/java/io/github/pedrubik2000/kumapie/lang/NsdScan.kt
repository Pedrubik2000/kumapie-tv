package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

data class Found(val name: String, val address: String, val url: String = "")

object NsdScan {
    private val resolving = Mutex()
    private const val RESOLVE_MS = 5_000L
    private const val RETRY_MS = 300L

    suspend fun scan(context: Context, ms: Long, type: String, skip: String = "", onFound: (List<Found>) -> Unit = {}): List<Found> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val out = CopyOnWriteArrayList<Found>()
        return coroutineScope {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName == skip) return
                    launch {
                        val i = resolve(nsd, info) ?: return@launch
                        @Suppress("DEPRECATION") val host = i.host?.hostAddress ?: return@launch
                        out.removeAll { it.name == i.serviceName }
                        out += Found(i.serviceName, "$host:${i.port}", i.attributes["url"]?.toString(Charsets.UTF_8).orEmpty())
                        onFound(out.toList())
                    }
                }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, e: Int) { Log.w("kumapie", "sync: discovery $e") }
                override fun onStopDiscoveryFailed(type: String, e: Int) {}
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return@coroutineScope emptyList() }
            try { delay(ms) } finally { runCatching { nsd.stopServiceDiscovery(listener) } }
            coroutineContext.cancelChildren()
            out.toList()
        }
    }

    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = resolving.withLock {
        var r = once(nsd, info)
        if (r.second == NsdManager.FAILURE_ALREADY_ACTIVE) {
            delay(RETRY_MS)
            r = once(nsd, info)
        }
        r.first
    }

    private suspend fun once(nsd: NsdManager, info: NsdServiceInfo): Pair<NsdServiceInfo?, Int> = withTimeoutOrNull(RESOLVE_MS) {
        suspendCancellableCoroutine { c ->
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(i: NsdServiceInfo) { if (c.isActive) c.resume(i to 0) }
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                    Log.w("kumapie", "sync: resolve ${i.serviceName}: $e")
                    if (c.isActive) c.resume(null to e)
                }
            })
        }
    } ?: (null to -1)
}
