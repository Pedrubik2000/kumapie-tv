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

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else optInt(name)
