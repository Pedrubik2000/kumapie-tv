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
import io.github.pedrubik2000.kumapie.lang.GlossaryHtml
import io.github.pedrubik2000.kumapie.lang.JapaneseLookup
import io.github.pedrubik2000.kumapie.lang.Headword
import io.github.pedrubik2000.kumapie.lang.Tap
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlinx.coroutines.launch
import io.github.pedrubik2000.kumapie.i18n.tr

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
fun YomitanPopup(library: Library, lang: Lang, headwords: List<Headword>, onSpeak: (Headword) -> Unit,
                 /** In the player's word card: a lower card list. */
                 compact: Boolean = false) {
    if (headwords.isEmpty()) {
        Text(tr("Not in your %s dictionaries.", lang.displayName), color = Colors.dim, fontSize = 14.sp)
        return
    }
    // Words looked up from inside a definition (Japanese): a stack, ← goes back.
    var nested by remember(headwords) { mutableStateOf<List<List<Headword>>>(emptyList()) }
    val shown = nested.lastOrNull() ?: headwords
    var selected by remember(shown) { mutableStateOf(0) }
    val hw = shown[selected.coerceIn(0, shown.lastIndex)]
    var group by remember(hw) { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val language = library.languages.of(lang)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (shown.size > 1 || nested.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (nested.isNotEmpty()) FilterChip(selected = false, onClick = { nested = nested.dropLast(1) }, label = { Text("←", fontSize = 14.sp) })
            shown.forEachIndexed { i, h ->
                FilterChip(selected = i == selected, onClick = { selected = i },
                    label = { Text(h.expression + if (h.reading.isNotBlank() && h.reading != h.expression) "  ${h.reading}" else "", fontSize = 14.sp) })
            }
        }
        Header(hw, onSpeak)
        val dicts = remember(hw) { hw.terms.flatMap { it.glossaries }.map { it.dict }.distinct() }
        val groups = remember(hw) { dicts.map { library.yomitan.groupOf(lang, it) }.filter { it.isNotBlank() }.distinct() }
        if (groups.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = group == null, onClick = { group = null }, label = { Text(tr("All %d", dicts.size), fontSize = 13.sp) })
            groups.forEach { g ->
                FilterChip(selected = group == g, onClick = { group = g },
                    label = { Text("$g ${dicts.count { library.yomitan.groupOf(lang, it) == g }}", fontSize = 13.sp) })
            }
        }
        // In the word card: small enough that the card (word above, buttons below) fits a phone in landscape.
        Glossaries(library, lang, hw, group, if (compact) (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 300).coerceIn(140, 320) else 520,
            onOpen = { w -> shown.indexOfFirst { it.expression == w }.takeIf { it >= 0 }?.let { selected = it } },
            onTapText = { text, offset ->
                // A tap in a definition (Japanese scans from there; spaced languages find nothing without a picked word).
                scope.launch {
                    val found = runCatching { language.lookup(Tap(text, offset)) }.getOrDefault(emptyList())
                    if (found.isNotEmpty() && found.first().expression != hw.expression) nested = nested + listOf(found)
                }
            })
    }
}

/** Reading with pitch over the word, the first meanings beside it, 🔊; then frequency and pitch badges. */
@Composable
private fun Header(hw: Headword, onSpeak: (Headword) -> Unit) {
    val downsteps = hw.pitches.flatMap { it.second }.distinct()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            // Kana words too when there is a pitch (もう: the line over it is the point).
            val kana = hw.reading.ifBlank { hw.expression }
            if (kana != hw.expression || downsteps.isNotEmpty()) PitchReading(kana, downsteps.firstOrNull())
            Text(hw.expression, fontSize = 28.sp, color = Colors.text)
        }
        val summary = hw.terms.flatMap { it.glossaries }.firstOrNull { it.senses.isNotEmpty() }?.senses
            ?.flatMap(JapaneseLookup::meanings)?.distinct()?.take(3)?.joinToString("  ·  ") { it.take(40) }.orEmpty()
        Text(summary, fontSize = 14.sp, color = Colors.dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = { onSpeak(hw) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, tr("Say it")) }
    }
    val badges = buildList {
        if (hw.frequencies.isNotEmpty()) add(tr("Freq ") + hw.frequencies.take(3).joinToString(" · ") { Regex("""\d+""").find(it.second)?.value ?: it.second } +
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
private fun Glossaries(library: Library, lang: Lang, hw: Headword, group: String?, height: Int, onOpen: (String) -> Unit,
                       onTapText: (String, Int) -> Unit = { _, _ -> }) {
    val opener by rememberUpdatedState(onOpen)
    val tapper by rememberUpdatedState(onTapText)
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
    PopupWeb.onTapText = { t, o -> tapper(t, o) }
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
    var onTapText: (String, Int) -> Unit = { _, _ -> }
    var media: (String, String) -> ByteArray? = { _, _ -> null }

    fun get(context: Context): WebView = web ?: WebView(context.applicationContext).apply {
        setBackgroundColor(0)
        settings.javaScriptEnabled = true
        addJavascriptInterface(object {
            @JavascriptInterface fun toggled(dict: String, open: Boolean) = onToggle(dict, open)
            /** A word in a card (form-of "... of denken"): show its tab. */
            @JavascriptInterface fun open(word: String) { post { onOpen(word.trim()) } }
            /** A tap inside a definition: the text around it and where (a word inside a monolingual definition). */
            @JavascriptInterface fun tapText(text: String, offset: Int) { post { onTapText(text, offset) } }
        }, "kumapie")
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.i("kumapie", "popup web: ${m.message()} @${m.lineNumber()}")
                return true
            }
        }
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
// A tap on text inside a card (not its header, not a form-of link): look up the word there.
document.addEventListener('click', e => {
  if (e.target.closest('summary') || e.target.closest('.form-of') || !e.target.closest('.body')) return;
  // The text node and offset under the finger (caretPositionFromPoint in newer WebViews, caretRangeFromPoint before).
  let node = null, at = 0;
  const p = document.caretPositionFromPoint && document.caretPositionFromPoint(e.clientX, e.clientY);
  if (p) { node = p.offsetNode; at = p.offset; }
  else { const r = document.caretRangeFromPoint(e.clientX, e.clientY); if (r) { node = r.startContainer; at = r.startOffset; } }
  if (node && node.nodeType !== 3) { const c = node.childNodes[Math.max(0, at - 1)]; if (c && c.nodeType === 3) { node = c; at = Math.max(0, c.length - 1); } }
  if (!node || node.nodeType !== 3) return;
  const text = node.textContent;
  const ja = i => /[\u3040-\u30ff\u3400-\u9fff]/.test(text.charAt(i));
  if (!ja(at) && at > 0 && ja(at - 1)) at -= 1; // the caret sits just after the tapped character
  if (!ja(at)) return;
  kumapie.tapText(text, at);
});
function showGroup(g) { document.querySelectorAll('details').forEach(d => d.style.display = (!g || d.dataset.group === g) ? '' : 'none'); }
function render(css, html, g) {
  document.getElementById('dict').textContent = css;
  const c = document.getElementById('cards'); c.innerHTML = html; window.scrollTo(0, 0);
  c.querySelectorAll('details').forEach(d => d.addEventListener('toggle', () => kumapie.toggled(d.dataset.dictionary, d.open)));
  showGroup(g);
}
</script></body></html>"""
