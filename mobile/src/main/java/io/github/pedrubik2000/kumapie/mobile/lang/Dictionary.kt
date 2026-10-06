package io.github.pedrubik2000.kumapie.mobile.lang

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.text.Html
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** One dictionary entry: a word as one part of speech, with all its senses. */
data class DictEntry(
    val word: String,
    val pos: String,
    /** The headword line: "Hund m (strong, genitive Hundes …, plural Hunde …)". */
    val head: String,
    val ipa: String,
    val senses: List<Sense>,
    /** From Wiktionary online (cached), not from the offline file. */
    val online: Boolean = false,
)

data class Sense(val gloss: String, val tags: String, val examples: List<Pair<String, String>>)

/**
 * German-English meanings without the PC and without paying:
 * - offline: an SQLite file built from English Wiktionary (kaikki.org) by the repo's Dictionary workflow
 *   (tools/dictionary/build.py), downloaded once: entries with every sense, forms -> dictionary form, recordings;
 * - online, for words the file doesn't have: Wiktionary's definition API, every answer kept in a local cache
 *   (also "not found"), so a word is asked for once.
 * Wiktionary content is CC BY-SA 4.0 ([ATTRIBUTION]).
 */
class Dictionary(private val context: Context, private val yomitan: YomitanDictionaries? = null) {
    private val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "dictionary")
    val file = File(base, "de-en.sqlite")
    val isReady: Boolean get() = file.exists()
    private val recordings = File(base, "recordings")
    private val work get() = WorkManager.getInstance(context)

    @Volatile private var db: SQLiteDatabase? = null
    private var openedVersion = 0L
    private val cache: SQLiteDatabase by lazy {
        SQLiteDatabase.openOrCreateDatabase(File(context.filesDir, "dictionary-cache.sqlite"), null).apply {
            execSQL("create table if not exists online (term text primary key, json text, time integer)")
        }
    }

    fun download() {
        val request = OneTimeWorkRequestBuilder<DictionaryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun state(): Flow<String?> = AssetWorker.state(work, WORK)

    /** When the offline file was built ("2026-10-05"), or null. */
    fun built(): String? = runCatching {
        open()?.rawQuery("select value from meta where key = 'built'", null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** The offline file, opened again when a new download has replaced it. */
    @Synchronized
    private fun open(): SQLiteDatabase? {
        if (!isReady) return null
        val version = file.lastModified()
        db?.let { if (it.isOpen && version == openedVersion) return it else it.close() }
        openedVersion = version
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
    }

    /** Closes the file so a new download can replace it. */
    @Synchronized
    fun close() {
        db?.close()
        db = null
    }

    /**
     * Every entry for the word as it appears in the subtitles: [surface] ("Hunde"), its morph key ([key], "rufe an"
     * for a split verb) and spaCy's dictionary form ([lemma]). The dictionary form's entries come first. Online
     * only when the file has nothing (and [online] is allowed).
     */
    suspend fun lookup(surface: String, key: String?, lemma: String?, online: Boolean = true): List<DictEntry> =
        withContext(Dispatchers.IO) {
            // Imported Yomitan dictionaries first (kumapie_languages_plan.md step 2b); the Wiktionary file stays the fallback.
            val yomi = runCatching { yomitan(surface, key, lemma) }.onFailure { Log.w("kumapie", "yomitan: $it") }.getOrDefault(emptyList())
            if (yomi.isNotEmpty()) return@withContext yomi
            val found = runCatching { offline(surface, key, lemma) }.onFailure { Log.w("kumapie", "dictionary: $it") }
                .getOrDefault(emptyList())
            if (found.isNotEmpty() || !online) return@withContext found
            val term = lemma?.takeIf { it.isNotBlank() } ?: surface
            onlineEntries(term).ifEmpty { if (!term.equals(surface, true)) onlineEntries(surface) else emptyList() }
        }

    /** The imported German Yomitan dictionaries' entries: the dictionary form first, then the written forms. */
    private fun yomitan(surface: String, key: String?, lemma: String?): List<DictEntry> {
        val y = yomitan ?: return emptyList()
        val de = io.github.pedrubik2000.kumapie.data.Lang.GERMAN
        val words = listOfNotNull(lemma, key, surface).flatMap { listOf(it, it.lowercase()) }.filter { it.isNotBlank() }.distinct()
        val found = words.flatMap { y.query(de, it) }
        // Form-of entries ("glaubst": second-person singular present of glauben) lead to the dictionary form: its
        // meanings come first, the form-of notes after.
        val bases = found.flatMap { t -> t.glossaries.flatMap { it.formOf } }.distinct().filter { it !in words }
        // A split verb's key ("sag bescheid", "rufe an"): the verb's dictionary form with its particle, as a phrase
        // ("Bescheid sagen") or one word ("anrufen").
        val phrases = key?.split(' ')?.takeIf { it.size == 2 }?.let { (verb, particle) ->
            val verbBases = (listOf(verb) + y.query(de, verb).flatMap { t -> t.glossaries.flatMap { it.formOf } }).distinct()
            verbBases.flatMap { b -> listOf(particle + b, "${particle.replaceFirstChar { it.uppercase() }} $b", "$particle $b") }
        }.orEmpty()
        val all = phrases.flatMap { y.query(de, it) } + bases.flatMap { y.query(de, it) } + found
        val terms = all.filter { t -> t.glossaries.any { it.formOf.isEmpty() } } + all.filter { t -> t.glossaries.all { it.formOf.isNotEmpty() } }
        val seen = HashSet<String>()
        return terms.flatMap { t ->
            t.glossaries.filter { seen.add("${t.expression}|${t.reading}|${it.dict}|${it.senses.firstOrNull()}") }.map { g ->
                DictEntry(t.expression, g.tags, listOf(t.reading.takeIf { it.isNotBlank() && it != t.expression }, g.dict)
                    .filterNotNull().joinToString(" · "), t.ipa.joinToString(", "), g.senses.map { Sense(it, "", emptyList()) })
            }
        }
    }

    private fun offline(surface: String, key: String?, lemma: String?): List<DictEntry> {
        val db = open() ?: return emptyList()
        val written = listOfNotNull(surface, key, lemma).map { it.lowercase() }.distinct()
        // Dictionary forms of what is written ("ging" -> "gehen", "ruft an" -> "anrufen").
        val viaForms = LinkedHashSet<String>()
        db.rawQuery("select lemma from forms where form in (${written.joinToString(",") { "?" }})", written.toTypedArray()).use { c ->
            while (c.moveToNext()) viaForms += c.getString(0).lowercase()
        }
        val order = (listOfNotNull(lemma?.lowercase()) + viaForms + written).distinct()
        val out = ArrayList<Pair<Int, DictEntry>>()
        db.rawQuery("select word, lower, pos, head, ipa, senses from entries where lower in (${order.joinToString(",") { "?" }})",
            order.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val rank = order.indexOf(c.getString(1))
                out += rank to DictEntry(c.getString(0), c.getString(2), c.getString(3), c.getString(4), senses(c.getString(5)))
            }
        }
        // Same rank: the written capitalisation first (a noun "Halt" vs the particle "halt").
        return out.sortedWith(compareBy({ it.first }, { if (it.second.word == surface || it.second.word == lemma) 0 else 1 }))
            .map { it.second }
    }

    private fun senses(json: String): List<Sense> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val s = a.getJSONArray(i)
            val ex = s.getJSONArray(2)
            Sense(s.getString(0), s.getString(1), (0 until ex.length()).map { j -> ex.getJSONArray(j).let { it.getString(0) to it.getString(1) } })
        }
    }

    /** Wiktionary's definitions of [term] in German, from the cache or asked once. */
    private fun onlineEntries(term: String): List<DictEntry> {
        cache.rawQuery("select json from online where term = ?", arrayOf(term)).use { c ->
            if (c.moveToFirst()) return fromJson(c.getString(0))
        }
        val entries = runCatching { fetchOnline(term) }.getOrElse {
            Log.w("kumapie", "Wiktionary $term: $it")
            return emptyList() // offline or failed: not cached, asked again next time
        }
        cache.execSQL("insert or replace into online values (?, ?, ?)", arrayOf<Any>(term, toJson(entries), System.currentTimeMillis()))
        return entries
    }

    private fun fetchOnline(term: String): List<DictEntry> {
        val url = "https://en.wiktionary.org/api/rest_v1/page/definition/" + URLEncoder.encode(term, "UTF-8").replace("+", "%20")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", AssetWorker.USER_AGENT)
        try {
            if (conn.responseCode == 404) return emptyList()
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val de = o.optJSONArray("de") ?: return emptyList()
            return (0 until de.length()).map { i ->
                val e = de.getJSONObject(i)
                val defs = e.getJSONArray("definitions")
                DictEntry(term, e.optString("partOfSpeech").lowercase(), term, "", (0 until defs.length()).mapNotNull { j ->
                    val d = defs.getJSONObject(j)
                    val gloss = plain(d.optString("definition"))
                    val ex = d.optJSONArray("parsedExamples")
                    val examples = if (ex == null) emptyList() else (0 until minOf(ex.length(), 2)).mapNotNull { k ->
                        val x = ex.getJSONObject(k)
                        val en = plain(x.optString("translation"))
                        if (en.isBlank()) null else plain(x.optString("example")) to en
                    }
                    if (gloss.isBlank()) null else Sense(gloss, "", examples)
                }, online = true)
            }.filter { it.senses.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    private fun plain(html: String) = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()

    private fun toJson(entries: List<DictEntry>) = JSONArray(entries.map { e ->
        JSONObject().put("word", e.word).put("pos", e.pos).put("head", e.head).put("ipa", e.ipa)
            .put("senses", JSONArray(e.senses.map { s -> JSONArray().put(s.gloss).put(s.tags)
                .put(JSONArray(s.examples.map { JSONArray().put(it.first).put(it.second) })) }))
    }).toString()

    private fun fromJson(json: String): List<DictEntry> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val e = a.getJSONObject(i)
            DictEntry(e.getString("word"), e.getString("pos"), e.getString("head"), e.getString("ipa"),
                senses(e.getJSONArray("senses").toString()), online = true)
        }
    }

    // ------------------------------------------------------------------ recordings

    /** A human recording of exactly this written word: the saved file, else its Wikimedia Commons URL, else null. */
    fun recording(surface: String): String? {
        val saved = recordingFile(surface)
        if (saved.exists()) return saved.path
        val db = open() ?: return null
        val path = runCatching {
            db.rawQuery("select file from sounds where word = ?", arrayOf(surface.lowercase())).use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: return null
        return COMMONS_MP3 + path + "/" + path.substringAfterLast('/') + ".mp3"
    }

    /** Keeps a recording that played from Commons, so it plays offline next time. */
    suspend fun keepRecording(surface: String, url: String) = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext
        val out = recordingFile(surface)
        if (out.exists()) return@withContext
        runCatching {
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".part")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", AssetWorker.USER_AGENT)
            try {
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
            tmp.renameTo(out)
        }.onFailure { Log.w("kumapie", "recording $surface: $it") }
    }

    private fun recordingFile(surface: String) = File(recordings, hash(surface.lowercase()) + ".mp3")

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    companion object {
        const val URL_FILE = "https://github.com/Pedrubik2000/kumapie-tv/releases/download/dictionary/de-en.sqlite.gz"
        const val ATTRIBUTION = "Meanings: English Wiktionary (CC BY-SA 4.0), extracted by kaikki.org. " +
            "Recordings: Wikimedia Commons, each under its own free license."
        private const val COMMONS_MP3 = "https://upload.wikimedia.org/wikipedia/commons/transcoded/"
        private const val WORK = "dictionary"
    }
}

/** Downloads the gzipped dictionary from the repo's "dictionary" release and puts it in place. */
class DictionaryWorker(context: Context, params: WorkerParameters) : AssetWorker(context, params) {
    private val dictionary = Dictionary(context)
    override val what = "the dictionary"
    override val notificationId = 998

    override suspend fun run() {
        val dir = dictionary.file.parentFile!!.apply { mkdirs() }
        val gz = File(dir, "de-en.sqlite.gz.part")
        fetch(Dictionary.URL_FILE, gz)
        report("Unpacking…", 0.95f)
        val tmp = File(dir, "de-en.sqlite.tmp")
        GZIPInputStream(gz.inputStream().buffered(1 shl 16)).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        runCatching { SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).close() }
            .onFailure { tmp.delete(); gz.delete(); throw IOException("the download is not the dictionary") }
        dictionary.close()
        if (!tmp.renameTo(dictionary.file)) {
            dictionary.file.delete()
            if (!tmp.renameTo(dictionary.file)) throw IOException("couldn't move the dictionary into place")
        }
        gz.delete()
    }
}
