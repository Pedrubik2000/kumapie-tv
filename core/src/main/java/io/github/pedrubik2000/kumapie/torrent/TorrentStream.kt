package io.github.pedrubik2000.kumapie.torrent

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class TorrentException(message: String, val fault: TorrentFault = TorrentFault.OTHER) : IOException(message)

class Sidecar(private val process: Process, private val port: Int, private val token: String, private val dir: File) {
    val alive: Boolean get() = process.isAlive

    fun status(files: Boolean = false): JSONObject {
        val c = URL("http://127.0.0.1:$port/status" + if (files) "?files=1" else "").openConnection() as HttpURLConnection
        c.setRequestProperty("X-Token", token)
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        try {
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    fun url(index: Int): String = "http://127.0.0.1:$port/stream?idx=$index&t=$token"

    fun stop() {
        runCatching { process.outputStream.close() }
        if (!process.waitFor(STOP_S, TimeUnit.SECONDS)) {
            process.destroy()
            process.waitFor(STOP_S, TimeUnit.SECONDS)
        }
        dir.deleteRecursively()
    }

    private companion object {
        const val TIMEOUT_MS = 2000
        const val STOP_S = 3L
    }
}

class TorrentStream(val ref: TorrentRef, val sidecar: Sidecar, val index: Int, val size: Long) {
    val url: String get() = sidecar.url(index)
}
