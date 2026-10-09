package io.github.pedrubik2000.kumapie.lang

import org.json.JSONArray
import org.json.JSONObject

/**
 * Yomitan glossaries as HTML, the way Yomitan builds them, so each dictionary's own styles.css applies: structured
 * content elements become their tag with class `gloss-sc-<tag>` and `data-sc-<key>` attributes, `style` objects
 * become inline CSS, images point to `kumapie-media://<dictionary>/<path>` (served from the dictionary by the
 * popup's WebView). Each dictionary's CSS is scoped to its own block with CSS nesting.
 */
object GlossaryHtml {
    private val tags = setOf("br", "ruby", "rt", "rp", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "span", "div",
        "ol", "ul", "li", "details", "summary", "a", "img")

    /** One dictionary's glossary items (the raw JSON array) as a list. */
    fun glossary(raw: String, dict: String): String = runCatching {
        val a = JSONArray(raw)
        val items = (0 until a.length()).map { i ->
            val sb = StringBuilder()
            item(a.get(i), dict, sb)
            sb.toString()
        }.filter { it.isNotBlank() }
        if (items.size == 1) "<div class=\"glossary-item\">${items[0]}</div>"
        else items.joinToString("", "<ol class=\"glossary-list\">", "</ol>") { "<li class=\"glossary-item\">$it</li>" }
    }.getOrDefault(esc(raw))

    private fun item(node: Any?, dict: String, sb: StringBuilder) {
        // A form-of item: [dictionary form, [inflection rules]].
        if (node is JSONArray && node.length() == 2 && node.opt(0) is String && node.opt(1) is JSONArray) {
            val rules = node.getJSONArray(1)
            sb.append(esc((0 until rules.length()).joinToString(", ") { rules.optString(it) })).append(" of ")
                .append("<span class=\"form-of\" onclick=\"kumapie.open(this.textContent)\">").append(esc(node.getString(0))).append("</span>")
            return
        }
        content(node, dict, sb)
    }

    private fun content(node: Any?, dict: String, sb: StringBuilder) {
        when (node) {
            null, JSONObject.NULL -> {}
            is String -> sb.append(esc(node).replace("\n", "<br>"))
            is JSONArray -> for (i in 0 until node.length()) content(node.get(i), dict, sb)
            is JSONObject -> when (node.optString("type")) {
                "text" -> sb.append(esc(node.optString("text")).replace("\n", "<br>"))
                "structured-content" -> content(node.opt("content"), dict, sb)
                "image" -> image(node, dict, sb)
                else -> element(node, dict, sb)
            }
            else -> sb.append(esc(node.toString()))
        }
    }

    private fun element(node: JSONObject, dict: String, sb: StringBuilder) {
        val tag = node.optString("tag")
        if (tag == "img") return image(node, dict, sb)
        if (tag !in tags) return content(node.opt("content"), dict, sb)
        val out = if (tag == "a") "span" else tag // links would leave the popup
        sb.append('<').append(out).append(" class=\"gloss-sc-").append(tag).append('"')
        node.optJSONObject("data")?.let { d -> d.keys().forEach { k -> sb.append(" data-sc-").append(attr(k)).append("=\"").append(attr(d.optString(k))).append('"') } }
        node.optJSONObject("style")?.let { st -> sb.append(" style=\"").append(attr(css(st))).append('"') }
        node.optString("lang").takeIf { it.isNotBlank() }?.let { sb.append(" lang=\"").append(attr(it)).append('"') }
        node.optString("title").takeIf { it.isNotBlank() }?.let { sb.append(" title=\"").append(attr(it)).append('"') }
        if (tag == "td" || tag == "th") {
            node.optInt("colSpan", 0).takeIf { it > 1 }?.let { sb.append(" colspan=\"").append(it).append('"') }
            node.optInt("rowSpan", 0).takeIf { it > 1 }?.let { sb.append(" rowspan=\"").append(it).append('"') }
        }
        if (tag == "details" && node.optBoolean("open")) sb.append(" open")
        if (tag == "br") { sb.append('>'); return }
        sb.append('>')
        content(node.opt("content"), dict, sb)
        sb.append("</").append(out).append('>')
    }

    private fun image(node: JSONObject, dict: String, sb: StringBuilder) {
        val path = node.optString("path").takeIf { it.isNotBlank() } ?: return
        sb.append("<img class=\"gloss-image\" src=\"kumapie-media://media/").append(attr(android.net.Uri.encode(dict))).append('/')
            .append(attr(android.net.Uri.encode(path, "/"))).append('"')
        node.optString("alt").takeIf { it.isNotBlank() }?.let { sb.append(" alt=\"").append(attr(it)).append('"') }
        val w = node.optDouble("width", Double.NaN)
        val h = node.optDouble("height", Double.NaN)
        val unit = node.optString("sizeUnits").ifBlank { "px" }
        val style = StringBuilder()
        if (!w.isNaN()) style.append("width:").append(w).append(unit).append(';')
        if (!h.isNaN()) style.append("height:").append(h).append(unit).append(';')
        if (node.optString("verticalAlign").isNotBlank()) style.append("vertical-align:").append(node.optString("verticalAlign")).append(';')
        if (style.isNotEmpty()) sb.append(" style=\"").append(attr(style.toString())).append('"')
        sb.append('>')
    }

    /** {"fontSize": "0.8em", "marginLeft": 1} -> "font-size:0.8em;margin-left:1". */
    private fun css(st: JSONObject): String = st.keys().asSequence().joinToString(";") { k ->
        k.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() } + ":" + st.optString(k)
    }

    /** Every dictionary's CSS, each inside its own [data-dictionary="..."] block (CSS nesting). */
    fun styles(byDict: Map<String, String>): String = byDict.entries.joinToString("\n") { (dict, css) ->
        "[data-dictionary=\"${dict.replace("\\", "\\\\").replace("\"", "\\\"")}\"] {\n${css.replace("</", "<\\/")}\n}"
    }

    fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun attr(s: String): String = esc(s).replace("\"", "&quot;")
}
