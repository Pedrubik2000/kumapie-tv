package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object ReportSender {
    private const val PC_MS = 3_000L
    private const val POST_MS = 8_000
    const val ISSUE_CAP = 6_000

    private suspend fun pcUrl(context: Context): String {
        val set = Settings(context).server
        if (set.isNotBlank()) return set.trimEnd('/')
        val pc = NsdScan.scan(context, PC_MS, DeviceSync.PC_SERVICE).firstOrNull() ?: return ""
        return MainDeviceFlow.pcUrl(pc.address).trimEnd('/')
    }

    private fun post(base: String, path: String, body: String): Boolean {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = POST_MS
        conn.readTimeout = POST_MS
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Connection", "close")
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }

    suspend fun deliver(context: Context, path: String, json: String, qrCap: Int = 0, issueUrl: (Int) -> String): String? {
        val sent = withContext(Dispatchers.IO) {
            val base = pcUrl(context)
            base.isNotEmpty() && runCatching { post(base, path, json) }.getOrDefault(false)
        }
        return if (sent) null else issueUrl(if (qrCap > 0) qrCap else ISSUE_CAP)
    }
}
