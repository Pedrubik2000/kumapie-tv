package io.github.pedrubik2000.kumapie.mobile.read

import java.util.zip.ZipInputStream

/**
 * An EPUB → its title and paragraphs, in reading order (kumapie_plan.md, "Readers"): META-INF/container.xml → the
 * OPF → its spine → each chapter's XHTML, one paragraph per block (p, headings, div, li…). Furigana (rt, rp) is left
 * out, so 漢字 reads as written. Images are skipped.
 * ponytail: a tolerant tag scanner, not an XML parser (Japanese EPUBs use HTML entities a strict parser refuses);
 * enough for text, not for tables or CSS-made layout.
 */
object Epub {
    class Book(val title: String, val paragraphs: List<String>)

    fun read(bytes: ByteArray): Book {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.filter { !it.isDirectory }.forEach { files[it.name] = zip.readBytes() }
        }
        fun text(path: String) = files[path]?.toString(Charsets.UTF_8) ?: error("EPUB: $path is missing")
        val opfPath = Regex("""full-path\s*=\s*"([^"]+)"""").find(text("META-INF/container.xml"))?.groupValues?.get(1)
            ?: error("EPUB: no OPF in container.xml")
        val opf = text(opfPath)
        val dir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val hrefs = Regex("""<(?:\w+:)?item\b[^>]*>""").findAll(opf).mapNotNull { m ->
            val id = attr(m.value, "id") ?: return@mapNotNull null
            id to (attr(m.value, "href") ?: return@mapNotNull null)
        }.toMap()
        val spine = Regex("""<(?:\w+:)?itemref\b[^>]*>""").findAll(opf).mapNotNull { attr(it.value, "idref") }.toList()
        val title = Regex("""<dc:title[^>]*>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL).find(opf)?.groupValues?.get(1)
            ?.let { decode(it).trim() }.orEmpty()
        val paragraphs = spine.mapNotNull { hrefs[it] }.flatMap { href ->
            val path = normalize(dir + java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
            files[path]?.let { paragraphs(it.toString(Charsets.UTF_8)) }.orEmpty()
        }
        return Book(title, paragraphs)
    }

    private val BLOCKS = setOf("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "dd", "dt", "tr", "section", "br", "hr")
    private val SKIPPED = setOf("rt", "rp", "script", "style", "head", "title")

    /** An XHTML chapter → its blocks' text, blank ones left out. */
    fun paragraphs(html: String): List<String> {
        val out = ArrayList<String>()
        val now = StringBuilder()
        var skip = 0
        fun flush() { val t = now.toString().replace(Regex("""\s+"""), " ").trim(); if (t.isNotEmpty()) out += t; now.clear() }
        for (m in Regex("""<!--.*?-->|<[!?][^>]*>|<(/?)([A-Za-z][\w:-]*)[^>]*?(/?)>|([^<]+)""", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val (close, name, self, text) = m.destructured
            if (text.isNotEmpty()) { if (skip == 0) now.append(decode(text)); continue }
            if (name.isEmpty()) continue // a comment, <?xml ?>, <!DOCTYPE>

            val tag = name.substringAfter(':').lowercase()
            if (tag in SKIPPED && self.isEmpty()) { skip += if (close.isEmpty()) 1 else -1; skip = skip.coerceAtLeast(0); continue }
            if (tag in BLOCKS) flush()
        }
        flush()
        return out
    }

    private fun attr(tag: String, name: String) = Regex("""\b$name\s*=\s*"([^"]*)"""").find(tag)?.groupValues?.get(1)

    /** "OEBPS/text/../images/a.png" → "OEBPS/images/a.png". */
    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.split('/')) when (p) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts += p }
        return parts.joinToString("/")
    }

    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”")

    fun decode(s: String): String = Regex("""&(#x[0-9a-fA-F]+|#\d+|\w+);""").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED[e] ?: m.value
        }
    }
}
