package io.github.pedrubik2000.kumapie.lang

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PcProbe {
    const val MS = 1_500

    suspend fun check(url: String, ms: Int = MS): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url.trimEnd('/') + "/api/tv").openConnection() as HttpURLConnection
            conn.connectTimeout = ms
            conn.readTimeout = ms
            conn.setRequestProperty("Connection", "close")
            try {
                if (conn.responseCode != 200) "HTTP ${conn.responseCode}"
                else { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getInt("api"); "" }
            } finally {
                conn.disconnect()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }
}
