package io.github.pedrubik2000.kumapie.mobile.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.i18n.tr
import android.net.Uri
import android.util.Log
import io.github.pedrubik2000.kumapie.data.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * hoshidicts (bee-san/hoshidicts, GPL-3.0) through JNI: tools/hoshidicts/build.sh builds libhoshidicts_jni.so from
 * tools/hoshidicts/jni. Results come back as JSON.
 */
internal object Hoshidicts {
    init { System.loadLibrary("hoshidicts_jni") }
    @JvmStatic external fun nativeImport(source: String, outDir: String): String
    @JvmStatic external fun nativeOpen(): Long
    @JvmStatic external fun nativeClose(handle: Long)
    /** kind: 0 term, 1 frequency, 2 pitch, 3 kanji. */
    @JvmStatic external fun nativeAdd(handle: Long, path: String, kind: Int): Boolean
    @JvmStatic external fun nativeQuery(handle: Long, word: String): String
    @JvmStatic external fun nativeLookup(handle: Long, text: String, max: Int): String
    @JvmStatic external fun nativeStyles(handle: Long): String
    @JvmStatic external fun nativeMedia(handle: Long, dict: String, path: String): ByteArray?
}

/**
 * The Yomitan dictionaries imported into kumapie (kumapie_languages_plan.md "Yomitan dictionaries"), per language,
 * in the user's order: what they hold (term / frequency / pitch / kanji), on or off. Each lives in its own folder
 * under the app's external files; the list is a small JSON file next to them.
 */
class YomitanDictionaries private constructor(private val context: Context) {
    private val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "yomitan").apply { mkdirs() }
    private val listFile = File(base, "dictionaries.json")
    private val handles = HashMap<String, Long>()
    /** One import, download or update at a time (they share the temporary import folder). */
    private val work = kotlinx.coroutines.sync.Mutex()

    data class Dict(
        val folder: String, val title: String, val lang: String, val revision: String,
        val terms: Long, val freq: Long, val pitch: Long, val kanji: Long, val enabled: Boolean = true,
        val updatable: Boolean = false, val indexUrl: String? = null, val downloadUrl: String? = null,
        /** The popup's chip: "Bilingual", "Monolingual", "Freq"... (from a "[Group] name.zip" file name), or "". */
        val group: String = "",
    ) {
        val kinds: String get() = listOfNotNull("meanings".takeIf { terms > 0 }, "frequency".takeIf { freq > 0 },
            "pitch".takeIf { pitch > 0 }, "kanji".takeIf { kanji > 0 }).joinToString(" · ")
    }

    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Dict>> = _all

    fun of(lang: Lang): List<Dict> = _all.value.filter { it.lang == lang.code }

    /** Every .zip under a folder the user picked (subfolders too), sorted by folder and name: for [import]. */
    fun zipsIn(tree: Uri): List<Uri> {
        val out = ArrayList<Pair<String, Uri>>()
        fun walk(docId: String, path: String) {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            context.contentResolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1)
                    if (c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) walk(id, "$path$name/")
                    else if (name.endsWith(".zip", true)) out += "$path$name" to android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
        walk(android.provider.DocumentsContract.getTreeDocumentId(tree), "")
        return out.sortedBy { it.first }.map { it.second }
    }

    /** Imports Yomitan zips picked by the user for [lang]. Answers one line per file. */
    suspend fun import(uris: List<Uri>, lang: Lang, progress: (String) -> Unit): List<String> = work.withLock { withContext(Dispatchers.IO) {
        uris.map { uri ->
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "dictionary.zip"
            runCatching {
                progress(tr("Importing %s…", name))
                val zip = File(context.cacheDir, "yomitan-import.zip")
                context.contentResolver.openInputStream(uri)?.use { input -> zip.outputStream().use { input.copyTo(it) } }
                    ?: throw IOException(tr("can't read %s", name))
                importFile(zip, lang, null, groupOf(name))
            }.getOrElse { "$name: ${it.message}" }
        }
    } }

    /**
     * Imports one zip (then deletes it). A dictionary with the same title, or [replacing] (an update, whose title may
     * have changed: kty-de-en became wty-de-en), is replaced in place: same position, same on/off.
     */
    private fun importFile(zip: File, lang: Lang, replacing: Dict?, group: String = replacing?.group ?: ""): String {
        val tmp = File(base, ".import").apply { deleteRecursively(); mkdirs() }
        try {
            val r = JSONObject(Hoshidicts.nativeImport(zip.path, tmp.path))
            if (!r.optBoolean("ok")) throw IOException(r.optString("error").ifBlank { tr("not a Yomitan dictionary") })
            val folder = r.getString("folder")
            synchronized(this) {
                closeAll()
                replacing?.let { if (it.folder != folder) File(base, it.folder).deleteRecursively() }
                File(base, folder).deleteRecursively()
                if (!File(tmp, folder).renameTo(File(base, folder))) throw IOException(tr("couldn't move %s into place", folder))
                val title = r.getString("title")
                val wty = Regex("""^wty-(\w+)-(\w+)(-gloss)?$""").find(title)?.groupValues
                val grp = group.ifBlank { if (wty == null) "" else if (wty[1] == wty[2]) "Monolingual" else "Bilingual" }
                val d = Dict(folder, title, lang.code, r.optString("revision"), r.optLong("terms"),
                    r.optLong("freq"), r.optLong("pitch"), r.optLong("kanji"), true, r.optBoolean("isUpdatable"),
                    r.optString("indexUrl").takeIf { it.isNotBlank() && it != "null" },
                    r.optString("downloadUrl").takeIf { it.isNotBlank() && it != "null" }, grp)
                val old = _all.value.indexOfFirst { it.folder == folder || it.folder == replacing?.folder }
                save(if (old >= 0) _all.value.toMutableList().also { it[old] = d.copy(enabled = it[old].enabled) }
                     else _all.value + d)
            }
            return tr("%1\$s %2\$s: imported (%3\$s)", r.getString("title"), r.optString("revision"), listOf(r.optLong("terms") to tr("words"),
                r.optLong("freq") to tr("frequencies"), r.optLong("pitch") to tr("pronunciations")).filter { it.first > 0 }
                .joinToString { "${it.first} ${it.second}" })
        } finally {
            zip.delete()
            tmp.deleteRecursively()
        }
    }

    /** Downloads [url] (a dictionary zip) and imports it for [lang]. */
    private fun install(url: String, lang: Lang, replacing: Dict?, progress: (String) -> Unit): String {
        val zip = File(context.cacheDir, "yomitan-download.zip")
        download(url, zip) { mb -> progress(tr("Downloading… %d MB", mb)) }
        progress(tr("Importing…"))
        return importFile(zip, lang, replacing, replacing?.group ?: "")
    }

    /** The starter set for [lang] ([RECOMMENDED]): installs those not there yet. Answers one line per dictionary. */
    suspend fun installRecommended(lang: Lang, progress: (String) -> Unit): List<String> = work.withLock { withContext(Dispatchers.IO) {
        (RECOMMENDED["${lang.code}-${Lang.speaker}"] ?: RECOMMENDED[lang.code]).orEmpty().map { indexUrl ->
            runCatching {
                val index = JSONObject(fetchText(indexUrl))
                val title = index.getString("title")
                if (of(lang).any { it.title == title || it.indexUrl == indexUrl }) return@runCatching tr("%s: already there", title)
                progress("$title…")
                install(index.getString("downloadUrl"), lang, null) { progress("$title: $it") }
            }.getOrElse { "${indexUrl.substringAfterLast('/').substringBefore('?')}: ${it.message}" }
        }
    } }

    /**
     * Checks every updatable dictionary (Yomitan's isUpdatable + indexUrl + downloadUrl, as Hachidori does): a
     * different revision is downloaded, imported and swapped in; a failed update keeps the working one. Answers one
     * line per updated or failed dictionary.
     */
    suspend fun update(progress: (String) -> Unit = {}): List<String> = work.withLock { withContext(Dispatchers.IO) {
        _all.value.filter { it.updatable && it.indexUrl != null }.mapNotNull { d ->
            runCatching {
                progress(tr("Checking %s…", d.title))
                val index = JSONObject(fetchText(d.indexUrl!!))
                val revision = index.optString("revision")
                if (revision.isBlank() || revision == d.revision) return@runCatching null
                val url = index.optString("downloadUrl").ifBlank { d.downloadUrl ?: throw IOException(tr("no download link")) }
                install(url, Lang.of(d.lang), d) { progress("${d.title}: $it") }
            }.getOrElse { tr("%1\$s: update failed (%2\$s)", d.title, it.message) }
        }
    } }

    private fun fetchText(url: String): String = open(url).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun download(url: String, out: File, progress: (Long) -> Unit) {
        open(url).use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                var shown = -1L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    total += n
                    if (total / 1_000_000 != shown) { shown = total / 1_000_000; progress(shown) }
                }
            }
        }
    }

    private fun open(url: String): java.io.InputStream {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", AssetWorker.USER_AGENT)
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
        return conn.inputStream
    }

    @Synchronized fun setEnabled(folder: String, on: Boolean) = edit { l -> l.map { if (it.folder == folder) it.copy(enabled = on) else it } }

    /** Moves a dictionary one place up (-1) or down (+1) among its language's dictionaries. */
    @Synchronized fun move(folder: String, by: Int) = edit { l ->
        val d = l.first { it.folder == folder }
        val same = l.filter { it.lang == d.lang }
        val i = same.indexOf(d)
        val j = (i + by).coerceIn(0, same.lastIndex)
        if (i == j) l else {
            val reordered = same.toMutableList().apply { removeAt(i); add(j, d) }
            var k = 0
            l.map { if (it.lang == d.lang) reordered[k++] else it }
        }
    }

    @Synchronized fun delete(folder: String) {
        closeAll()
        File(base, folder).deleteRecursively()
        save(_all.value.filter { it.folder != folder })
    }

    /** Every term entry for [word] in [lang]'s enabled dictionaries, in their order. */
    fun query(lang: Lang, word: String): List<Term> = synchronized(this) {
        val h = handle(lang) ?: return emptyList()
        parseTerms(JSONArray(Hoshidicts.nativeQuery(h, word)))
    }

    /** Yomitan's scan from the start of [text] (Japanese deinflection, longest match first): (matched text, term). */
    fun scan(lang: Lang, text: String, max: Int = 16): List<Pair<String, Term>> = synchronized(this) {
        val h = handle(lang) ?: return emptyList()
        val a = JSONArray(Hoshidicts.nativeLookup(h, text, max))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getString("matched") to parseTerms(JSONArray().put(it.getJSONObject("term"))).first() } }
    }

    /** Each enabled dictionary's styles.css, by dictionary title. */
    fun styles(lang: Lang): Map<String, String> = synchronized(this) {
        val h = handle(lang) ?: return emptyMap()
        val o = JSONObject(Hoshidicts.nativeStyles(h))
        o.keys().asSequence().associateWith { o.getString(it) }
    }

    /** A dictionary's media file (an image in its structured content), or null. */
    fun media(lang: Lang, dict: String, path: String): ByteArray? = synchronized(this) {
        handle(lang)?.let { Hoshidicts.nativeMedia(it, dict, path) }
    }

    /** The group a dictionary belongs to, by its title. */
    fun groupOf(lang: Lang, title: String): String =
        of(lang).firstOrNull { it.title == title }?.group.orEmpty().let { if (it.startsWith("JA-JA")) "Monolingual" else it }

    private fun handle(lang: Lang): Long? {
        handles[lang.code]?.let { return it }
        val dicts = of(lang).filter { it.enabled }
        if (dicts.none { it.terms > 0 }) return null
        val h = Hoshidicts.nativeOpen()
        for (d in dicts) {
            val dir = File(base, d.folder).path
            if (d.terms > 0) Hoshidicts.nativeAdd(h, dir, 0)
            if (d.freq > 0) Hoshidicts.nativeAdd(h, dir, 1)
            if (d.pitch > 0) Hoshidicts.nativeAdd(h, dir, 2)
            if (d.kanji > 0) Hoshidicts.nativeAdd(h, dir, 3)
        }
        handles[lang.code] = h
        return h
    }

    private fun closeAll() {
        handles.values.forEach { Hoshidicts.nativeClose(it) }
        handles.clear()
    }

    private fun edit(change: (List<Dict>) -> List<Dict>) {
        closeAll()
        save(change(_all.value))
    }

    private fun save(list: List<Dict>) {
        _all.value = list
        listFile.writeText(JSONArray(list.map {
            JSONObject().put("folder", it.folder).put("title", it.title).put("lang", it.lang).put("revision", it.revision)
                .put("terms", it.terms).put("freq", it.freq).put("pitch", it.pitch).put("kanji", it.kanji)
                .put("enabled", it.enabled).put("updatable", it.updatable).put("indexUrl", it.indexUrl).put("downloadUrl", it.downloadUrl)
                .put("group", it.group)
        }).toString())
    }

    private fun load(): List<Dict> = runCatching {
        val a = JSONArray(listFile.readText())
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                Dict(it.getString("folder"), it.getString("title"), it.getString("lang"), it.optString("revision"),
                    it.optLong("terms"), it.optLong("freq"), it.optLong("pitch"), it.optLong("kanji"), it.optBoolean("enabled", true),
                    it.optBoolean("updatable"), it.optString("indexUrl").takeIf { s -> s.isNotBlank() },
                    it.optString("downloadUrl").takeIf { s -> s.isNotBlank() }, it.optString("group"))
            }
        }.filter { File(base, it.folder).isDirectory }
    }.onFailure { if (listFile.exists()) Log.w("kumapie", "yomitan list: $it") }.getOrDefault(emptyList())

    /** A headword with what each dictionary says about it. */
    data class Term(val expression: String, val reading: String, val glossaries: List<Glossary>,
                    val frequencies: List<Pair<String, String>>, val pitches: List<Pair<String, List<Int>>>,
                    /** IPA transcriptions from IPA dictionaries (wty-de-en-ipa: "/fʁaɪ̯/"). */
                    val ipa: List<String> = emptyList())

    /** One dictionary's entry: its senses as plain text (structured content flattened), and its tags. */
    data class Glossary(val dict: String, val senses: List<String>, val tags: String, val formOf: List<String> = emptyList(),
                        /** The glossary as the dictionary has it (JSON: strings and structured content), for HTML. */
                        val raw: String = "")

    companion object {
        /** "[Bilingual, onomatopoeia] Onomatoproject.zip" -> "Bilingual"; no brackets -> "". */
        fun groupOf(fileName: String): String =
            Regex("""^\[([^\]]+)]""").find(fileName.substringAfterLast('/'))?.groupValues?.get(1)?.split(',', '・')?.first()?.trim()
                ?.let { if (it.startsWith("JA-JA")) "Monolingual" else it }.orEmpty()

        @Volatile private var instance: YomitanDictionaries? = null

        /** One per process: the app and the update job share the list and the open dictionaries. */
        fun get(context: Context): YomitanDictionaries =
            instance ?: synchronized(this) { instance ?: YomitanDictionaries(context.applicationContext).also { instance = it } }

        /**
         * Starter sets per language (index.json URLs; updatable): German = wty-de-en (Wiktionary, the source of the old
         * de-en.sqlite) + its IPA. Japanese comes with step 3 (Jitendex, JMnedict, a frequency and a pitch dictionary).
         */
        private const val WTY = "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/index"
        val RECOMMENDED = mapOf(
            // German-German last: the monolingual definition for mining and the fallback when wty-de-en has nothing.
            "de" to listOf("$WTY/wty-de-en-index.json?download=true", "$WTY/wty-de-en-ipa-index.json?download=true",
                "$WTY/wty-de-de-index.json?download=true"),
            // English for Pedro: English meanings and IPA. For a Spanish speaker (the parents): Spanish meanings per
            // sense (gloss), the Spanish Wiktionary's English entries, IPA - 93% of their episodes' words, 99% heard.
            "en-es" to listOf("$WTY/wty-en-es-gloss-index.json?download=true", "$WTY/wty-en-es-index.json?download=true",
                "$WTY/wty-en-es-ipa-index.json?download=true", "$WTY/wty-en-en-index.json?download=true"),
            "en" to listOf("$WTY/wty-en-en-index.json?download=true", "$WTY/wty-en-en-ipa-index.json?download=true"),
        )

        fun parseTerms(a: JSONArray): List<Term> = (0 until a.length()).map { i ->
            val t = a.getJSONObject(i)
            val g = t.getJSONArray("glossaries")
            val f = t.getJSONArray("frequencies")
            val p = t.getJSONArray("pitches")
            Term(t.getString("expression"), t.getString("reading"),
                (0 until g.length()).map { j ->
                    g.getJSONObject(j).let { e ->
                        Glossary(e.getString("dict"), senses(e.getString("glossary")),
                            listOf(e.optString("termTags"), e.optString("defTags")).filter { it.isNotBlank() }.joinToString(" "),
                            formOf(e.getString("glossary")), e.getString("glossary"))
                    }
                },
                (0 until f.length()).map { j ->
                    f.getJSONObject(j).let { e ->
                        val v = e.getJSONArray("values")
                        e.getString("dict") to (0 until v.length()).joinToString(", ") { k ->
                            v.getJSONObject(k).let { x -> x.optString("display").ifBlank { x.optInt("value").toString() } }
                        }
                    }
                },
                (0 until p.length()).map { j ->
                    p.getJSONObject(j).let { e ->
                        val pos = e.getJSONArray("positions")
                        e.getString("dict") to (0 until pos.length()).map { pos.getInt(it) }
                    }
                },
                (0 until p.length()).flatMap { j ->
                    p.getJSONObject(j).optJSONArray("ipa")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                }.distinct())
        }

        /**
         * A Yomitan glossary (a JSON array of strings and structured content) as plain-text senses: each list item of
         * structured content is one sense (kty and Jitendex put an entry's senses in one list), else each glossary
         * item is one. Etymology/notes (`details`), backlinks, furigana readings and images are left out.
         */
        fun senses(glossary: String): List<String> = runCatching {
            val a = JSONArray(glossary)
            (0 until a.length()).flatMap { i ->
                val item = a.get(i)
                // A Yomitan form-of item: [dictionary form, [inflection rules]] -> "second-person singular present of glauben".
                deinflection(item)?.let { (base, rules) -> return@flatMap listOf("${rules.joinToString(", ")} of $base") }
                val items = ArrayList<Any?>().also { listItems(item, it) }
                (items.ifEmpty { listOf(item) }).mapNotNull { node ->
                    val sb = StringBuilder()
                    flatten(node, sb)
                    sb.toString().lines().map { it.trim().replace(Regex(" {2,}"), " ").replace(Regex(" ([;,.)])"), "$1") }
                        .filter { it.isNotEmpty() }.joinToString("; ").ifBlank { null }
                }
            }
        }.getOrDefault(listOf(glossary))

        /** The dictionary forms a glossary's form-of items point to ("glaubst" -> "glauben"). */
        fun formOf(glossary: String): List<String> = runCatching {
            val a = JSONArray(glossary)
            (0 until a.length()).mapNotNull { deinflection(a.get(it))?.first }.distinct()
        }.getOrDefault(emptyList())

        private fun deinflection(item: Any?): Pair<String, List<String>>? {
            if (item !is JSONArray || item.length() != 2 || item.opt(0) !is String || item.opt(1) !is JSONArray) return null
            val rules = item.getJSONArray(1)
            return item.getString(0) to (0 until rules.length()).map { rules.optString(it) }
        }

        private val blocks = setOf("div", "p", "li", "ol", "ul", "tr", "table", "br", "summary")
        private val skipped = setOf("rt", "rp", "img", "details")
        private val skippedData = setOf("backlink", "preamble", "attribution")

        private fun isSkipped(node: JSONObject) = node.optString("tag") in skipped ||
            node.optJSONObject("data")?.optString("content") in skippedData

        /** The outermost `li` nodes under [node] (not inside skipped parts). */
        private fun listItems(node: Any?, out: MutableList<Any?>) {
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) listItems(node.get(i), out)
                is JSONObject -> when {
                    isSkipped(node) -> {}
                    node.optString("tag") == "li" -> out += node
                    else -> listItems(node.opt("content"), out)
                }
            }
        }

        private fun flatten(node: Any?, sb: StringBuilder) {
            when (node) {
                is String -> sb.append(node)
                is JSONArray -> for (i in 0 until node.length()) flatten(node.get(i), sb)
                is JSONObject -> when (node.optString("type")) {
                    "text" -> sb.append(node.optString("text"))
                    "image" -> {}
                    "structured-content" -> flatten(node.opt("content"), sb)
                    else -> {
                        if (isSkipped(node)) return
                        val block = node.optString("tag") in blocks
                        if (block && sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                        flatten(node.opt("content"), sb)
                        if (block && sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                        // Tag labels are separate spans ("vi" "vt" "rare"): keep them apart in plain text.
                        if (!block && sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
                    }
                }
            }
        }
    }
}

/** Weekly, on an unmetered network: updates every updatable Yomitan dictionary ([YomitanDictionaries.update]). */
class YomitanUpdateWorker(context: android.content.Context, params: androidx.work.WorkerParameters) :
    androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val lines = YomitanDictionaries.get(applicationContext).update()
        lines.forEach { Log.i("kumapie", "yomitan update: $it") }
        return Result.success()
    }

    companion object {
        fun schedule(context: android.content.Context) {
            val request = androidx.work.PeriodicWorkRequestBuilder<YomitanUpdateWorker>(7, java.util.concurrent.TimeUnit.DAYS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.UNMETERED).build())
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("yomitan-update", androidx.work.ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
