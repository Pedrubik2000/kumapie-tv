package io.github.pedrubik2000.kumapie.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The dojo server's /api/tv (services/feed/tv.py in the dojo repo). Every call runs on the IO dispatcher. */
class Api(private val base: String) {

    /** A server path ("/api/tv/thumb/abc.jpg") as a full URL. */
    fun url(path: String): String = if (path.startsWith("http")) path else base + path

    suspend fun info(): ServerInfo = JSONObject(get("/api/tv")).let {
        ServerInfo(api = it.getInt("api"), language = it.optString("language"),
            translation = it.optString("translation"), episodes = it.optInt("episodes"))
    }

    suspend fun shows(): List<Show> {
        val shows = JSONObject(get("/api/tv/shows")).getJSONArray("shows")
        return shows.objects().map { s ->
            Show(
                id = s.getString("id"), title = s.getString("title"), kind = s.optString("kind"),
                poster = url(s.getString("poster")),
                episodes = s.getJSONArray("episodes").objects().map { e ->
                    Episode(
                        id = e.getString("id"), title = e.getString("title"),
                        season = e.optIntOrNull("season"), number = e.optIntOrNull("episode"),
                        duration = e.optDouble("duration", 0.0), thumb = url(e.getString("thumb")),
                        scenes = e.optInt("scenes"), seen = e.optInt("seen"), easy = e.optInt("easy"),
                        resume = if (e.isNull("resume")) null else e.getDouble("resume"),
                    )
                },
            )
        }
    }

    suspend fun episode(id: String): EpisodeDetail {
        val e = JSONObject(get("/api/tv/episode/$id"))
        val words = e.getJSONObject("words").let { w ->
            w.keys().asSequence().associateWith { k ->
                w.getJSONObject(k).let {
                    Word(status = it.optString("s", "u"), meaning = it.optString("g"), lemma = it.optString("lemma"),
                        stability = if (it.isNull("d")) null else it.optDouble("d"), lookups = it.optInt("n"),
                        marked = it.optBoolean("m"))
                }
            }
        }
        val scenes = e.getJSONArray("scenes").objects().map { s ->
            Scene(
                id = s.getString("id"), index = s.getInt("i"), start = s.getDouble("start"), end = s.getDouble("end"),
                german = s.optBoolean("german"), level = s.optIntOrNull("level"), seen = s.optBoolean("seen"),
                cues = s.getJSONArray("cues").objects().map { c ->
                    val seg = c.getJSONArray("seg")
                    Cue(c.getDouble("start"), c.getDouble("end"), (0 until seg.length()).map { i ->
                        seg.getJSONArray(i).let { Segment(it.getString(0), if (it.isNull(1)) null else it.getString(1)) }
                    })
                },
                english = s.getJSONArray("english").let { en ->
                    (0 until en.length()).map { i -> en.getJSONArray(i).let { Line(it.getDouble(0), it.getDouble(1), it.getString(2)) } }
                },
                meanings = s.getJSONObject("g").let { g -> g.keys().asSequence().associateWith { g.getString(it) } },
                defs = s.optJSONObject("defs")?.let { d ->
                    d.keys().asSequence().associateWith { k ->
                        d.getJSONObject(k).let {
                            val de = it.getJSONArray("de")
                            LineDef(
                                lemma = it.optString("lemma"), english = it.optString("en"), grammar = it.optString("gr"),
                                german = (0 until de.length()).map { i ->
                                    de.getJSONArray(i).let { a -> Segment(a.getString(0), if (a.isNull(1)) null else a.getString(1)) }
                                },
                            )
                        }
                    }
                } ?: emptyMap(),
            )
        }
        return EpisodeDetail(
            id = e.getString("id"), show = e.getString("show"), title = e.getString("title"),
            duration = e.optDouble("duration", 0.0), video = url(e.getString("video")),
            resume = if (e.isNull("resume")) null else e.getDouble("resume"), scenes = scenes, words = words,
        )
    }

    /** Where the episode was left, scenes watched to their end, and seconds watched since the last report. */
    suspend fun progress(episode: String, pos: Double, seen: Collection<String>, watched: Double) {
        val body = JSONObject().put("episode", episode).put("pos", pos).put("seen", JSONArray(seen)).put("watched", watched)
        post("/api/tv/progress", body.toString())
    }

    /** A word looked up in the picker; answers how many times it has been looked up. */
    suspend fun lookup(word: String, scene: String): Int =
        JSONObject(post("/api/tv/lookup", JSONObject().put("word", word).put("scene", scene).toString())).optInt("n")

    /** Marks a word known without a card (or undoes it); answers its status now: "k", "l" or "u". */
    suspend fun markKnown(word: String, known: Boolean): String =
        JSONObject(post("/api/tv/known", JSONObject().put("word", word).put("known", known).toString())).optString("s", "u")

    /** The word read aloud (the server makes and caches it). */
    /** A line's German definition read aloud. */
    fun definitionAudio(scene: String, line: Int, word: String): String =
        url("/api/tts?d=" + java.net.URLEncoder.encode("$scene|$line|$word", "UTF-8"))

    fun wordAudio(surface: String): String = url("/api/tts?w=" + java.net.URLEncoder.encode(surface.lowercase(), "UTF-8"))

    private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url(path)).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $path")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val conn = URL(url(path)).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $path")
            conn.inputStream.bufferedReader().use { it.readText() }
                .also { Log.i("kumapie", "GET $path: ${it.length} chars in ${System.currentTimeMillis() - started} ms") }
        } finally {
            conn.disconnect()
        }
    }
}

data class ServerInfo(val api: Int, val language: String, val translation: String, val episodes: Int)

data class Show(val id: String, val title: String, val kind: String, val poster: String, val episodes: List<Episode>)

data class Episode(
    val id: String,
    val title: String,
    val season: Int?,
    val number: Int?,
    val duration: Double,
    val thumb: String,
    val scenes: Int,
    val seen: Int,
    /** Scenes at i+0 / i+1 for Pedro today. */
    val easy: Int,
    /** Seconds into the episode where it was left, or null. */
    val resume: Double?,
)

/** An episode as the player needs it. Times are seconds in the episode's video. */
data class EpisodeDetail(
    val id: String,
    val show: String,
    val title: String,
    val duration: Double,
    val video: String,
    val resume: Double?,
    val scenes: List<Scene>,
    /** Every word of the episode: known/learning/unknown today and its meaning. */
    val words: Map<String, Word>,
)

data class Scene(
    val id: String,
    val index: Int,
    val start: Double,
    val end: Double,
    /** False for lines that are not German speech (songs, English): no level, no words. */
    val german: Boolean,
    /** Words in the scene that are neither known nor learning; null when not German. */
    val level: Int?,
    val seen: Boolean,
    val cues: List<Cue>,
    val english: List<Line>,
    /** Meanings that differ from the word's usual one in this scene. */
    val meanings: Map<String, String>,
    /** Per-line definitions, by "<line>|<word>" (definitions v2). */
    val defs: Map<String, LineDef> = emptyMap(),
) {
    fun def(line: Int, word: String): LineDef? = defs["$line|$word"]
}

/**
 * A word's definition in one line: dictionary form, English (translation + explanation), a German
 * dictionary-style definition split into words (so they can be coloured and picked), and a grammar note.
 */
data class LineDef(val lemma: String, val english: String, val german: List<Segment>, val grammar: String) {
    val germanText: String get() = german.joinToString("") { it.text }
}

data class Cue(val start: Double, val end: Double, val segments: List<Segment>) {
    val text: String get() = segments.joinToString("") { it.text }
}

/** A piece of a subtitle line: a word (with its key in EpisodeDetail.words) or the text between words. */
data class Segment(val text: String, val word: String?)

data class Line(val start: Double, val end: Double, val text: String)

/**
 * status: "k" known, "l" learning (in Anki, below the known stability), "u" never studied.
 * lemma: dictionary form; stability: best FSRS stability in days of its cards (null if never reviewed);
 * lookups: times looked up on the TV; marked: marked known on the TV (no card).
 */
data class Word(
    val status: String,
    val meaning: String,
    val lemma: String = "",
    val stability: Double? = null,
    val lookups: Int = 0,
    val marked: Boolean = false,
)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else optInt(name)
