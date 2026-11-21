package io.github.pedrubik2000.kumapie.lang

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.pedrubik2000.kumapie.i18n.tr
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object SyncListener {
    private var socket: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private const val MAX_BODY = 64 shl 20

    val running: Boolean @Synchronized get() = socket != null

    @Synchronized fun start(context: Context) {
        if (socket != null) return
        val app = context.applicationContext
        val s = runCatching { ServerSocket(DeviceSync.PORT) }.getOrElse { ServerSocket(0) }
        socket = s
        thread(isDaemon = true, name = "device-sync") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                runCatching { c.use { handle(app, it) } }.onFailure { Log.w("kumapie", "sync answer: $it") }
            }
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w("kumapie", "sync: nsd $e") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        val info = NsdServiceInfo().apply { serviceName = Progress.of(app).device; serviceType = DeviceSync.SERVICE; port = s.localPort }
        runCatching { app.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, reg); registration = reg }
        Log.i("kumapie", "sync: answering on port ${s.localPort}")
    }

    @Synchronized fun stop(context: Context) {
        runCatching { socket?.close() }
        socket = null
        registration?.let { r -> runCatching { context.applicationContext.getSystemService(NsdManager::class.java).unregisterService(r) } }
        registration = null
    }

    private fun near(a: InetAddress) = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress

    private fun handle(context: Context, c: Socket) {
        if (!near(c.inetAddress)) return
        c.soTimeout = 30_000
        val input = c.getInputStream().buffered()
        val (_, path) = line(input).split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val headers = HashMap<String, String>()
        while (true) {
            val h = line(input)
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val a = if (length > MAX_BODY) DeviceSync.Answer(413, ByteArray(0)) else {
            val body = ByteArray(length)
            var n = 0
            while (n < length) { val r = input.read(body, n, length - n); if (r < 0) break; n += r }
            DeviceSync.answer(context, path, headers["x-device"], headers["x-sig"], body)
        }
        val out = c.getOutputStream()
        out.write(("HTTP/1.1 ${a.status} X\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${a.body.size}\r\n" +
            (a.sig?.let { "X-Sig: $it\r\n" } ?: "") + "Connection: close\r\n\r\n").toByteArray())
        out.write(a.body)
        out.flush()
    }

    private fun line(input: InputStream): String {
        val b = StringBuilder()
        while (true) {
            val ch = input.read()
            if (ch < 0 || ch == '\n'.code) break
            if (ch != '\r'.code) b.append(ch.toChar())
        }
        return b.toString()
    }
}

class SyncService : Service() {
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Answering your other devices"), NotificationManager.IMPORTANCE_MIN))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(tr("Answering your other devices")).setContentText(tr("kumapie syncs them over the home Wi-Fi."))
            .setOngoing(true).setSilent(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = SyncListener.start(this@SyncService)
            override fun onLost(network: Network) = SyncListener.stop(this@SyncService)
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        callback = cb
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "sync-answer"
        private const val ID = 7321

        fun start(context: Context) {
            val i = Intent(context, SyncService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i) }
                .onFailure { Log.w("kumapie", "sync service: $it") }
        }

        fun stop(context: Context) = context.stopService(Intent(context, SyncService::class.java))
    }
}
