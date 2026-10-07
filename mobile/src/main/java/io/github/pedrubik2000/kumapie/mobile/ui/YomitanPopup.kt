package io.github.pedrubik2000.kumapie.mobile.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.mobile.lang.GlossaryHtml
import io.github.pedrubik2000.kumapie.mobile.lang.JapaneseLookup
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * The word popup for many Yomitan dictionaries, laid out like Hachidori's (bee-san/hachidori, docs/assets): headword
 * tabs when the lookup found several words; a header with the reading and its pitch drawn over the word, a one-line
 * summary of the first meanings and 🔊; badges for frequency ranks and pitch patterns; chips per dictionary group
 * (only groups with a result); then one rounded, collapsible card per dictionary in the user's order, with the
 * dictionary's own CSS. The first [OPEN_FIRST] cards are open the first time; after that each dictionary stays open or
 * closed the way it was left. The cards scroll in one WebView kept for the app's life ([PopupWeb]): a tap swaps them
 * in, nothing is rebuilt.
 */
@Composable
fun YomitanPopup(library: Library, lang: Lang, headwords: List<JapaneseLookup.Headword>, onSpeak: (String) -> Unit,
                 /** In the player's word card: a lower card list. */
                 compact: Boolean = false) {
    if (headwords.isEmpty()) {
        Text("Not in your ${lang.name} dictionaries.", color = Colors.dim, fontSize = 14.sp)
        return
    }
    var selected by remember(headwords) { mutableStateOf(0) }
    val hw = headwords[selected.coerceIn(0, headwords.lastIndex)]
    var group by remember(hw) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (headwords.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            headwords.forEachIndexed { i, h ->
                FilterChip(selected = i == selected, onClick = { selected = i },
                    label = { Text(h.expression + if (h.reading.isNotBlank() && h.reading != h.expression) "  ${h.reading}" else "", fontSize = 14.sp) })
            }
        }
        Header(hw, onSpeak)
        val dicts = remember(hw) { hw.terms.flatMap { it.glossaries }.map { it.dict }.distinct() }
        val groups = remember(hw) { dicts.map { library.yomitan.groupOf(lang, it) }.filter { it.isNotBlank() }.distinct() }
        if (groups.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = group == null, onClick = { group = null }, label = { Text("All ${dicts.size}", fontSize = 13.sp) })
            groups.forEach { g ->
                FilterChip(selected = group == g, onClick = { group = g },
                    label = { Text("$g ${dicts.count { library.yomitan.groupOf(lang, it) == g }}", fontSize = 13.sp) })
            }
        }
        Glossaries(library, lang, hw, group, if (compact) 320 else 520,
            onOpen = { w -> headwords.indexOfFirst { it.expression == w }.takeIf { it >= 0 }?.let { selected = it } })
    }
}

/** Reading with pitch over the word, the first meanings beside it, 🔊; then frequency and pitch badges. */
@Composable
private fun Header(hw: JapaneseLookup.Headword, onSpeak: (String) -> Unit) {
    val downsteps = hw.pitches.flatMap { it.second }.distinct()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            // Kana words too when there is a pitch (もう: the line over it is the point).
            val kana = hw.reading.ifBlank { hw.expression }
            if (kana != hw.expression || downsteps.isNotEmpty()) PitchReading(kana, downsteps.firstOrNull())
            Text(hw.expression, fontSize = 28.sp, color = Colors.text)
        }
        val summary = hw.terms.flatMap { it.glossaries }.firstOrNull { it.senses.isNotEmpty() }?.senses
            ?.map(::meaning)?.filter { it.isNotBlank() }?.distinct()?.take(3)?.joinToString("  ·  ") { it.take(40) }.orEmpty()
        Text(summary, fontSize = 14.sp, color = Colors.dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = { onSpeak(hw.reading.ifBlank { hw.expression }) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Say it") }
    }
    val badges = buildList {
        if (hw.frequencies.isNotEmpty()) add("Freq " + hw.frequencies.take(3).joinToString(" · ") { Regex("""\d+""").find(it.second)?.value ?: it.second } +
            if (hw.frequencies.size > 3) "  +${hw.frequencies.size - 3}" else "")
        val kana = hw.reading.ifBlank { hw.expression }
        downsteps.take(3).forEach { add("$kana [$it] ${pattern(morae(kana).size, it)}") }
        hw.terms.flatMap { it.ipa }.distinct().take(2).forEach { add(it) }
    }
    if (badges.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        badges.forEach { b ->
            Text(b, fontSize = 12.sp, color = Colors.text, modifier = Modifier.border(1.dp, Colors.dim.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}

/** A sense as a short meaning: "よばれる【呼ばれる】; 〘v1・vi〙; 1 to be called out." → "to be called out." */
private fun meaning(s: String) = s.substringAfterLast('】').replace(Regex("""〘[^〙]*〙|［[^］]*］|\[[^\]]*]"""), "")
    .replace(Regex("""^[\s;；:・.\d①-⑳]+"""), "").trim()

/** "LHH" for [n] morae with the downstep after mora [down] (0 = heiban). */
private fun pattern(n: Int, down: Int) = (0 until n).joinToString("") { if (high(it, down)) "H" else "L" }

/** The reading in kana with the pitch drawn over it: a line over high morae and a drop after the accented one. */
@Composable
private fun PitchReading(reading: String, downstep: Int?) {
    val morae = morae(reading)
    Row {
        morae.forEachIndexed { i, m ->
            val high = downstep != null && high(i, downstep)
            val drop = downstep != null && downstep > 0 && i == downstep - 1
            Text(m, fontSize = 14.sp, color = Colors.dim, modifier = Modifier.padding(top = 3.dp).drawBehind {
                if (high) drawLine(Colors.accent, Offset(0f, 0f), Offset(size.width, 0f), 2f)
                if (drop) drawLine(Colors.accent, Offset(size.width, 0f), Offset(size.width, size.height * 0.45f), 2f)
            })
        }
    }
}

/** Heiban [0]: low then high; atamadaka [1]: high then low; nakadaka/odaka [n]: low, high up to mora n, then low. */
private fun high(i: Int, n: Int) = when (n) { 0 -> i > 0; 1 -> i == 0; else -> i in 1 until n }

private fun morae(kana: String): List<String> {
    val small = "ゃゅょぁぃぅぇぉャュョァィゥェォ"
    val out = ArrayList<String>()
    for (c in kana) if (c in small && out.isNotEmpty()) out[out.lastIndex] = out.last() + c else out += c.toString()
    return out
}

/** The headword's cards in the shared WebView; chips hide the other groups; open/closed is remembered. */
@Composable
private fun Glossaries(library: Library, lang: Lang, hw: JapaneseLookup.Headword, group: String?, height: Int, onOpen: (String) -> Unit) {
    val opener by rememberUpdatedState(onOpen)
    val prefs = library.settings.prefs
    val (cards, css) = remember(hw) {
        val byDict = LinkedHashMap<String, MutableList<String>>()
        val seen = HashSet<String>()
        for (t in hw.terms) for (g in t.glossaries) if (seen.add(g.dict + " " + g.raw)) byDict.getOrPut(g.dict) { ArrayList() } += GlossaryHtml.glossary(g.raw, g.dict)
        val styles = library.yomitan.styles(lang).filterKeys { it in byDict }
        byDict.entries.mapIndexed { i, (dict, items) ->
            val open = prefs.getBoolean("popup_open_$dict", i < OPEN_FIRST)
            val grp = library.yomitan.groupOf(lang, dict)
            "<details data-dictionary=\"${attr(dict)}\" data-group=\"${attr(grp)}\"${if (open) " open" else ""}>" +
                "<summary>${GlossaryHtml.esc(dict)}${if (grp.isNotBlank()) " <span class=\"grp\">${GlossaryHtml.esc(grp)}</span>" else ""}</summary>" +
                "<div class=\"body\">${items.joinToString("<hr>")}</div></details>"
        }.joinToString("") to GlossaryHtml.styles(styles)
    }
    PopupWeb.onToggle = { dict, open -> prefs.edit().putBoolean("popup_open_$dict", open).apply() }
    PopupWeb.onOpen = { opener(it) }
    PopupWeb.media = { dict, path -> library.yomitan.media(lang, dict, path) }
    Box(Modifier.fillMaxWidth().height(height.dp)) {
        AndroidView(modifier = Modifier.fillMaxWidth().height(height.dp),
            factory = { ctx -> PopupWeb.get(ctx).also { (it.parent as? ViewGroup)?.removeView(it) } },
            onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
            update = { PopupWeb.show(cards, css, group) })
    }
}

/** Makes the popup's WebView ahead of the first tap (the player opening a Japanese episode), so the first popup is quick. */
fun prewarmPopup(context: Context) { PopupWeb.get(context) }

/** One WebView for every popup, made once: a lookup only swaps the cards in by JavaScript (no new view, no page load). */
@SuppressLint("SetJavaScriptEnabled", "StaticFieldLeak")
private object PopupWeb {
    private var web: WebView? = null
    private var loaded = false
    private var pending: String? = null
    private var shown: Triple<String, String, String?>? = null
    var onToggle: (String, Boolean) -> Unit = { _, _ -> }
    var onOpen: (String) -> Unit = {}
    var media: (String, String) -> ByteArray? = { _, _ -> null }

    fun get(context: Context): WebView = web ?: WebView(context.applicationContext).apply {
        setBackgroundColor(0)
        settings.javaScriptEnabled = true
        addJavascriptInterface(object {
            @JavascriptInterface fun toggled(dict: String, open: Boolean) = onToggle(dict, open)
            /** A word in a card (form-of "... of denken"): show its tab. */
            @JavascriptInterface fun open(word: String) { post { onOpen(word.trim()) } }
        }, "kumapie")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                loaded = true
                pending?.let { view.evaluateJavascript(it, null) }
                pending = null
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.scheme != "kumapie-media") return null
                val parts = u.encodedPath.orEmpty().removePrefix("/").split('/', limit = 2)
                if (parts.size < 2) return null
                val bytes = media(android.net.Uri.decode(parts[0]), android.net.Uri.decode(parts[1])) ?: return null
                val type = when (parts[1].substringAfterLast('.').lowercase()) { "svg" -> "image/svg+xml"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/jpeg" }
                return WebResourceResponse(type, null, ByteArrayInputStream(bytes))
            }
        }
        loadDataWithBaseURL("https://kumapie.local/", PAGE, "text/html", "utf-8", null)
    }.also { web = it }

    fun show(cards: String, css: String, group: String?) {
        val now = Triple(cards, css, group)
        if (now == shown) return
        val js = if (shown?.first == cards && shown?.second == css) "showGroup(${JSONObject.quote(group ?: "")})"
            else "render(${JSONObject.quote(css)}, ${JSONObject.quote(cards)}, ${JSONObject.quote(group ?: "")})"
        shown = now
        val w = web ?: return
        if (loaded) w.evaluateJavascript(js, null) else pending = js
    }
}

private fun attr(s: String) = GlossaryHtml.esc(s).replace("\"", "&quot;")

private const val OPEN_FIRST = 2

/** Hachidori's card look: each dictionary a rounded box with a small "▾ name" header. */
private val PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<style>
body { margin: 0; padding: 2px; background: transparent; color: #e8e6e3; font: 15px/1.45 sans-serif; }
details { background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.08); border-radius: 10px; margin: 0 0 8px; padding: 6px 10px; }
summary { color: #a8a6a3; font-size: 13px; cursor: pointer; list-style: none; }
summary::before { content: "▸ "; } details[open] summary::before { content: "▾ "; }
summary .grp { color: #777; font-size: 11px; margin-left: 6px; }
.body { padding: 4px 0 2px; }
ol.glossary-list { margin: 0; padding-left: 1.3em; }
hr { border: 0; border-top: 1px dashed rgba(255,255,255,0.1); margin: 6px 0; }
img { max-width: 100%; }
a, .gloss-sc-a { color: #8ab4f8; }
table { border-collapse: collapse; } td, th { border: 1px solid rgba(255,255,255,0.15); padding: 2px 4px; }
.form-of { color: #d9a25b; text-decoration: underline dotted; cursor: pointer; }
</style><style id="dict"></style></head><body><div id="cards"></div>
<script>
function showGroup(g) { document.querySelectorAll('details').forEach(d => d.style.display = (!g || d.dataset.group === g) ? '' : 'none'); }
function render(css, html, g) {
  document.getElementById('dict').textContent = css;
  const c = document.getElementById('cards'); c.innerHTML = html; window.scrollTo(0, 0);
  c.querySelectorAll('details').forEach(d => d.addEventListener('toggle', () => kumapie.toggled(d.dataset.dictionary, d.open)));
  showGroup(g);
}
</script></body></html>"""
