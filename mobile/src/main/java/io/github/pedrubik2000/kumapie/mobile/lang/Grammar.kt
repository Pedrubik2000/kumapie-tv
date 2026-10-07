package io.github.pedrubik2000.kumapie.mobile.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.i18n.tr
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import io.github.pedrubik2000.kumapie.data.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * The A1 grammar cards (german_grammar_plan.md in the dojo repo) added on the device, one point a day. The PC writes
 * the points in batches (`tools/grammar/grammar.py export`: point.json + the edge-tts audio) and pushes them into
 * `files/grammar/<point>/`; here Pedro chooses each sentence's picture (Openverse, cached) and the point goes into kuma3
 * Anki: note type "🇩🇪 Grammatik", deck Deutsch::Grammatik, tags `_de::grammar grammar::<point>`, as the PC's import did.
 * A point counts as added when Anki has notes with its tag (so it is the same on every device).
 */
class Grammar(private val context: Context, private val settings: Settings, private val anki: AnkiCards = AnkiCards(context)) {
    val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "grammar").apply { mkdirs() }
    private val cache = File(base, ".openverse").apply { mkdirs() }

    class Note(val fields: JSONObject, val audio: List<String>) {
        val sentence: String get() = Html.fromHtml(fields.optString("Sentence"), 0).toString()
        val english: String get() = Html.fromHtml(fields.optString("English"), 0).toString()
        val query: String get() = Html.fromHtml(fields.optString("ImageQuery"), 0).toString()
    }

    class Point(val dir: File, val number: Int, val key: String, val title: String, val explanation: String,
                val tags: List<String>, val deck: String, val noteType: String, val notes: List<Note>)

    /** A picture found for a sentence: shown by [thumb], saved from [url]; [credit] goes into the Credit field. */
    class Picture(val thumb: String, val url: String, val credit: String)

    /** The points pushed from the PC, in order. */
    fun points(): List<Point> = (base.listFiles() ?: emptyArray()).mapNotNull { dir ->
        runCatching {
            val o = JSONObject(File(dir, "point.json").readText())
            val notes = o.getJSONArray("notes").let { a ->
                (0 until a.length()).map { i ->
                    a.getJSONObject(i).let { n -> Note(n.getJSONObject("fields"), n.getJSONArray("audio").let { x -> (0 until x.length()).map(x::getString) }) }
                }
            }
            Point(dir, o.getInt("number"), o.getString("key"), o.getString("title"), o.getString("explanation"),
                o.getJSONArray("tags").let { t -> (0 until t.length()).map(t::getString) }, o.getString("deck"), o.getString("note_type"), notes)
        }.getOrNull()
    }.sortedBy { it.number }

    /** The Anki to write to (kuma3 Anki first). */
    fun ankiApp(): String? = anki.installed().firstOrNull()

    /** Keys of the points already in Anki. */
    fun added(pkg: String, points: List<Point>): Set<String> =
        points.filter { p -> anki.notes(pkg, "\"tag:${p.tags.last()}\"").isNotEmpty() }.map { it.key }.toSet()

    /** A point was added today already (one a day, as the deck's 20 new cards a day expect). */
    val addedToday: Boolean get() = settings.prefs.getString("grammar_day", "") == LocalDate.now().toString()

    /**
     * CC photos from Openverse for [query], best title/tag matches first (as the PC's grammar tool chose), at least
     * 400x300, nothing suggestive. The answer is cached per query, so a point's choices work offline once searched.
     */
    fun search(query: String): List<Picture> {
        val file = File(cache, query.lowercase().replace(Regex("[^a-z0-9]+"), "_") + ".json")
        val body = file.takeIf { it.exists() }?.readText() ?: run {
            val url = "https://api.openverse.org/v1/images/?page_size=20&mature=false&q=" + URLEncoder.encode(query, "UTF-8")
            get(url).decodeToString().also { file.writeText(it) }
        }
        val words = Regex("[a-z]+").findAll(query.lowercase()).map { it.value }.filter { it !in STOP }.toList()
        val results = JSONObject(body).optJSONArray("results") ?: JSONArray()
        fun score(r: JSONObject): Int {
            val tags = r.optJSONArray("tags")?.let { t -> (0 until t.length()).joinToString(" ") { t.getJSONObject(it).optString("name") } } ?: ""
            val text = (r.optString("title") + " " + tags).lowercase()
            if (AVOID.any { Regex("\\b$it").containsMatchIn(text) }) return -1
            return words.count { Regex("\\b$it").containsMatchIn(text) }
        }
        return (0 until results.length()).map { results.getJSONObject(it) }
            .filter { it.optInt("width") >= 400 && it.optInt("height") >= 300 && score(it) >= 0 }
            .sortedByDescending { score(it) }
            .map { r ->
                var credit = "${r.optString("title").ifBlank { "photo" }} - ${r.optString("creator").ifBlank { "unknown" }} (${r.optString("license")}"
                if (r.optString("license_version").isNotBlank()) credit += " ${r.optString("license_version")}"
                credit += ") ${r.optString("foreign_landing_url")}".trimEnd()
                Picture(r.optString("thumbnail").ifBlank { r.optString("url") }, r.optString("url"), Html.escapeHtml(credit))
            }
    }

    /**
     * The point into Anki: each note's audio and chosen picture (made at most 640 px, JPEG) into the media folder,
     * then the note in the point's deck. [pictures]: per note, or null for none (left for later).
     */
    fun add(point: Point, pictures: List<Picture?>, progress: (String) -> Unit): String {
        val pkg = ankiApp() ?: error(tr("No kuma3 Anki on this device."))
        if (point.key in added(pkg, listOf(point))) return tr("Already in Anki.")
        val (mid, names) = anki.noteType(pkg, listOf(point.noteType)) ?: error(tr("No note type \"%s\" in Anki.", point.noteType))
        val deck = anki.deck(pkg, point.deck)
        point.notes.forEachIndexed { i, note ->
            progress(tr("Adding %1\$d of %2\$d…", i + 1, point.notes.size))
            val fields = JSONObject(note.fields.toString())
            for (name in note.audio) {
                val stored = anki.addMedia(pkg, File(point.dir, name), name.substringBeforeLast(".")) // Anki adds the extension
                for (f in listOf("Sentence Audio", "Cloze Audio")) fields.put(f, fields.optString(f).replace("[sound:$name]", "[sound:$stored]"))
            }
            pictures.getOrNull(i)?.let { pic ->
                runCatching {
                    val jpg = File(point.dir, "${point.key}_${i + 1}.jpg")
                    if (!jpg.exists()) savePicture(pic.url, jpg)
                    val stored = anki.addMedia(pkg, jpg, "grammar_${point.key}_${"%02d".format(i + 1)}")
                    fields.put("Image", "<img src=\"$stored\">").put("Credit", pic.credit)
                }
            }
            anki.addNote(pkg, mid, names.map { fields.optString(it) }, point.tags, deck)
        }
        settings.prefs.edit().putString("grammar_day", LocalDate.now().toString()).apply()
        return tr("Added: %1\$d. %2\$s (%3\$d notes).", point.number, point.title, point.notes.size)
    }

    private fun savePicture(url: String, out: File) {
        val bytes = get(url)
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("not a picture")
        val scale = minOf(1f, 640f / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", AssetWorker.USER_AGENT)
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} from ${URL(url).host}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val STOP = setOf("a", "an", "the", "of", "in", "on", "at", "with", "and", "for", "very", "is", "are")
        private val AVOID = listOf("topless", "nude", "naked", "sexy", "lingerie", "bikini", "erotic", "porn", "boudoir", "underwear")
    }
}
