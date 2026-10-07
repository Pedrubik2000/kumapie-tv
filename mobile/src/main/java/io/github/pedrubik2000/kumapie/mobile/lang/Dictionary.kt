package io.github.pedrubik2000.kumapie.mobile.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.i18n.tr
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
    /** The Yomitan dictionary's title ("" for Wiktionary online). */
    val dict: String = "",
)

data class Sense(val gloss: String, val tags: String, val examples: List<Pair<String, String>>)

/**
 * German meanings and word recordings without the PC and without paying:
 * - meanings: the imported Yomitan dictionaries ([YomitanDictionaries], wty-de-en: English Wiktionary);
 * - online, for words they don't have: Wiktionary's definition API, every answer kept in a local cache (also "not
 *   found"), so a word is asked for once;
 * - recordings: a small index per language (word -> Wikimedia Commons file; German, English) built monthly by the
 *   repo's Dictionary workflow (tools/dictionary/build.py), downloaded by itself when missing (a few MB each).
 * Wiktionary content is CC BY-SA 4.0 ([ATTRIBUTION]).
 */
class Dictionary(private val context: Context, private val yomitan: YomitanDictionaries? = null) {
    private val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "dictionary")
    /** A language's recordings index (word -> Commons file). */
    fun recordingsFile(lang: String) = File(base, "$lang-recordings.sqlite")
    /** The German one (Settings shows its date). */
    val file get() = recordingsFile("de")
    val isReady: Boolean get() = file.exists()
    init { File(base, "de-en.sqlite").delete() } // the old meanings file (84 MB), replaced by Yomitan dictionaries
    private val recordings = File(base, "recordings")
    private val work get() = WorkManager.getInstance(context)

    private val dbs = HashMap<String, Pair<SQLiteDatabase, Long>>()
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

    /** When the recordings index was built ("2026-10-05"), or null. */
    fun built(): String? = runCatching {
        open()?.rawQuery("select value from meta where key = 'built'", null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** A language's index, opened again when a new download has replaced it. */
    @Synchronized
    private fun open(lang: String = "de"): SQLiteDatabase? {
        val f = recordingsFile(lang)
        if (!f.exists()) return null
        val version = f.lastModified()
        dbs[lang]?.let { (db, v) -> if (db.isOpen && v == version) return db else db.close() }
        return SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).also { dbs[lang] = it to version }
    }

    /** Closes the files so a new download can replace them. */
    @Synchronized
    fun close() {
        dbs.values.forEach { it.first.close() }
        dbs.clear()
    }

    /**
     * Every entry for the word as it appears in the subtitles: [surface] ("Hunde"), its morph key ([key], "rufe an"
     * for a split verb) and spaCy's dictionary form ([lemma]). Online only when the Yomitan dictionaries have
     * nothing (and [online] is allowed).
     */
    suspend fun lookup(surface: String, key: String?, lemma: String?, online: Boolean = true,
                       lang: io.github.pedrubik2000.kumapie.data.Lang = io.github.pedrubik2000.kumapie.data.Lang.GERMAN): List<DictEntry> =
        withContext(Dispatchers.IO) {
            val found = runCatching { yomitan(surface, key, lemma, lang) }.onFailure { Log.w("kumapie", "yomitan: $it") }.getOrDefault(emptyList())
            // Wiktionary online is the German section only.
            if (found.isNotEmpty() || !online || lang != io.github.pedrubik2000.kumapie.data.Lang.GERMAN) return@withContext found
            val term = lemma?.takeIf { it.isNotBlank() } ?: surface
            onlineEntries(term).ifEmpty { if (!term.equals(surface, true)) onlineEntries(surface) else emptyList() }
        }

    /**
     * The imported German Yomitan dictionaries' entries, in this order: a split verb's phrase ("Bescheid sagen"), the
     * word as written ("glaubst": second-person singular present of glauben), then its dictionary forms ("glauben").
     */
    private fun yomitan(surface: String, key: String?, lemma: String?, lang: io.github.pedrubik2000.kumapie.data.Lang): List<DictEntry> {
        val seen = HashSet<String>()
        return yomitanTerms(surface, key, lemma, lang).flatMap { t ->
            t.glossaries.filter { seen.add("${t.expression}|${t.reading}|${it.dict}|${it.senses.firstOrNull()}") }.map { g ->
                DictEntry(t.expression, g.tags, listOf(t.reading.takeIf { it.isNotBlank() && it != t.expression }, g.dict)
                    .filterNotNull().joinToString(" · "), t.ipa.joinToString(", "), g.senses.map { Sense(it, "", emptyList()) }, dict = g.dict)
            }
        }
    }

    /** The same lookup as Yomitan terms (for the popup), in the same order. */
    fun yomitanTerms(surface: String, key: String?, lemma: String?,
                     lang: io.github.pedrubik2000.kumapie.data.Lang = io.github.pedrubik2000.kumapie.data.Lang.GERMAN): List<YomitanDictionaries.Term> {
        val y = yomitan ?: return emptyList()
        val de = lang // the German rules (form-of, split verbs) also fit English phrasal verbs ("give up")
        val words = listOfNotNull(surface, key, lemma).flatMap { listOf(it, it.lowercase()) }.filter { it.isNotBlank() }.distinct()
        val found = words.flatMap { y.query(de, it) }
        // Form-of entries ("glaubst": second-person singular present of glauben) lead to the dictionary form.
        val bases = found.flatMap { t -> t.glossaries.flatMap { it.formOf } }.distinct().filter { it !in words }
        // A split verb's key ("sag bescheid", "rufe an"): the verb's dictionary form with its particle, as a phrase
        // ("Bescheid sagen") or one word ("anrufen").
        val phrases = key?.split(' ')?.takeIf { it.size == 2 }?.let { (verb, particle) ->
            val verbBases = (listOf(verb) + y.query(de, verb).flatMap { t -> t.glossaries.flatMap { it.formOf } }).distinct()
            verbBases.flatMap { b -> listOf(particle + b, "${particle.replaceFirstChar { it.uppercase() }} $b", "$particle $b") }
        }.orEmpty()
        return phrases.flatMap { y.query(de, it) } + found + bases.flatMap { y.query(de, it) }
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

    /** Cached senses: [[gloss, "tag tag", [[german example, english], ...]], ...]. */
    private fun senses(json: String): List<Sense> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val s = a.getJSONArray(i)
            val ex = s.getJSONArray(2)
            Sense(s.getString(0), s.getString(1), (0 until ex.length()).map { j -> ex.getJSONArray(j).let { it.getString(0) to it.getString(1) } })
        }
    }

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
    fun recording(surface: String, lang: String = "de"): String? {
        val saved = recordingFile(surface, lang)
        if (saved.exists()) return saved.path
        val db = open(lang) ?: return null
        val path = runCatching {
            db.rawQuery("select file from sounds where word = ?", arrayOf(surface.lowercase())).use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: return null
        return COMMONS_MP3 + path + "/" + path.substringAfterLast('/') + ".mp3"
    }

    /** Keeps a recording that played from Commons, so it plays offline next time. */
    suspend fun keepRecording(surface: String, url: String, lang: String = "de") = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext
        val out = recordingFile(surface, lang)
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

    /** German ones where they always were; other languages in their own folder. */
    private fun recordingFile(surface: String, lang: String = "de") =
        File(if (lang == "de") recordings else File(recordings, lang), hash(surface.lowercase()) + ".mp3")

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    companion object {
        /** The languages with a recordings index ("dictionary" pre-release: <lang>-recordings.sqlite.gz). */
        val RECORDINGS = listOf("de", "en")
        fun url(lang: String) = "https://github.com/Pedrubik2000/kumapie-tv/releases/download/dictionary/$lang-recordings.sqlite.gz"
        const val ATTRIBUTION = "Meanings: Yomitan dictionaries (wty-de-en: English Wiktionary, CC BY-SA 4.0, via kaikki.org " +
            "and wiktionary-to-yomitan). Recordings: Wikimedia Commons, each under its own free license."
        private const val COMMONS_MP3 = "https://upload.wikimedia.org/wikipedia/commons/transcoded/"
        private const val WORK = "dictionary"
    }
}

/** Downloads the gzipped dictionary from the repo's "dictionary" release and puts it in place. */
class DictionaryWorker(context: Context, params: WorkerParameters) : AssetWorker(context, params) {
    private val dictionary = Dictionary(context)
    override val what = tr("the word recordings list")
    override val notificationId = 998

    override suspend fun run() {
        // Every language, each on its own; the job fails (and retries) only after trying them all.
        val failed = Dictionary.RECORDINGS.mapNotNull { lang -> runCatching { one(lang) }.exceptionOrNull()?.also { Log.w("kumapie", "recordings $lang: $it") } }
        failed.firstOrNull()?.let { throw it }
    }

    private suspend fun one(lang: String) {
        val target = dictionary.recordingsFile(lang)
        val dir = target.parentFile!!.apply { mkdirs() }
        val gz = File(dir, "$lang-recordings.sqlite.gz.part")
        fetch(Dictionary.url(lang), gz)
        report(tr("Unpacking…"), 0.95f)
        val tmp = File(dir, "$lang-recordings.sqlite.tmp")
        GZIPInputStream(gz.inputStream().buffered(1 shl 16)).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        runCatching { SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).close() }
            .onFailure { tmp.delete(); gz.delete(); throw IOException(tr("the download is not the recordings list")) }
        dictionary.close()
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException(tr("couldn't move the dictionary into place"))
        }
        gz.delete()
    }
}
