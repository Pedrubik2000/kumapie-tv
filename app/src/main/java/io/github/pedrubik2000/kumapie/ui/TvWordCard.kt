package io.github.pedrubik2000.kumapie.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.CardDefinition
import io.github.pedrubik2000.kumapie.lang.GlossaryHtml
import io.github.pedrubik2000.kumapie.lang.Headword
import io.github.pedrubik2000.kumapie.lang.JapaneseLookup
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

@Composable
fun TvJapaneseCard(settings: Settings, ctl: SceneController, picker: WordPicker, height: Int) {
    val context = LocalContext.current
    val segment = picker.selected ?: return
    val key = segment.word ?: return
    val word = ctl.words[key] ?: io.github.pedrubik2000.kumapie.data.Word("u", "")
    var hw by remember(picker.line, picker.seg) { mutableStateOf<Headword?>(null) }
    var html by remember(picker.line, picker.seg) { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    Row(Modifier.width(860.dp).height(height.dp)) {
        Column(Modifier.width(230.dp).fillMaxHeight().padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val kana = hw?.reading?.takeIf { it.isNotBlank() } ?: word.reading
            val expression = hw?.expression ?: word.lemma.ifBlank { segment.text }
            if (kana.isNotBlank() && kana != expression) PitchReading(kana, hw?.pitches?.flatMap { it.second }?.firstOrNull())
            Text(expression, color = Colors.text, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            if (segment.text != expression) Text(segment.text, color = Colors.dim, fontSize = 16.sp)
            (ctl.scene.meanings[key] ?: word.meaning).substringAfter(" = ").takeIf { it.isNotBlank() }?.let {
                Text(it, color = Colors.text, fontSize = 19.sp, maxLines = 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
            val (state, color) = statusLine(word.formCard(segment.text)?.let { word.copy(card = it) } ?: word)
            val shown = word.formCard(segment.text) ?: word.card.takeUnless { word.marked }
            if (shown == "d") Text(state, color = Arcade.background, fontSize = 12.sp, style = Arcade.label,
                modifier = Modifier.padding(top = 4.dp).background(Colors.due, Slab).padding(horizontal = 12.dp, vertical = 1.dp))
            val looked = if (word.lookups > 0) tr("  ·  looked up %d×", word.lookups) else ""
            Text(if (shown == "d") looked.trimStart(' ', '·') else state + looked, color = color, fontSize = 15.sp)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Colors.dim.copy(alpha = 0.2f)))
        val message = status ?: when (html) { null -> tr("Looking it up…"); "" -> tr("Not in your %s dictionaries.", ctl.lang.displayName); else -> null }
        if (message != null) Text(message, color = Colors.dim, fontSize = 17.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        else html?.let { DictionaryWeb(it, Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp)) }
    }
    LaunchedEffect(picker.line, picker.seg) {
        TvMining.languages(context, settings)
        val y = TvMining.yomitan
        if (y.of(Lang.JAPANESE).isEmpty()) TvMining.fetchJapanese(context, settings)
        while (TvMining.fetching != null) { status = TvMining.fetching; delay(2_000) }
        status = null
        val cue = ctl.scene.cues.getOrNull(picker.line) ?: return@LaunchedEffect
        val offset = cue.segments.take(picker.seg).sumOf { it.text.length }
        val word = ctl.words[key]
        val lemma = ctl.scene.def(picker.line, key)?.lemma?.takeIf { it.isNotBlank() } ?: word?.lemma?.takeIf { it.isNotBlank() }
        val found = withContext(Dispatchers.IO) { tvHeadword(y, cue.text, offset, lemma, word?.reading.orEmpty()) }
        hw = found
        html = if (found == null) "" else withContext(Dispatchers.IO) {
            val byDict = LinkedHashMap<String, MutableList<String>>()
            for (t in found.terms) for (g in t.glossaries) byDict.getOrPut(g.dict) { ArrayList() }.let { if (g.raw !in it) it += g.raw }
            val dicts = byDict.keys.sortedBy { CardDefinition.rank(it) ?: Int.MAX_VALUE }
            val css = GlossaryHtml.styles(y.styles(Lang.JAPANESE).filterKeys { it in byDict })
            val tabs = dicts.joinToString("") { "<span>${GlossaryHtml.esc(dictLabel(it))}</span>" }
            "<style>$css</style><nav id=\"tabs\">$tabs</nav>" + dicts.joinToString("") { d ->
                "<section data-dictionary=\"${GlossaryHtml.esc(d).replace("\"", "&quot;")}\"><h3>${GlossaryHtml.esc(dictLabel(d))}</h3>" +
                    byDict.getValue(d).joinToString("<hr>") { GlossaryHtml.glossary(it, d) } + "</section>"
            }
        }
    }
}

private fun dictLabel(title: String): String {
    val name = Regex("[A-Za-z]+").find(title)?.value?.let { if (it.any(Char::isUpperCase)) it else it.replaceFirstChar(Char::uppercase) } ?: title.take(16)
    val lang = when {
        title.contains("es-jp", true) || title.contains("spanish", true) -> " ES"
        title.contains("en-jp", true) -> " EN"
        else -> ""
    }
    return name + lang
}

@Composable
private fun PitchReading(kana: String, downstep: Int?) {
    val morae = JapaneseLookup.morae(kana)
    fun high(i: Int, n: Int) = JapaneseLookup.high(i, n)
    Row {
        morae.forEachIndexed { i, m ->
            val up = downstep != null && high(i, downstep)
            val drop = downstep != null && downstep > 0 && i == downstep - 1
            Text(m, color = Colors.dim, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp).drawBehind {
                if (up) drawLine(Colors.accent, Offset(0f, 0f), Offset(size.width, 0f), 3f)
                if (drop) drawLine(Colors.accent, Offset(size.width, 0f), Offset(size.width, size.height * 0.45f), 3f)
            })
        }
    }
}

@Composable
private fun DictionaryWeb(html: String, modifier: Modifier) {
    AndroidView(modifier = modifier,
        factory = { ctx -> WebView(ctx).apply {
            setBackgroundColor(0)
            isFocusable = false
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url
                    if (u.scheme != "kumapie-media") return null
                    val parts = u.encodedPath.orEmpty().removePrefix("/").split('/', limit = 2)
                    if (parts.size < 2) return null
                    val bytes = TvMining.yomitan.media(Lang.JAPANESE, Uri.decode(parts[0]), Uri.decode(parts[1])) ?: return null
                    val type = if (parts[1].endsWith(".svg", true)) "image/svg+xml" else "image/*"
                    return WebResourceResponse(type, null, ByteArrayInputStream(bytes))
                }
            }
            TvMining.web = this
        } },
        onRelease = { if (TvMining.web === it) TvMining.web = null },
        update = { w ->
            if (w.tag != html) { w.tag = html; w.loadDataWithBaseURL("https://kumapie.local/", PAGE.replace("<!--BODY-->", html), "text/html", "utf-8", null) }
        })
}

fun scrollTvDictionaries(dir: Int): Boolean {
    val w = TvMining.web ?: return false
    if (!w.isAttachedToWindow || !w.canScrollVertically(dir)) return false
    w.evaluateJavascript("step($dir)", null)
    return true
}

private const val PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<style>
body { margin: 0; background: transparent; color: #F2F0F7; font: 19px/1.6 sans-serif; }
#tabs { position: sticky; top: 0; z-index: 1; background: #14171D; padding: 2px 4px 10px; display: flex; flex-wrap: wrap; gap: 6px; }
#tabs span { font-size: 13px; color: #A8A8A8; background: rgba(255,255,255,0.12); border: 1px solid rgba(255,255,255,0.18); padding: 2px 12px; border-radius: 18px; }
#tabs span.on { color: #000; font-weight: 600; background: #fff; border-color: #fff; }
section { padding: 2px 0 14px; margin-bottom: 12px; border-bottom: 1px solid rgba(169,164,184,0.15); }
section:last-of-type { border-bottom: 0; padding-bottom: 40px; }
h3 { margin: 0 0 4px; font-size: 14px; font-weight: 600; color: #9CC9FF; }
ol, ul { margin: 2px 0; padding-left: 1.4em; }
hr { border: 0; border-top: 1px dashed rgba(169,164,184,0.2); margin: 8px 0; }
img { max-width: 100%; }
a { color: #8ab4f8; text-decoration: none; }
table { border-collapse: collapse; } td, th { border: 1px solid rgba(169,164,184,0.25); padding: 2px 6px; }
#fade { position: fixed; left: 0; right: 0; bottom: 0; height: 36px; pointer-events: none;
  background: linear-gradient(rgba(20,23,29,0), #14171D); transition: opacity .2s; }
</style></head><body><!--BODY--><div id="fade"></div>
<script>
const secs = [...document.querySelectorAll('section')], tabs = [...document.querySelectorAll('#tabs span')];
const bar = () => document.getElementById('tabs').offsetHeight;
const atEnd = () => innerHeight + scrollY >= document.documentElement.scrollHeight - 2;
// The tab of the dictionary at the top of the view (the last one at the end); the fade only while more is below.
function mark() {
  let i = 0; secs.forEach((s, k) => { if (s.offsetTop - bar() <= scrollY + 4) i = k; });
  if (atEnd()) i = secs.length - 1;
  tabs.forEach((t, k) => t.classList.toggle('on', k === i));
  document.getElementById('fade').style.opacity = atEnd() ? 0 : 1;
}
// ↓: the next dictionary's heading to the top when it is on screen, else a page; ↑ the same way back.
function step(d) {
  const page = (innerHeight - bar()) * 0.85, y = scrollY;
  const tops = secs.map(s => s.offsetTop - bar());
  const t = d > 0 ? tops.find(v => v > y + 4) : tops.filter(v => v < y - 4).pop();
  const to = t !== undefined && Math.abs(t - y) <= page ? t : y + d * page;
  scrollTo({ top: Math.max(0, to), behavior: 'smooth' });
}
addEventListener('scroll', mark); mark();
</script></body></html>"""
