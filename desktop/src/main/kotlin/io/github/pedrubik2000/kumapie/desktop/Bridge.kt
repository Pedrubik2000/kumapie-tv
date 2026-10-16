package io.github.pedrubik2000.kumapie.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * kuma3 desktop through the PC server's /api/anki (AnkiConnect behind it): kuma3's word states, rating, undo, mining,
 * sync; and /api/tv/lookup, which answers every dictionary sense for the word card.
 */
class Bridge(private val base: String) {
    class Closed : IOException("kuma3 is closed")

    /** A word's card state by form ("d" due, "k" studied, "b" mined, "n" learning) and its note, for one language. */
    data class Cards(val forms: Map<String, String>, val notes: Map<String, Long>)
    data class Mined(val note: Long, val deck: String)

    suspend fun up(): Boolean = runCatching { JSONObject(get("/api/anki/status")).getBoolean("up") }.getOrDefault(false)

    suspend fun cards(lang: String): Cards = JSONObject(get("/api/anki/cards?lang=$lang")).let { j ->
        val f = j.getJSONObject("forms")
        val n = j.getJSONObject("notes")
        Cards(f.keySet().associateWith { f.getString(it) }, n.keySet().associateWith { n.getLong(it) })
    }

    /** Answers the word's due cards (else its first); how many it answered. */
    suspend fun rate(lang: String, word: String, ease: Int): Int =
        JSONObject(post("/api/anki/rate", JSONObject().put("lang", lang).put("word", word).put("ease", ease))).getInt("answered")

    suspend fun undo(): Boolean = JSONObject(post("/api/anki/undo", JSONObject())).getBoolean("ok")
    suspend fun sync() { post("/api/anki/sync", JSONObject()) }
    suspend fun browse(note: Long) { post("/api/anki/browse", JSONObject().put("note", note)) }
    suspend fun delete(note: Long) { post("/api/anki/delete", JSONObject().put("note", note)) }

    /** A card from [line] of [scene] (a word card when [word] is set); the server cuts the clip. */
    suspend fun mine(episode: String, scene: String, line: Int, word: String?, surface: String?, gloss: String, english: String): Mined =
        JSONObject(post("/api/anki/mine", JSONObject().put("episode", episode).put("scene", scene).put("line", line)
            .put("word", word ?: JSONObject.NULL).put("surface", surface ?: JSONObject.NULL).put("gloss", gloss).put("english", english)))
            .let { Mined(it.getLong("note"), it.getString("deck")) }

    /** Counts a lookup; answers how many times the word was looked up and every sense of it (the PC's dictionaries). */
    suspend fun lookup(word: String, scene: String): Pair<Int, List<String>> =
        JSONObject(post("/api/tv/lookup", JSONObject().put("word", word).put("scene", scene))).let { j ->
            j.getInt("n") to (j.optJSONArray("senses")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
        }

    private suspend fun get(path: String) = call(path, null)
    private suspend fun post(path: String, body: JSONObject) = call(path, body.toString())

    private suspend fun call(path: String, body: String?): String = withContext(Dispatchers.IO) {
        val conn = URI(base + path).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 300_000 // mining cuts a clip; a sync can take a while
        try {
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                503 -> throw Closed()
                else -> throw IOException("HTTP $code: ${conn.responseMessage}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
