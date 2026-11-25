package io.github.pedrubik2000.kumapie.lang

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class SplitterEntry(val name: String, val size: Long, val sha256: String)

data class SplitterManifest(val version: String, val files: List<SplitterEntry>) {
    companion object {
        const val BASE = "https://github.com/Pedrubik2000/kumapie-tv/releases/download/jiten-splitter/"
        const val NAME = "jiten-splitter.json"

        fun parse(text: String): SplitterManifest {
            val json = JSONObject(text)
            val files = json.getJSONArray("files")
            return SplitterManifest(json.getString("version"), (0 until files.length()).map {
                files.getJSONObject(it).let { f -> SplitterEntry(f.getString("name"), f.getLong("size"), f.getString("sha256").lowercase()) }
            })
        }
    }
}

fun SplitterEntry.matches(file: File): Boolean = file.length() == size && sha256Of(file) == sha256

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
