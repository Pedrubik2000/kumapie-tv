package io.github.pedrubik2000.kumapie.mobile.lang

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.pedrubik2000.kumapie.data.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
}

/**
 * The Yomitan dictionaries imported into kumapie (kumapie_languages_plan.md "Yomitan dictionaries"), per language,
 * in the user's order: what they hold (term / frequency / pitch / kanji), on or off. Each lives in its own folder
 * under the app's external files; the list is a small JSON file next to them.
 */
class YomitanDictionaries(private val context: Context) {
    private val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "yomitan").apply { mkdirs() }
    private val listFile = File(base, "dictionaries.json")
    private val handles = HashMap<String, Long>()

    data class Dict(
        val folder: String, val title: String, val lang: String, val revision: String,
        val terms: Long, val freq: Long, val pitch: Long, val kanji: Long, val enabled: Boolean = true,
        val updatable: Boolean = false, val indexUrl: String? = null, val downloadUrl: String? = null,
    ) {
        val kinds: String get() = listOfNotNull("meanings".takeIf { terms > 0 }, "frequency".takeIf { freq > 0 },
            "pitch".takeIf { pitch > 0 }, "kanji".takeIf { kanji > 0 }).joinToString(" · ")
    }

    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Dict>> = _all

    fun of(lang: Lang): List<Dict> = _all.value.filter { it.lang == lang.code }

    /** Imports Yomitan zips for [lang]; a dictionary with a title already there is replaced in place. Answers one line per file. */
    suspend fun import(uris: List<Uri>, lang: Lang, progress: (String) -> Unit): List<String> = withContext(Dispatchers.IO) {
        uris.map { uri ->
            runCatching {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "dictionary.zip"
                progress("Importing $name…")
                val zip = File(context.cacheDir, "yomitan-import.zip")
                context.contentResolver.openInputStream(uri)?.use { input -> zip.outputStream().use { input.copyTo(it) } }
                    ?: throw IOException("can't read $name")
                val tmp = File(base, ".import").apply { deleteRecursively(); mkdirs() }
                val r = JSONObject(Hoshidicts.nativeImport(zip.path, tmp.path))
                zip.delete()
                if (!r.optBoolean("ok")) throw IOException(r.optString("error").ifBlank { "not a Yomitan dictionary" })
                val folder = r.getString("folder")
                synchronized(this@YomitanDictionaries) {
                    closeAll()
                    File(base, folder).deleteRecursively()
                    if (!File(tmp, folder).renameTo(File(base, folder))) throw IOException("couldn't move $folder into place")
                    val d = Dict(folder, r.getString("title"), lang.code, r.optString("revision"), r.optLong("terms"),
                        r.optLong("freq"), r.optLong("pitch"), r.optLong("kanji"), true, r.optBoolean("isUpdatable"),
                        r.optString("indexUrl").takeIf { it.isNotBlank() && it != "null" },
                        r.optString("downloadUrl").takeIf { it.isNotBlank() && it != "null" })
                    val old = _all.value.indexOfFirst { it.folder == folder }
                    save(if (old >= 0) _all.value.toMutableList().also { it[old] = d.copy(enabled = it[old].enabled) } else _all.value + d)
                }
                tmp.deleteRecursively()
                "${r.getString("title")}: imported (${listOf(r.optLong("terms") to "words", r.optLong("freq") to "frequencies",
                    r.optLong("pitch") to "pitch accents").filter { it.first > 0 }.joinToString { "${it.first} ${it.second}" }})"
            }.getOrElse { "${uri.lastPathSegment}: ${it.message}" }
        }
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
        }).toString())
    }

    private fun load(): List<Dict> = runCatching {
        val a = JSONArray(listFile.readText())
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                Dict(it.getString("folder"), it.getString("title"), it.getString("lang"), it.optString("revision"),
                    it.optLong("terms"), it.optLong("freq"), it.optLong("pitch"), it.optLong("kanji"), it.optBoolean("enabled", true),
                    it.optBoolean("updatable"), it.optString("indexUrl").takeIf { s -> s.isNotBlank() },
                    it.optString("downloadUrl").takeIf { s -> s.isNotBlank() })
            }
        }.filter { File(base, it.folder).isDirectory }
    }.onFailure { if (listFile.exists()) Log.w("kumapie", "yomitan list: $it") }.getOrDefault(emptyList())

    /** A headword with what each dictionary says about it. */
    data class Term(val expression: String, val reading: String, val glossaries: List<Glossary>,
                    val frequencies: List<Pair<String, String>>, val pitches: List<Pair<String, List<Int>>>)

    /** One dictionary's entry: its senses as plain text (structured content flattened), and its tags. */
    data class Glossary(val dict: String, val senses: List<String>, val tags: String, val formOf: List<String> = emptyList())

    companion object {
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
                            formOf(e.getString("glossary")))
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
                })
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
                    sb.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ").ifBlank { null }
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
                    }
                }
            }
        }
    }
}
