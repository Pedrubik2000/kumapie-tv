package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Recordings of Japanese words by people: first the local collection (NHK16, Shinmeikai 8, JapanesePod101, Forvo
 * speakers; `models/ja-audio.sqlite`, built by tools/audio/build_ja.py and copied to the tablet), else JapanesePod101
 * online (as Yomitan does), else nothing (the caller falls back to the device voice). Files go to the cache.
 */
class JapaneseAudio(private val context: Context) {
    val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/ja-audio.sqlite")
    private val cache = File(context.cacheDir, "ja-audio").apply { mkdirs() }
    private var db: SQLiteDatabase? = null
    private var player: MediaPlayer? = null

    /** A recording of [expression] read [reading] (hiragana; blank = any), or null. */
    suspend fun recording(expression: String, reading: String): File? = withContext(Dispatchers.IO) {
        val key = (expression + "|" + reading).hashCode().toUInt().toString(16)
        cache.listFiles()?.firstOrNull { it.name.startsWith("$key.") && it.length() > 0 }?.let { return@withContext it }
        local(expression, reading)?.let { (ext, bytes) -> return@withContext File(cache, "$key.$ext").apply { writeBytes(bytes) } }
        online(expression, reading)?.let { bytes -> return@withContext File(cache, "$key.mp3").apply { writeBytes(bytes) } }
        null
    }

    /** Plays a recording of the word; false when there is none (say it with the device voice then). */
    suspend fun play(expression: String, reading: String): Boolean {
        val f = recording(expression, reading) ?: return false
        return withContext(Dispatchers.Main) {
            runCatching {
                player?.release()
                player = MediaPlayer().apply { setDataSource(f.path); setOnCompletionListener { it.release(); if (player === it) player = null }; prepare(); start() }
            }.isSuccess
        }
    }

    private fun local(expression: String, reading: String): Pair<String, ByteArray>? {
        if (!file.exists()) return null
        val d = db ?: runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()?.also { db = it } ?: return null
        // The written form with this reading first, then the written form, then the reading alone; NHK before Shinmeikai,
        // JapanesePod101 and Forvo.
        val order = "CASE w.source WHEN 'nhk16' THEN 0 WHEN 'shinmeikai8' THEN 1 WHEN 'jpod' THEN 2 ELSE 3 END"
        val tries = buildList {
            if (reading.isNotBlank()) add("w.term = ? AND w.reading = ?" to arrayOf(expression, reading))
            add("w.term = ?" to arrayOf(expression))
            if (reading.isNotBlank() && reading != expression) add("w.term = ?" to arrayOf(reading))
        }
        for ((where, args) in tries) {
            d.rawQuery("SELECT a.data, w.file FROM words w JOIN audio a ON a.source = w.source AND a.file = w.file WHERE $where ORDER BY $order LIMIT 1", args).use { c ->
                if (c.moveToFirst()) return c.getString(1).substringAfterLast('.', "opus") to c.getBlob(0)
            }
        }
        return null
    }

    /** JapanesePod101's own word audio (Yomitan's default source); its "no audio" clip is left out by its size. */
    private fun online(expression: String, reading: String): ByteArray? = runCatching {
        val url = "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=" + URLEncoder.encode(expression, "UTF-8") +
            if (reading.isNotBlank()) "&kana=" + URLEncoder.encode(reading, "UTF-8") else ""
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        try {
            if (conn.responseCode != 200) return@runCatching null
            conn.inputStream.use { it.readBytes() }.takeIf { it.size > 1000 && it.size != NO_AUDIO_SIZE }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    companion object {
        /** Size of JapanesePod101's "the audio for this clip is currently not available" MP3. */
        private const val NO_AUDIO_SIZE = 52288
    }
}
