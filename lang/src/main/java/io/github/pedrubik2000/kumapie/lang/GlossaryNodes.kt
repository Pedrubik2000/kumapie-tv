package io.github.pedrubik2000.kumapie.lang

import org.json.JSONArray
import org.json.JSONObject

/**
 * Yomitan structured content as plain blocks of styled text, for the TV's native word card (no WebView: the TV's
 * 32-bit address space can't afford one). Pure and JVM-testable. What it keeps: text, bold/italic/size, ruby as
 * `base[reading]`, ol/ul/li, tables as rows, tag pills (part of speech and the like), links as coloured text,
 * images as their title/alt. What it drops: the dictionary's own CSS, colours, images without a title, layout.
 */
object GlossaryNodes {
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val size: Float = 1f,
        val pill: Boolean = false,
        val link: Boolean = false,
        val dim: Boolean = false,
    )

    /** One paragraph: [indent] list levels deep; a list item's marker is its first run. */
    data class Block(val runs: List<Run>, val indent: Int = 0) {
        val text get() = runs.joinToString("") { it.text }
    }

    const val MAX_DEPTH = 24
    const val MAX_CHARS = 20_000
    const val MAX_BLOCKS = 300

    private val PILL = Regex("(?i)tag|-info\$|label|part-?of-?speech|^pos\$")

    /** One dictionary's glossary (the raw JSON array of items) as blocks; a plain string works too. */
    fun build(raw: String): List<Block> {
        val b = Builder()
        val a = runCatching { JSONArray(raw) }.getOrNull()
        if (a == null) b.text(raw, Style())
        else {
            val items = (0 until a.length()).map { a.get(it) }
            items.forEachIndexed { i, item ->
                b.flush()
                if (items.size > 1) b.text("${i + 1}. ", Style(dim = true))
                b.item(item, Style(), 0)
            }
        }
        b.flush()
        return b.blocks
    }

    private data class Style(
        val bold: Boolean = false, val italic: Boolean = false, val size: Float = 1f,
        val pill: Boolean = false, val link: Boolean = false, val dim: Boolean = false, val indent: Int = 0,
    )

    private class Builder {
        val blocks = ArrayList<Block>()
        private var runs = ArrayList<Run>()
        private var indent = 0
        private var chars = 0

        fun flush() {
            if (runs.any { it.text.isNotBlank() } && blocks.size < MAX_BLOCKS) blocks += Block(runs, indent)
            runs = ArrayList()
        }

        fun text(s: String, st: Style) {
            if (s.isEmpty() || chars > MAX_CHARS) return
            chars += s.length
            runs += Run(s, st.bold, st.italic, st.size, st.pill, st.link, st.dim)
        }

        private fun block(st: Style, body: () -> Unit) { flush(); val prev = indent; indent = st.indent; body(); flush(); indent = prev }

        fun item(node: Any?, st: Style, depth: Int) {
            // A form-of item: [dictionary form, [inflection rules]].
            if (node is JSONArray && node.length() == 2 && node.opt(0) is String && node.opt(1) is JSONArray) {
                val rules = node.getJSONArray(1)
                text((0 until rules.length()).joinToString(", ") { rules.optString(it) } + " of ", st)
                text(node.getString(0), st.copy(link = true))
                return
            }
            content(node, st, depth)
        }

        private fun content(node: Any?, st: Style, depth: Int) {
            if (depth > MAX_DEPTH || chars > MAX_CHARS) return
            when (node) {
                null, JSONObject.NULL -> {}
                is String -> text(node, st)
                is JSONArray -> for (i in 0 until node.length()) content(node.get(i), st, depth + 1)
                is JSONObject -> when (node.optString("type")) {
                    "text" -> text(node.optString("text"), st)
                    "structured-content" -> content(node.opt("content"), st, depth + 1)
                    "image" -> image(node, st)
                    else -> element(node, st, depth)
                }
                else -> text(node.toString(), st)
            }
        }

        private fun image(node: JSONObject, st: Style) {
            val t = node.optString("title").ifBlank { node.optString("alt") }
            if (t.isNotBlank()) text(t, st)
        }

        private fun element(node: JSONObject, st: Style, depth: Int) {
            val tag = node.optString("tag")
            val css = node.optJSONObject("style")
            if (css?.optString("display") == "none") return
            var s = st
            if (css != null) {
                val w = css.opt("fontWeight")?.toString().orEmpty()
                if (w == "bold" || (w.toIntOrNull() ?: 0) >= 600) s = s.copy(bold = true)
                if (css.optString("fontStyle") == "italic") s = s.copy(italic = true)
                size(css.opt("fontSize"))?.let { s = s.copy(size = (s.size * it).coerceIn(0.5f, 2f)) }
                if (css.has("backgroundColor")) s = s.copy(pill = true)
            }
            node.optJSONObject("data")?.let { d ->
                if (d.keys().asSequence().any { k -> PILL.containsMatchIn(d.optString(k)) || (k == "tag" || k == "partOfSpeech") }) s = s.copy(pill = true)
            }
            val child = node.opt("content")
            when (tag) {
                "br" -> text("\n", s)
                "rp", "img" -> if (tag == "img") image(node, s)
                "rt" -> { text("[", s.copy(dim = true, size = s.size * 0.7f)); content(child, s.copy(dim = true, size = s.size * 0.7f), depth + 1); text("]", s.copy(dim = true, size = s.size * 0.7f)) }
                "b", "strong", "th" -> content(child, s.copy(bold = true), depth + 1)
                "i", "em" -> content(child, s.copy(italic = true), depth + 1)
                "a" -> content(child, s.copy(link = true), depth + 1)
                "ul", "ol" -> block(s) { list(child, tag == "ol", s.copy(indent = s.indent + 1), depth) }
                "li", "div", "summary", "details", "table", "thead", "tbody", "tfoot" ->
                    block(s.copy(bold = s.bold || tag == "summary")) { content(child, s.copy(bold = s.bold || tag == "summary"), depth + 1) }
                "tr" -> block(s) { cells(child, s, depth) }
                else -> content(child, s, depth + 1) // span and anything unknown: its children
            }
        }

        private fun list(child: Any?, ordered: Boolean, st: Style, depth: Int) {
            val kids = when (child) { is JSONArray -> (0 until child.length()).map { child.get(it) }; null -> emptyList(); else -> listOf(child) }
            var n = 0
            for (k in kids) {
                val isLi = k is JSONObject && k.optString("tag") == "li"
                if (!isLi) { content(k, st, depth + 1); continue }
                flush(); indent = st.indent - 1
                text(if (ordered) "${++n}. " else "• ", st.copy(dim = true))
                content((k as JSONObject).opt("content"), st, depth + 2)
                flush()
            }
        }

        private fun cells(child: Any?, st: Style, depth: Int) {
            val kids = when (child) { is JSONArray -> (0 until child.length()).map { child.get(it) }; null -> emptyList(); else -> listOf(child) }
            var first = true
            for (k in kids) {
                if (!first) text("   ", st)
                first = false
                content(k, st, depth + 1)
            }
        }

        /** "0.8em" -> 0.8, "80%" -> 0.8, 1 -> 1 (a number is em), "small" -> 0.85; else null. */
        private fun size(v: Any?): Float? = when (v) {
            is Number -> v.toFloat()
            is String -> when {
                v.endsWith("em") -> v.removeSuffix("em").toFloatOrNull()
                v.endsWith("%") -> v.removeSuffix("%").toFloatOrNull()?.div(100)
                v == "small" || v == "smaller" -> 0.85f
                v == "large" || v == "larger" -> 1.2f
                else -> null
            }
            else -> null
        }
    }
}
