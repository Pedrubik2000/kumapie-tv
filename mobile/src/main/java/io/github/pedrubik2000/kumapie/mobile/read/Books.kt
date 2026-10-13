package io.github.pedrubik2000.kumapie.mobile.read

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.lang.Japanese
import io.github.pedrubik2000.kumapie.lang.Spaced
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * A book on this device: its paragraphs in one language (kumapie_plan.md, "Readers": the shared base); a manga
 * ([Manga]) also has its [pages], each speech bubble one paragraph, and [dir] holds their images.
 */
class Book(val id: String, val title: String, val lang: Lang, val paragraphs: List<String>, val pages: List<Manga.Page>? = null, val dir: File? = null)

/** A paragraph's piece of text and its known-word key (null between words): the same keys subtitles use. */
data class Piece(val text: String, val key: String?)

/**
 * Books kept on the device, `books/<id>/book.json` ({title, lang, paragraphs, pages for manga}) + `pos.txt` (the
 * paragraph read last) + a manga's `pages/` images. Text, EPUB ([Epub]) and Mokuro manga ([Manga]) give the same paragraphs.
 */
class Books(private val context: Context) {
    private val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "books").apply { mkdirs() }

    fun all(): List<Book> = base.listFiles().orEmpty().mapNotNull { load(it.name) }.sortedBy { it.title.lowercase() }

    fun load(id: String): Book? = runCatching {
        val o = JSONObject(File(base, "$id/book.json").readText())
        val p = o.getJSONArray("paragraphs")
        Book(id, o.getString("title"), Lang.of(o.getString("lang")), (0 until p.length()).map { p.getString(it) },
            o.optJSONArray("pages")?.let(Manga::fromJson), File(base, id))
    }.getOrNull()

    /** A manga page read on the device ([MangaOcr]) is kept in book.json, so it is read once. */
    @Synchronized
    fun setPage(id: String, n: Int, page: Manga.Page) {
        val f = File(base, "$id/book.json")
        val o = JSONObject(f.readText())
        val pages = Manga.fromJson(o.getJSONArray("pages")).toMutableList().also { it[n] = page }
        f.writeText(o.put("pages", Manga.toJson(pages)).toString())
    }

    fun pos(id: String): Int = runCatching { File(base, "$id/pos.txt").readText().trim().toInt() }.getOrDefault(0)
    fun setPos(id: String, at: Int) = runCatching { File(base, "$id/pos.txt").writeText(at.toString()) }

    fun delete(id: String) = File(base, id).deleteRecursively()

    /** An .epub ([Epub]), a .cbz/.zip manga with its .mokuro ([Manga]) or a .txt file (one paragraph per non-blank line) → a book in [lang]. Answers its id. */
    fun import(uri: Uri, lang: Lang): String {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Book"
        if (name.endsWith(".epub", ignoreCase = true)) {
            val epub = Epub.read(bytes)
            if (epub.paragraphs.isEmpty()) error(io.github.pedrubik2000.kumapie.i18n.tr("No text in this EPUB."))
            return add(epub.title.ifBlank { name.substringBeforeLast('.') }, lang, epub.paragraphs)
        }
        if (name.endsWith(".cbz", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true)) {
            val manga = Manga.read(bytes)
            val id = add(manga.title.ifBlank { name.substringBeforeLast('.') }, lang, emptyList(), manga.pages)
            for ((path, data) in manga.images) File(base, "$id/$path").apply { parentFile!!.mkdirs() }.writeBytes(data)
            return id
        }
        return add(name.substringBeforeLast('.'), lang, paragraphs(bytes.toString(Charsets.UTF_8)))
    }

    fun add(title: String, lang: Lang, paragraphs: List<String>, pages: List<Manga.Page>? = null): String {
        val id = "book-" + MessageDigest.getInstance("SHA-1").digest("$title|${lang.code}|${paragraphs.size}|${pages?.size}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
        File(base, id).mkdirs()
        File(base, "$id/book.json").writeText(JSONObject().put("title", title).put("lang", lang.code)
            .put("paragraphs", JSONArray(paragraphs)).apply { if (pages != null) put("pages", Manga.toJson(pages)) }.toString())
        return id
    }

    /**
     * Any text → per paragraph its pieces, joined they give the paragraph back, split as episode cues are: Japanese by
     * Jiten's splitter (`model.words`, else Sudachi), German and English by spaCy (`newepisode.text_segments`).
     */
    suspend fun pieces(library: Library, lang: Lang, paragraphs: List<String>): List<List<Piece>> = withContext(Dispatchers.Default) {
        when (val language = library.languages.of(lang)) {
            is Japanese -> language.model.words(paragraphs).mapIndexed { i, tokens ->
                fill(paragraphs[i], tokens.map { Triple(it.begin, it.surface, it.base.takeIf { _ -> it.isWord }) })
            }
            is Spaced -> {
                if (!com.chaquo.python.Python.isStarted()) com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(context))
                val out = JSONArray(com.chaquo.python.Python.getInstance().getModule("newepisode")
                    .callAttr("text_segments", language.model.dir.path, JSONArray(paragraphs).toString()).toString())
                (0 until out.length()).map { i ->
                    val row = out.getJSONArray(i)
                    (0 until row.length()).map { j -> row.getJSONArray(j).let { Piece(it.getString(0), if (it.isNull(1)) null else it.getString(1)) } }
                }
            }
        }
    }

    companion object {
        private val END = Regex("""[.!?。！？…]+["'“”‘’»«」』)）]*\s*""")

        /** The sentence of [text] around [offset] (a book paragraph can hold many): the card's Sentence. */
        fun sentenceAt(text: String, offset: Int): String {
            var start = 0
            for (m in END.findAll(text)) {
                val end = m.range.last + 1
                if (end > offset) return text.substring(start, end).trim()
                start = end
            }
            return text.substring(start).trim()
        }

        fun paragraphs(text: String): List<String> = text.removePrefix("\uFEFF").replace("\r", "").lines().map { it.trim() }.filter { it.isNotEmpty() }

        /** Tokens (offset, surface, key) → pieces covering all of [text], the gaps between tokens as plain pieces. */
        fun fill(text: String, tokens: List<Triple<Int, String, String?>>): List<Piece> {
            val out = ArrayList<Piece>()
            var pos = 0
            for ((at, surface, key) in tokens.sortedBy { it.first }) {
                if (at < pos || at + surface.length > text.length) continue
                if (at > pos) out += Piece(text.substring(pos, at), null)
                out += Piece(surface, key)
                pos = at + surface.length
            }
            if (pos < text.length) out += Piece(text.substring(pos), null)
            return out
        }
    }
}
