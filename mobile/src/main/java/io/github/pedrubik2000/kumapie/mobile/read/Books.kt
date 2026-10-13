package io.github.pedrubik2000.kumapie.mobile.read

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.pedrubik2000.kumapie.i18n.tr
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

    fun all(): List<Book> = base.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { load(it.name) }.sortedBy { it.title.lowercase() }

    fun has(id: String) = File(base, "$id/book.json").exists()

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

    // --- The PC (dojo-feed's /api/sync/<owner>/books, book_sync.py): a book added here goes up at the next sync
    // ([io.github.pedrubik2000.kumapie.mobile.local.Sync]); one added on another device comes down as its book.json
    // only, its pages read from the PC when shown ([ensurePage]) or all at once ([download]).

    private val synced = File(base, ".synced")

    fun onPc(id: String) = runCatching { id in synced.readLines() }.getOrDefault(false)
    @Synchronized fun markOnPc(id: String) { if (!onPc(id)) synced.appendText("$id\n") }

    /** The book's folder on the PC, or null (no PC set up, or the PC doesn't have it). */
    fun remote(id: String): String? {
        val s = io.github.pedrubik2000.kumapie.data.Settings(context)
        return if (s.server.isBlank() || s.owner.isBlank() || !onPc(id)) null else "${s.server}/api/sync/${s.owner}/books/$id"
    }

    /** The pages not on this device (read from the PC when shown). */
    fun missing(book: Book) = book.pages.orEmpty().filter { !File(base, "${book.id}/${it.image}").exists() }

    /** A page's picture: the file here, else its address on the PC (a volume read without its own copy). */
    fun picture(book: Book, image: String): Any? = File(base, "${book.id}/$image").takeIf { it.exists() } ?: remote(book.id)?.let { "$it/$image" }

    /** Page [image] of [id] here, fetched from the PC when it isn't. */
    fun ensurePage(id: String, image: String): File {
        val f = File(base, "$id/$image")
        if (!f.exists()) get("${remote(id) ?: error(tr("This page isn't on this device."))}/$image", f)
        return f
    }

    /** All of a volume's pages here, so it reads without the PC. */
    suspend fun download(book: Book, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val todo = missing(book)
        todo.forEachIndexed { i, p -> progress(tr("Downloading page %d of %d…", i + 1, todo.size)); ensurePage(book.id, p.image) }
    }

    /** "Delete here": only the pages go (the volume stays and reads from the PC); not on the PC: all of it. */
    fun removeCopy(id: String) { if (onPc(id)) File(base, "$id/pages").deleteRecursively() else delete(id) }

    /** "Delete everywhere": off the PC (to its archive), then here; the other devices follow at their sync. */
    fun deleteEverywhere(id: String) {
        remote(id)?.let { url ->
            val c = java.net.URL("$url/delete").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000
            c.outputStream.use { }
            if (c.responseCode != 200) throw java.io.IOException("delete: HTTP ${c.responseCode}")
        }
        delete(id)
    }

    /** Another device's book from the PC ([url] = its folder there): its book.json, which lists it here. */
    fun fetchBook(url: String, id: String) {
        get("$url/book.json", File(base, "$id/book.json"))
        markOnPc(id)
    }

    private fun get(url: String, out: File) {
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000 }
        if (c.responseCode != 200) throw java.io.IOException("${out.name}: HTTP ${c.responseCode}")
        val part = File(out.path + ".part").apply { parentFile!!.mkdirs() }
        c.inputStream.use { i -> part.outputStream().use { i.copyTo(it) } }
        if (!part.renameTo(out)) throw java.io.IOException("couldn't move ${out.name} into place")
    }

    /**
     * A file -> books in [lang], answers their ids, off the main thread: an .epub ([Epub]); a .cbz/.zip ([importArchive]:
     * one manga volume, several (a folder of images each, or archives inside), streamed to files: a volume is 100 MB+);
     * else text (one paragraph per non-blank line).
     */
    suspend fun import(uri: Uri, lang: Lang, progress: (String) -> Unit = {}): List<String> = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Book"
        context.contentResolver.openInputStream(uri)!!.use { importStream(it, name, "", lang, progress) }
    }

    /**
     * A folder ([tree], from the system folder picker) -> books: every archive, EPUB or text file in it and its
     * subfolders, and each folder of pictures as one manga volume; titles start with the folder's name.
     */
    suspend fun importFolder(tree: Uri, lang: Lang, progress: (String) -> Unit = {}): List<String> = withContext(Dispatchers.IO) {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val top = docName(DocumentsContract.buildDocumentUriUsingTree(tree, root)) ?: "Books"
        val ids = ArrayList<String>()
        fun walk(docId: String, path: String) {
            val children = children(tree, docId).sortedBy { it.name }
            val pictures = children.filter { !it.dir && Manga.isImage(it.name) }
            if (pictures.isNotEmpty()) withTemp { tmp ->
                val files = pictures.mapIndexed { i, d ->
                    progress(tr("Copying page %d…", i + 1))
                    File(tmp, i.toString()).also { f -> context.contentResolver.openInputStream(d.uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }
                }
                val mokuro = children.firstOrNull { it.name.endsWith(".mokuro", true) }?.let { m -> context.contentResolver.openInputStream(m.uri)!!.use { it.readBytes() } }
                ids += volume(path, lang, pictures.map { "$path/${it.name}" }.zip(files), mokuro)
            }
            for (d in children) when {
                d.dir -> walk(d.id, "$path ${d.name}")
                d.name.matches(Regex(""".*\.(cbz|zip|epub|txt)""", RegexOption.IGNORE_CASE)) ->
                    context.contentResolver.openInputStream(d.uri)!!.use { ids += importStream(it, d.name, "$path ", lang, progress) }
            }
        }
        walk(root, top)
        if (ids.isEmpty()) error(tr("Nothing to import in this folder."))
        ids
    }

    private class Doc(val id: String, val uri: Uri, val name: String, val dir: Boolean)

    private fun children(tree: Uri, docId: String): List<Doc> = context.contentResolver.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
        null, null, null)?.use { c ->
        val out = ArrayList<Doc>()
        while (c.moveToNext()) out += Doc(c.getString(0), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), c.getString(1),
            c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
        out
    }.orEmpty()

    private fun docName(uri: Uri) = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    /** One file's content: [prefix] goes before its books' titles (the folder it came from). */
    private fun importStream(input: java.io.InputStream, name: String, prefix: String, lang: Lang, progress: (String) -> Unit): List<String> {
        val stem = name.substringBeforeLast('.')
        return when {
            name.endsWith(".cbz", true) || name.endsWith(".zip", true) -> importArchive(input, prefix + stem, lang, progress)
            name.endsWith(".epub", true) -> {
                val epub = Epub.read(input.readBytes())
                if (epub.paragraphs.isEmpty()) error(tr("No text in this EPUB."))
                listOf(add(prefix + epub.title.ifBlank { stem }, lang, epub.paragraphs))
            }
            else -> listOf(add(prefix + stem, lang, paragraphs(input.readBytes().toString(Charsets.UTF_8))))
        }
    }

    /**
     * A zip, streamed: its pictures grouped by folder, one manga volume per folder ("Series/Volume 01/001.jpg" ->
     * "Series Volume 01"; a .mokuro is its folder's when named after it, else the archive's only one), and every
     * archive or EPUB inside imported in turn (a zip of .cbz volumes).
     */
    private fun importArchive(input: java.io.InputStream, title: String, lang: Lang, progress: (String) -> Unit): List<String> = withTemp { tmp ->
        val pictures = ArrayList<Pair<String, File>>()
        val mokuros = HashMap<String, ByteArray>()
        val ids = ArrayList<String>()
        val zip = java.util.zip.ZipInputStream(input.buffered())
        for (e in generateSequence { zip.nextEntry }) {
            if (e.isDirectory) continue
            val lower = e.name.lowercase()
            when {
                lower.endsWith(".mokuro") -> mokuros[e.name.substringBeforeLast('.')] = zip.readBytes()
                Manga.isImage(e.name) -> {
                    pictures += e.name to File(tmp, pictures.size.toString()).also { f -> f.outputStream().use { zip.copyTo(it) } }
                    progress(tr("Copying page %d…", pictures.size))
                }
                lower.endsWith(".cbz") || lower.endsWith(".zip") || lower.endsWith(".epub") ->
                    ids += importStream(object : java.io.FilterInputStream(zip) { override fun close() {} }, // the outer zip stays open
                        e.name.substringAfterLast('/'), "$title ", lang, progress)
            }
        }
        val folders = pictures.groupBy { it.first.substringBeforeLast('/', "") }
        for ((folder, files) in folders.toSortedMap()) {
            val name = if (folders.size == 1) title else folder.replace('/', ' ').trim().ifBlank { title }
            ids += volume(name, lang, files, mokuros[folder] ?: mokuros.values.singleOrNull()?.takeIf { folders.size == 1 })
        }
        if (ids.isEmpty()) error(tr("No pictures in this archive."))
        ids
    }

    /** One manga volume from its pictures ([name in the archive or folder] to the file it was copied to). */
    private fun volume(title: String, lang: Lang, pictures: List<Pair<String, File>>, mokuro: ByteArray?): String {
        val (own, pages) = Manga.layout(pictures.map { it.first }, mokuro)
        val id = add(if (mokuro != null && own.isNotBlank()) own else title, lang, emptyList(), pages.map { it.first })
        val byName = pictures.toMap()
        for ((page, source) in pages) byName.getValue(source).renameTo(File(base, "$id/${page.image}").apply { parentFile!!.mkdirs() })
        return id
    }

    /** A scratch folder for the copied pictures, gone afterwards (a nested archive gets its own). */
    private fun <T> withTemp(block: (File) -> T): T {
        val tmp = File(base, ".import-${System.nanoTime()}").apply { mkdirs() }
        try { return block(tmp) } finally { tmp.deleteRecursively() }
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
        private val VOLUME = Regex("""[\s_-]*(?:vol(?:ume)?\.?\s*|第\s*)?\d+\s*巻?\s*$""", RegexOption.IGNORE_CASE)

        /** A manga volume's series, from its title: "Hagure Idol Jigokuhen Volume 07" -> "Hagure Idol Jigokuhen". */
        fun seriesOf(title: String) = VOLUME.replace(title, "").trim().ifBlank { title }

        /** What tells the volume apart in its series: "Volume 07" (the title when it has no series part). */
        fun volumeOf(title: String) = title.removePrefix(seriesOf(title)).trim().ifBlank { title }

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
