package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The dojo server's /api/tv (services/feed/tv.py in the dojo repo). Every call runs on the IO dispatcher. [person]: whose
 * shows and history (the device's owner, "giovanna"); empty = the TV's person.
 */
class Api(private val base: String, private val person: String = "") : Backend {
    private val whose get() = if (person.isEmpty()) "" else "?person=$person"


    /** A server path ("/api/tv/thumb/abc.jpg") as a full URL. */
    fun url(path: String): String = if (path.startsWith("http")) path else base + path

    suspend fun info(): ServerInfo = JSONObject(get("/api/tv")).let {
        ServerInfo(api = it.getInt("api"), language = it.optString("language"),
            translation = it.optString("translation"), episodes = it.optInt("episodes"))
    }

    suspend fun shows(): List<Show> = parseShows(showsJson())

    /** The shows as the server sends them (the phone keeps a copy for when it is offline). */
    suspend fun showsJson(): String = get("/api/tv/shows$whose")

    fun parseShows(json: String): List<Show> {
        val shows = JSONObject(json).getJSONArray("shows")
        return shows.objects().map { s ->
            Show(
                id = s.getString("id"), title = s.getString("title"), kind = s.optString("kind"),
                poster = url(s.getString("poster")), lang = s.optString("lang", "de"),
                description = s.optString("description"), year = s.optIntOrNull("year"),
                episodes = s.getJSONArray("episodes").objects().map { e ->
                    Episode(
                        id = e.getString("id"), title = e.getString("title"),
                        season = e.optIntOrNull("season"), number = e.optIntOrNull("episode"),
                        duration = e.optDouble("duration", 0.0), thumb = url(e.getString("thumb")),
                        scenes = e.optInt("scenes"), seen = e.optInt("seen"), easy = e.optInt("easy"),
                        resume = if (e.isNull("resume")) null else e.getDouble("resume"),
                        episodeTitle = e.optString("episode_title").takeIf { it != "null" }.orEmpty(),
                        resumedAt = e.optLong("resumed_at"),
                    )
                },
            )
        }
    }

    suspend fun episode(id: String): EpisodeDetail = parseEpisode(episodeJson(id))

    /** An episode as the server sends it (the phone saves it with a downloaded episode). */
    suspend fun episodeJson(id: String): String = get("/api/tv/episode/$id")

    fun parseEpisode(json: String): EpisodeDetail {
        val e = JSONObject(json)
        val words = e.getJSONObject("words").let { w ->
            w.keys().asSequence().associateWith { k ->
                w.getJSONObject(k).let {
                    Word(status = it.optString("s", "u"), meaning = it.optString("g"), lemma = it.optString("lemma"), reading = it.optString("r"),
                        stability = if (it.isNull("d")) null else it.optDouble("d"), lookups = it.optInt("n"),
                        marked = it.optBoolean("m"))
                }
            }
        }
        val scenes = e.getJSONArray("scenes").objects().map { s ->
            Scene(
                id = s.getString("id"), index = s.getInt("i"), start = s.getDouble("start"), end = s.getDouble("end"),
                target = s.optBoolean("german"), level = s.optIntOrNull("level"), seen = s.optBoolean("seen"),
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
                                target = (0 until de.length()).map { i ->
                                    de.getJSONArray(i).let { a -> Segment(a.getString(0), if (a.isNull(1)) null else a.getString(1)) }
                                },
                            )
                        }
                    }
                } ?: emptyMap(),
                bgm = s.optString("bgm").takeIf { it.isNotEmpty() }?.let(::url),
                base = s.optString("base").takeIf { it.isNotEmpty() }?.let(::url),
                pics = s.optJSONArray("pics")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { p ->
                    Pic(p.getDouble(0), p.getDouble(1), url(p.getString(2)), if (p.isNull(3)) null else url(p.getString(3))) } } }.orEmpty(),
            )
        }
        return EpisodeDetail(
            id = e.getString("id"), show = e.getString("show"), title = e.getString("title"), lang = e.optString("lang", "de"),
            duration = e.optDouble("duration", 0.0), video = url(e.getString("video")),
            resume = if (e.isNull("resume")) null else e.getDouble("resume"), scenes = scenes, words = words,
            novel = e.optBoolean("novel"),
        )
    }

    /** Where the episode was left, scenes watched to their end, and seconds watched since the last report. */
    override suspend fun progress(episode: String, pos: Double, seen: Collection<String>, watched: Double) {
        val body = JSONObject().put("episode", episode).put("pos", pos).put("seen", JSONArray(seen)).put("watched", watched)
        post("/api/tv/progress", body.toString())
    }

    /** The people with kumapie, for the TV's "Who's watching?". */
    suspend fun people(): List<Profile> = JSONArray(get("/api/tv/people")).objects().map {
        Profile(it.getString("id"), it.getString("name"), it.optString("menu", "en"))
    }

    /** Everything the PC recorded (TV), as kumapie's progress JSON (see the phone app's Progress). */
    suspend fun history(): String = get("/api/tv/history$whose")

    suspend fun stats(): Stats {
        val o = JSONObject(get("/api/tv/stats$whose"))
        val days = o.getJSONObject("days").let { d -> d.keys().asSequence().associateWith { d.getDouble(it) } }
        return Stats(
            days = days, today = o.optDouble("today"), week = o.optDouble("week"), total = o.optDouble("total"),
            streak = o.optInt("streak"), scenesSeen = o.optInt("scenes_seen"), scenesTotal = o.optInt("scenes_total"),
            lookups = o.optInt("lookups"), wordsLookedUp = o.optInt("words_looked_up"),
            topLookups = o.getJSONArray("top_lookups").let { a ->
                (0 until a.length()).map { i -> a.getJSONArray(i).let { TopLookup(it.getString(0), it.getInt(1), it.optString(2)) } }
            },
            markedKnown = o.optInt("marked_known"),
            shows = o.getJSONArray("shows").objects().map { ShowProgress(it.getString("title"), it.getInt("seen"), it.getInt("scenes")) },
            langs = o.optJSONObject("langs")?.let { l -> l.keys().asSequence().associateWith { k -> l.getJSONObject(k).let {
                LangTotal(it.optDouble("today"), it.optDouble("week"), it.optDouble("total"), it.optInt("known")) } } }.orEmpty(),
        )
    }

    /** A word looked up in the picker; answers how many times it has been looked up. */
    override suspend fun lookup(word: String, scene: String): Int =
        JSONObject(post("/api/tv/lookup", JSONObject().put("word", word).put("scene", scene).toString())).optInt("n")

    /** Marks a word known without a card (or undoes it); answers its status now: "k", "l" or "u". */
    override suspend fun markKnown(word: String, known: Boolean): String =
        JSONObject(post("/api/tv/known", JSONObject().put("word", word).put("known", known).toString())).optString("s", "u")

    /** The words this device shows as known in [lang] (white or green): the PC's feed and TV count by them. */
    suspend fun knownWords(lang: String, words: List<String>) {
        post("/api/tv/known_words", JSONObject().put("person", person).put("lang", lang).put("words", org.json.JSONArray(words)).toString())
    }

    /** What this person's devices count as seen ({scene: episode}) and their "Mark as not watched" times ({episode:
     *  epoch s}): the TV blurs and counts by them too. */
    suspend fun deviceSeen(seen: Map<String, String>, unwatched: Map<String, Long>) {
        post("/api/tv/device_seen", JSONObject().put("person", person).put("seen", JSONObject(seen))
            .put("unwatched", JSONObject(unwatched)).toString())
    }

    /** This person's devices' own watch time ({lang: {study day: seconds}}), for the TV's Home card. */
    suspend fun deviceWatch(watch: Map<String, Map<String, Double>>) {
        post("/api/tv/device_watch", JSONObject().put("person", person).put("watch", JSONObject(watch.mapValues { JSONObject(it.value) })).toString())
    }

    /** "Fix match" for a PC show ([show] = its id, the library folder): the PC fetches and keeps the metadata. */
    suspend fun setMeta(show: String, kind: String, source: String, id: String) {
        post("/api/tv/meta", JSONObject().put("show", show).put("kind", kind).put("source", source).put("id", id).toString())
    }

    /** A line's German definition read aloud. */
    override fun definitionAudio(scene: String, line: Int, word: String): String =
        url("/api/tts?d=" + java.net.URLEncoder.encode("$scene|$line|$word", "UTF-8"))

    /** The word read aloud (the server makes and caches it). */
    override fun wordAudio(surface: String, lang: String): String =
        url("/api/tts?w=" + java.net.URLEncoder.encode(surface.lowercase(), "UTF-8") + "&lang=$lang")

    /**
     * An episode made on the PC (kumapie-server services\feed\jobs.py): [job] has "url" (AniWorld, YouTube / TikTok with
     * "kind" and "count", a magnet or Real-Debrid link with "rd") or "upload" (sent with [upload]), and "show", "type",
     * "height", "jimaku"; the person and [lang] are added here.
     */
    suspend fun makeOnPc(lang: String, job: JSONObject) = post("/api/aniworld", job.put("person", person).put("lang", lang).toString())

    suspend fun aniworldJobs() = JSONArray(get("/api/aniworld"))

    /**
     * A video file for [makeOnPc] as "<id>/<name>": sent [CHUNK] bytes a request from where the PC is (resumable: an
     * empty first request answers what it has); [shown] gets how far (0..1).
     */
    suspend fun upload(id: String, name: String, size: Long, input: java.io.InputStream, shown: suspend (Float) -> Unit): String =
        withContext(Dispatchers.IO) {
            val path = "/api/aniworld/upload/$id/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            fun put(offset: Long, buf: ByteArray, n: Int): Long {
                val conn = URL(url("$path?offset=$offset&total=$size")).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 60_000
                conn.requestMethod = "PUT"
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(n)
                try {
                    conn.outputStream.use { it.write(buf, 0, n) }
                    val code = conn.responseCode
                    if (code != 200 && code != 409) throw IOException(tr("HTTP %1\$d for %2\$s", code, path))
                    return JSONObject((if (code == 200) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }).getLong("size")
                } finally {
                    conn.disconnect()
                }
            }
            val buf = ByteArray(CHUNK)
            var offset = put(0, buf, 0) // what the PC has from an earlier try
            input.use {
                var skip = offset
                while (skip > 0) skip -= it.skip(skip).takeIf { s -> s > 0 } ?: throw IOException("can't skip in $name")
                while (offset < size) {
                    shown(offset.toFloat() / size)
                    var n = 0
                    while (n < buf.size) { val r = it.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    if (n == 0) throw IOException("$name ended at $offset of $size bytes")
                    val got = put(offset, buf, n)
                    if (got != offset + n) throw IOException("the PC has $got bytes of $name, not ${offset + n}")
                    offset = got
                }
            }
            "$id/$name"
        }

    private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url(path)).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) throw IOException(tr("HTTP %1\$d for %2\$s", conn.responseCode, path))
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
            if (conn.responseCode != 200) throw IOException(tr("HTTP %1\$d for %2\$s", conn.responseCode, path))
            conn.inputStream.bufferedReader().use { it.readText() }
                .also { Log.i("kumapie", "GET $path: ${it.length} chars in ${System.currentTimeMillis() - started} ms") }
        } finally {
            conn.disconnect()
        }
    }
}

private const val CHUNK = 8 shl 20 // an upload request: 8 MB

/** A person on the TV's "Who's watching?": [id] as the PC knows them ("giovanna"), [menu] "en" / "es". */
data class Profile(val id: String, val name: String, val menu: String)

data class ServerInfo(val api: Int, val language: String, val translation: String, val episodes: Int)

data class Show(val id: String, val title: String, val kind: String, val poster: String, val episodes: List<Episode>,
                /** [Lang.code] its episodes are in; the PC's shows are German. */
                val lang: String = "de",
                /** From its metadata (items 20-23), in the person's language; "" / null without a match. */
                val description: String = "", val year: Int? = null)

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
    /** Its title from the show's metadata; shown only once [watched] (titles can spoil). */
    val episodeTitle: String = "",
    /** When [resume] was saved (epoch seconds, 0 = never): "Continue watching" lists the newest first. */
    val resumedAt: Long = 0,
) {
    /** Every scene seen to its end (on any device), or marked watched: no spoilers left in it. */
    val watched: Boolean get() = scenes > 0 && seen >= scenes
}

/** Watch time (seconds) per study day ("2026-10-04", days start at 4 am) and what was done. */
data class Stats(
    val days: Map<String, Double>,
    val today: Double,
    val week: Double,
    val total: Double,
    /** Days in a row with at least a minute watched. */
    val streak: Int,
    val scenesSeen: Int,
    val scenesTotal: Int,
    val lookups: Int,
    val wordsLookedUp: Int,
    val topLookups: List<TopLookup>,
    val markedKnown: Int,
    val shows: List<ShowProgress>,
    /** Per language: TV + phones' time and the words known (the TV's Home card). */
    val langs: Map<String, LangTotal> = emptyMap(),
)

data class LangTotal(val today: Double, val week: Double, val total: Double, val known: Int)

data class TopLookup(val word: String, val times: Int, val meaning: String)

data class ShowProgress(val title: String, val seen: Int, val scenes: Int)

/** An episode as the player needs it. Times are seconds in the episode's video. */
data class EpisodeDetail(
    val id: String,
    val show: String,
    val title: String,
    /** The language it is spoken in ([Lang.code]); "de" when the JSON doesn't say. */
    val lang: String = "de",
    val duration: Double,
    val video: String,
    val resume: Double?,
    val scenes: List<Scene>,
    /** Every word of the episode: known/learning/unknown today and its meaning. */
    val words: Map<String, Word>,
    /** A visual novel (tools/vn on the PC): one line per scene, it waits after every line, music under it ([Scene.bgm]). */
    val novel: Boolean = false,
)

data class Scene(
    val id: String,
    val index: Int,
    val start: Double,
    val end: Double,
    /** False for lines not in the episode's language (songs, English in a German show): no level, no words. JSON "german". */
    val target: Boolean,
    /** Words in the scene that are neither known nor learning; null when not [target]. */
    val level: Int?,
    val seen: Boolean,
    val cues: List<Cue>,
    val english: List<Line>,
    /** Meanings that differ from the word's usual one in this scene. */
    val meanings: Map<String, String>,
    /** Per-line definitions, by "<line>|<word>" (definitions v2). */
    val defs: Map<String, LineDef> = emptyMap(),
    /** A novel's music under this line (URL), played on its own so it goes on while the line waits. */
    val bgm: String? = null,
    /** A novel kumapie draws (item 46): the screen when the line starts (URL), then its changes in order. */
    val base: String? = null,
    val pics: List<Pic> = emptyList(),
) {
    fun def(line: Int, word: String): LineDef? = defs["$line|$word"]

    /** The novel's screen showing at [t] (episode seconds), done fading in; null when the episode has a picture video. */
    fun picAt(t: Double): String? = pics.lastOrNull { it.start + it.fade <= t }?.url ?: base
}

/** A novel's screen change: from [start] (episode seconds) [url] fades in over [fade] s ([mask]: the game's wipe). */
data class Pic(val start: Double, val fade: Double, val url: String, val mask: String?)

/**
 * A word's definition in one line: dictionary form, English (translation + explanation), a dictionary-style
 * definition in the episode's language split into words (so they can be coloured and picked; JSON "de"), and a
 * grammar note.
 */
data class LineDef(val lemma: String, val english: String, val target: List<Segment>, val grammar: String) {
    val targetText: String get() = target.joinToString("") { it.text }
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
    /**
     * The word's own Anki card, when the phone knows it (kumapie_anki_review_plan.md): "d" due by kuma3's queue
     * (green), "k" studied and not due (white), "b" mined, never reviewed (blue), "n" still learning (red), "u" no card of its own (red).
     * The subtitles and the word card colour by it; scene levels still go by [status].
     */
    val card: String? = null,
    /**
     * Japanese: each way the episode writes this word (届いた, 届かない for 届く) -> its own card's state as in [card],
     * "" while kuma3's queue isn't known. Only a fallback when the dictionary form has no card ([formCard]).
     */
    val forms: Map<String, String>? = null,
    /** Japanese written with kanji: its kana reading, from the PC's dictionaries (the TV card shows it). */
    val reading: String = "",
) {
    /** The colour to draw: white when marked known, else [card]'s when known ("n" draws like "u"), else [status]. */
    val shown: String get() = if (marked) "k" else when (card) { null -> status; "n" -> "u"; else -> card }

    /**
     * Japanese: the card that colours [text]: the dictionary form's (躱し by 躱す, user 2026-10-13), else the written form's
     * own (a card mined as 届いた); null for [card].
     */
    fun formCard(text: String): String? =
        if (card != null && card != "u") null else forms?.get(text)?.takeIf { it.isNotEmpty() }

    /** The colour of the word written as [text] in a subtitle: marked known white, else [shown] by [formCard]. */
    fun shownFor(text: String): String =
        if (marked) "k" else formCard(text)?.let { if (it == "n") "u" else it } ?: shown
}

/**
 * A scene's level (i+N), null when it isn't in the target language: its different words drawn red ([Word.shownFor]:
 * no card of their own or new / learning, not marked known; without card states, never studied). Japanese counts by
 * dictionary form: 届いた and 届く are one.
 */
fun levelOf(scene: Scene, words: Map<String, Word>): Int? = if (!scene.target) null else unknownIn(scene.cues, words)

/** The different red words of [cues] ([levelOf]'s count; "Cards that don't stick" orders sentences by it). */
fun unknownIn(cues: List<Cue>, words: Map<String, Word>): Int = cues.flatMap { it.segments }
    .mapNotNull { seg -> seg.word?.let { k -> words[k]?.takeIf { it.shownFor(seg.text).let { s -> s == "u" || s == "b" } }?.let { k } } }
    .toSet().size

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else optInt(name)
