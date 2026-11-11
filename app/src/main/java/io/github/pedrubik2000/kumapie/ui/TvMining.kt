package io.github.pedrubik2000.kumapie.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.AnkiCards
import io.github.pedrubik2000.kumapie.lang.CardDefinition
import io.github.pedrubik2000.kumapie.lang.Dictionary
import io.github.pedrubik2000.kumapie.lang.GlossaryHtml
import io.github.pedrubik2000.kumapie.lang.Headword
import io.github.pedrubik2000.kumapie.lang.Japanese
import io.github.pedrubik2000.kumapie.lang.KnownWords
import io.github.pedrubik2000.kumapie.lang.Language
import io.github.pedrubik2000.kumapie.lang.JapaneseLookup
import io.github.pedrubik2000.kumapie.lang.Languages
import io.github.pedrubik2000.kumapie.lang.Miner
import io.github.pedrubik2000.kumapie.lang.SensePick
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import kotlin.coroutines.resume

/**
 * The TV's mining (no spaCy, no Sudachi: the PC's word data is the card's): [languages] when a player opens
 * ([paintCards]), the miner on the first hold ↑.
 */
private object TvMining {
    private var miner: Miner? = null
    lateinit var languages: Languages
    lateinit var yomitan: YomitanDictionaries
    /** Outlives the player: the Japanese dictionaries keep coming after it closes. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Words mined on this TV since kumapie started -> their notes: hold ↑ again shows the card. */
    val mined = HashMap<String, Long>()
    /** What the Japanese dictionaries' download is doing; null when it isn't running. */
    @Volatile var fetching: String? = null
    /** While an episode's cards are being read from kuma3 ([openCards]): the colours may be the saved ones, so no rating yet. */
    @Volatile var reading = false
    /** The word card's dictionaries ([TvDictionaries]) while shown: ↑↓ scroll it. */
    @android.annotation.SuppressLint("StaticFieldLeak") var web: WebView? = null

    private var dictionary: Dictionary? = null

    fun languages(context: Context, settings: Settings): Languages {
        if (dictionary == null) {
            val app = context.applicationContext
            yomitan = YomitanDictionaries.get(app)
            dictionary = Dictionary(app)
            languages = Languages(app, settings, yomitan, dictionary!!) { _, _ -> error("no spaCy on the TV") }
            // A TV that mines Japanese gets the zips added on the PC since, in the background.
            if (yomitan.of(Lang.JAPANESE).isNotEmpty() && settings.server.isNotEmpty()) fetchJapanese(context, settings)
        }
        return languages
    }

    fun miner(context: Context, settings: Settings): Miner = miner ?: run {
        val languages = languages(context, settings)
        val d = dictionary!!.also { d -> if (Dictionary.RECORDINGS.any { !d.recordingsFile(it).exists() }) d.download() }
        Miner(context.applicationContext, languages, d).also { miner = it }
    }
    /**
     * The PC's Japanese Yomitan zips (state\dicts\yomitan-ja, the phone's set) this TV hasn't imported yet (by file
     * name), into its dictionaries: all of them the first time, then the ones added on the PC (the card dictionaries,
     * 2026-10-30). A zip already here by title is replaced in place.
     */
    fun fetchJapanese(context: Context, settings: Settings) {
        if (fetching != null) return
        fetching = tr("Getting the Japanese dictionaries from the PC…")
        val app = context.applicationContext
        val prefs = settings.prefs
        work.launch {
            runCatching {
                val all = JSONArray(URL("${settings.server}/api/models/ja-dicts").readText())
                val names = (0 until all.length()).map { all.getString(it) } - prefs.getStringSet(FETCHED, emptySet()).orEmpty()
                names.forEachIndexed { i, name ->
                    fetching = tr("Getting the Japanese dictionaries from the PC (%1\$d/%2\$d)…", i + 1, names.size)
                    // Named as on the PC: "[Bilingual] JMdict….zip" gives the import its group.
                    val zip = File(app.cacheDir, name)
                    URL("${settings.server}/api/models/ja-dicts/${Uri.encode(name)}").openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
                    yomitan.import(listOf(Uri.fromFile(zip)), Lang.JAPANESE) {}.forEach { Log.i("kumapie", "ja dictionary: $it") }
                    zip.delete()
                    prefs.edit().putStringSet(FETCHED, prefs.getStringSet(FETCHED, emptySet()).orEmpty() + name).apply()
                }
            }.onFailure { Log.w("kumapie", "Japanese dictionaries: $it") }
            fetching = null
        }
    }

    private const val FETCHED = "ja_dicts_fetched"
}

/**
 * A Japanese word card's word, as the phone's automatic card picks it: the PC's dictionary form ([lemma]) looked up
 * (else the longest match from the word), with the PC's [reading] (else the first) and its pitch, the bilingual meaning that fits the line's
 * [english] (else the first), the first monolingual definition.
 */
private fun japaneseWord(y: YomitanDictionaries, text: String, offset: Int, surface: String, key: String, lemma: String?, reading: String,
                         english: String): Miner.Word {
    val ja = Lang.JAPANESE
    val hw = tvHeadword(y, text, offset, lemma, reading) ?: return Miner.Word(surface, key, lemma ?: surface, "")
    val glossaries = hw.terms.flatMap { it.glossaries }
    fun group(dict: String) = y.groupOf(ja, dict)
    val bilingual = glossaries.filter { group(it.dict).contains("bilingual", true) }.ifEmpty {
        glossaries.filterNot { group(it.dict).contains("mono", true) || group(it.dict).contains("form", true) || it.dict.contains("form", true) }
    }
    val meanings = bilingual.flatMap { g -> g.senses.flatMap { JapaneseLookup.bilingualMeanings(it) }.distinct().take(12) }.distinct().take(40)
    val gloss = meanings.getOrNull(SensePick.bestMeaning(meanings, english) ?: 0).orEmpty()
    val mono = glossaries.filter { group(it.dict).contains("mono", true) }
        .firstNotNullOfOrNull { g -> g.senses.firstNotNullOfOrNull { JapaneseLookup.monolingualDefinition(it) } }
    return Miner.Word(surface, key, hw.expression, gloss, reading = hw.reading.takeIf { it.isNotBlank() },
        pitch = hw.pitches.flatMap { it.second }.firstOrNull(), mono = mono)
}

/** The TV's Japanese headword (no Sudachi): the PC's [lemma] looked up, else Yomitan's scan from [offset]; the PC's [reading] picks the entry. */
private fun tvHeadword(y: YomitanDictionaries, text: String, offset: Int, lemma: String?, reading: String): Headword? {
    val ja = Lang.JAPANESE
    val terms = lemma?.let { y.query(ja, it) }.orEmpty().ifEmpty { y.scan(ja, text.substring(offset.coerceIn(0, text.length))).map { it.second } }
    // The PC's reading picks the entry (行く: いく, not ゆく), else the dictionaries' first.
    val first = terms.firstOrNull { reading.isNotBlank() && JapaneseLookup.hiragana(it.reading) == reading } ?: terms.firstOrNull() ?: return null
    return Headword(first.expression, first.reading, terms.filter { it.expression == first.expression && it.reading == first.reading })
}

/**
 * The TV's Japanese word card (user, 2026-11-01: the phone's and chimahon's dictionaries, then a better look), two
 * columns over the room above the picked line: the word (reading with its pitch, the PC's short meaning, Anki state)
 * left; right its entries in this TV's Yomitan dictionaries (the phone's set, fetched from the PC), the card's order
 * first (Daijirin es, Sankoku es, JMdict Spanish, Jitendex), then the rest in import order, with a tab per
 * dictionary that follows the scroll. ↑↓ go a dictionary or a page at a time ([scrollTvDictionaries]).
 */
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
            Text(expression, color = Colors.text, fontSize = 40.sp, lineHeight = 46.sp, style = Arcade.label)
            if (segment.text != expression) Text(segment.text, color = Colors.dim, fontSize = 16.sp)
            (ctl.scene.meanings[key] ?: word.meaning).substringAfter(" = ").takeIf { it.isNotBlank() }?.let {
                Text(it, color = Colors.text, fontSize = 19.sp, maxLines = 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
            val (state, color) = statusLine(word.formCard(segment.text)?.let { word.copy(card = it) } ?: word)
            val shown = word.formCard(segment.text) ?: word.card.takeUnless { word.marked }
            // A due card is the one thing to act on here: a green slab, not a line of small text.
            if (shown == "d") Text(state.uppercase(), color = Arcade.background, fontSize = 12.sp, style = Arcade.label,
                modifier = Modifier.padding(top = 4.dp).background(Colors.due, Slab).padding(horizontal = 12.dp, vertical = 1.dp))
            val looked = if (word.lookups > 0) tr("  ·  looked up %d×", word.lookups) else ""
            Text(if (shown == "d") looked.trimStart(' ', '·') else state + looked, color = color, fontSize = 15.sp)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Colors.dim.copy(alpha = 0.2f)))
        val message = status ?: when (html) { null -> tr("Looking it up…"); "" -> tr("Not in your %s dictionaries.", ctl.lang.displayName); else -> null }
        if (message != null) Text(message, color = Colors.dim, fontSize = 17.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        else DictionaryWeb(html!!, Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp))
    }
    LaunchedEffect(picker.line, picker.seg) {
        TvMining.languages(context, settings)
        val y = TvMining.yomitan
        if (y.of(Lang.JAPANESE).isEmpty()) TvMining.fetchJapanese(context, settings)
        // The first time the dictionaries come from the PC (minutes): say so until they are in.
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
            val dicts = byDict.keys.sortedBy { CardDefinition.rank(it) ?: Int.MAX_VALUE } // stable: the rest keep their order
            val css = GlossaryHtml.styles(y.styles(Lang.JAPANESE).filterKeys { it in byDict })
            val tabs = dicts.joinToString("") { "<span>${GlossaryHtml.esc(dictLabel(it))}</span>" }
            "<style>$css</style><nav id=\"tabs\">$tabs</nav>" + dicts.joinToString("") { d ->
                "<section data-dictionary=\"${GlossaryHtml.esc(d).replace("\"", "&quot;")}\"><h3>${GlossaryHtml.esc(dictLabel(d))}</h3>" +
                    byDict.getValue(d).joinToString("<hr>") { GlossaryHtml.glossary(it, d) } + "</section>"
            }
        }
    }
}

/** "daijirin4-gpt-6-luna es-jp" -> "Daijirin ES", "Jitendex.org [2026-10-03]" -> "Jitendex", "JMdict (Spanish)" -> "JMdict ES". */
private fun dictLabel(title: String): String {
    val name = Regex("[A-Za-z]+").find(title)?.value?.let { if (it.any(Char::isUpperCase)) it else it.replaceFirstChar(Char::uppercase) } ?: title.take(16)
    val lang = when {
        title.contains("es-jp", true) || title.contains("spanish", true) -> " ES"
        title.contains("en-jp", true) -> " EN"
        else -> ""
    }
    return name + lang
}

/** The reading in kana with its pitch drawn over it: a line over the high morae, a drop after the accented one. */
@Composable
private fun PitchReading(kana: String, downstep: Int?) {
    val morae = ArrayList<String>()
    for (c in kana) if (c in "ゃゅょぁぃぅぇぉャュョァィゥェォ" && morae.isNotEmpty()) morae[morae.lastIndex] += c else morae += c.toString()
    fun high(i: Int, n: Int) = when (n) { 0 -> i > 0; 1 -> i == 0; else -> i in 1 until n }
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

/** The dictionaries' HTML in a WebView that never takes the remote's focus; [scrollTvDictionaries] moves it. */
@Composable
private fun DictionaryWeb(html: String, modifier: Modifier) {
    AndroidView(modifier = modifier,
        factory = { ctx -> WebView(ctx).apply {
            setBackgroundColor(0)
            isFocusable = false // the remote stays with the player
            settings.javaScriptEnabled = true // the tabs and ↑↓ (step); the page is ours, the dictionaries' HTML has no scripts
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

/** ↑ (-1) / ↓ (+1) on the dictionaries: the next dictionary, or a page in a long one; false when not shown or at that end. */
fun scrollTvDictionaries(dir: Int): Boolean {
    val w = TvMining.web ?: return false
    if (!w.isAttachedToWindow || !w.canScrollVertically(dir)) return false
    w.evaluateJavascript("step($dir)", null)
    return true
}

/** Colours from the TV's (Arcade): card #0B0A10, text #F2F0F7, dim #A9A4B8, accent #FFD23F; the tabs are slabs. */
private const val PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<style>
body { margin: 0; background: transparent; color: #F2F0F7; font: 19px/1.6 sans-serif; }
#tabs { position: sticky; top: 0; z-index: 1; background: #0B0A10; padding: 2px 4px 10px; display: flex; flex-wrap: wrap; gap: 6px; }
#tabs span { font-size: 13px; font-style: italic; color: #A9A4B8; border: 1px solid rgba(169,164,184,0.35); padding: 1px 12px; transform: skewX(-12deg); }
#tabs span.on { color: #0B0A10; font-weight: 700; background: #FFD23F; border-color: #FFD23F; }
section { padding: 2px 0 14px; margin-bottom: 12px; border-bottom: 1px solid rgba(169,164,184,0.15); }
section:last-of-type { border-bottom: 0; padding-bottom: 40px; }
h3 { margin: 0 0 4px; font-size: 14px; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase; color: #FFD23F; }
ol, ul { margin: 2px 0; padding-left: 1.4em; }
hr { border: 0; border-top: 1px dashed rgba(169,164,184,0.2); margin: 8px 0; }
img { max-width: 100%; }
a { color: #8ab4f8; text-decoration: none; }
table { border-collapse: collapse; } td, th { border: 1px solid rgba(169,164,184,0.25); padding: 2px 6px; }
#fade { position: fixed; left: 0; right: 0; bottom: 0; height: 36px; pointer-events: none;
  background: linear-gradient(rgba(11,10,16,0), #0B0A10); transition: opacity .2s; }
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

/**
 * The video frame on screen, 480 p WebP: a Japanese card's picture. The phone's way (a frame from the stream) gives
 * none on the bedroom TV. Null when the TV won't copy it.
 */
private suspend fun screenFrame(view: PlayerView?, dir: File): File? {
    val surface = view?.videoSurfaceView as? SurfaceView ?: return null
    if (surface.width == 0 || surface.height == 0) return null
    val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
    val copied = suspendCancellableCoroutine { cont ->
        PixelCopy.request(surface, bitmap, { cont.resume(it == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
    }
    if (!copied) return null
    return withContext(Dispatchers.IO) {
        val out = File(File(dir, "mining").apply { mkdirs() }, "tv-shot.webp") // the folder kuma3 may read (file_paths.xml)
        @Suppress("DEPRECATION")
        val webp = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        out.outputStream().use { Bitmap.createScaledBitmap(bitmap, 480 * bitmap.width / bitmap.height, 480, true).compress(webp, 80, it) }
        out
    }
}

/**
 * Hold ↑ in the word picker: the selected word as a word card into kuma3 Anki on this TV (its Mined deck), as the
 * phone's automatic card: this line, the scene clip (Japanese: the line's audio and a picture), the word's audio, the
 * meaning; hold ↑ again on it: the card in kuma3's previewer (Back returns). Ratings: [rateWord]. Japanese meanings
 * come from the phone's dictionaries, fetched from the PC the first time.
 */
fun mineWord(context: Context, settings: Settings, ctl: SceneController, picker: WordPicker, scope: CoroutineScope, view: PlayerView?) {
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    val segment = picker.selected
    val key = segment?.word ?: return say(tr("Pick a word first"))
    val miner = TvMining.miner(context, settings)
    val japanese = TvMining.languages.of(ctl.lang) is Japanese
    if (japanese) {
        TvMining.fetching?.let { return say(it) }
        if (TvMining.yomitan.of(Lang.JAPANESE).isEmpty()) {
            TvMining.fetchJapanese(context, settings)
            return say(tr("Getting the Japanese dictionaries from the PC (once, a few minutes): hold ↑ again after"))
        }
    }
    val anki = AnkiCards(context)
    val pkg = anki.installed().firstOrNull() ?: return say(tr("No kuma3 Anki on this device."))
    if (!anki.hasPermission(pkg)) {
        (context as? Activity)?.requestPermissions(arrayOf(anki.permission(pkg)), 1)
        return say(tr("Allow kumapie to use Anki, then hold ↑ again"))
    }
    TvMining.mined[key]?.let { note ->
        // kuma3's Kuma3Screens "preview" (kuma3 builds before 2026-10-16 ignore it)
        val preview = android.content.Intent("com.ichi2.anki.kuma3.OPEN_SCREEN").setPackage(pkg)
            .putExtra("screen", "preview").putExtra("note_id", note)
        return runCatching { context.startActivity(preview) }.getOrElse { say(it.message ?: it.toString()) }
    }
    val scene = ctl.scene
    val line = picker.line
    val word = ctl.words[key]
    val def = scene.def(line, key)
    val lemma = def?.lemma?.takeIf { it.isNotBlank() } ?: word?.lemma?.takeIf { it.isNotBlank() }
    say(tr("Adding to Anki…"))
    scope.launch {
        runCatching {
            val w = if (japanese) {
                val cue = scene.cues[line]
                val offset = cue.segments.take(picker.seg).sumOf { it.text.length }
                withContext(Dispatchers.IO) { japaneseWord(TvMining.yomitan, cue.text, offset, segment.text, key, lemma, word?.reading.orEmpty(), SensePick.english(scene, line)) }
            } else {
                val gloss = def?.english?.takeIf { it.isNotBlank() } ?: (scene.meanings[key] ?: word?.meaning)?.substringAfter(" = ").orEmpty()
                Miner.Word(segment.text, key, lemma, gloss, null, def)
            }
            val picture = if (japanese) runCatching { screenFrame(view, context.cacheDir) }.getOrNull() else null
            miner.mine(Miner.Request(ctl.episode, scene, line, w, picture)) {}
        }
            .onSuccess {
                TvMining.mined[key] = miner.lastNote
                say(it + tr(" · hold ↑ again: see the card"))
                // Blue now (mined, never reviewed); the PC learns it from the phone after kuma3 syncs.
                ctl.words[key]?.let { w -> ctl.words[key] = w.copy(card = "b") }
                // Its card into the index, or a rating finds none ("kuma3 didn't take the rating", user 2026-11-10).
                val language = TvMining.languages(context, settings).of(ctl.lang)
                runCatching { language.known.addNote(miner.lastNote) }.onSuccess { repaint(language, ctl) }.onFailure { Log.w("kumapie", "mined card not read: $it") }
            }
            .onFailure { say(it.message ?: it.toString()) }
    }
}

/**
 * The words coloured by this TV's kuma3 (blue mined, green due, white studied), as the phone colours them; the PC's
 * colours stay (red) without kuma3 or its permission. Japanese: each written form by its own card too.
 */
suspend fun paintCards(context: Context, settings: Settings, ctl: SceneController) {
    val language = TvMining.languages(context, settings).of(ctl.lang)
    if (!runCatching { language.known.readCards() }.onFailure { Log.w("kumapie", "cards: $it") }.getOrDefault(false)) return
    repaint(language, ctl)
    // Where the due words are (a rating can be tried without hunting for a green word).
    val due = ctl.words.filterValues { it.card == "d" }.keys
    Log.i("kumapie", "kuma3 queue: ${language.known.cardIndex.due?.size} cards (${language.known.cardIndex.due?.values?.groupingBy { it }?.eachCount()}); due words in this episode: " + ctl.episode.scenes.withIndex().flatMap { (i, s) ->
        s.cues.flatMap { it.segments }.mapNotNull { it.word }.filter { it in due }.distinct().map { "$it@${i + 1}" } }.take(40))
}

/**
 * An episode opens: the words are coloured at once from the cards as they were last read (in this run, else saved on
 * the TV), since asking kuma3 takes about a minute on the Chromecast (2026-11-09: 12 s to start, 40 s notes and
 * cards, 22 s its queue). Then kuma3 syncs, its cards are read once and the words coloured again. Ratings wait for
 * that read ([TvMining.reading]): the saved queue can be yesterday's.
 */
suspend fun openCards(context: Context, settings: Settings, ctl: SceneController) {
    val language = TvMining.languages(context, settings).of(ctl.lang)
    TvMining.reading = true
    try {
        val t0 = System.currentTimeMillis()
        val saved = runCatching { language.known.loadCards() }.getOrDefault(false)
        if (saved) {
            repaint(language, ctl)
            Log.i("kumapie", "saved cards: words coloured in ${System.currentTimeMillis() - t0} ms, ${ctl.words.values.count { it.card == "d" }} due in this episode")
            runCatching { language.known.syncAnki(90_000) }.onFailure { Log.w("kumapie", "kuma3 sync: $it") }
            paintCards(context, settings, ctl)
        } else {
            // The first time on this TV: kuma3 is only known after a read, so read, sync, read.
            paintCards(context, settings, ctl)
            if (runCatching { language.known.syncAnki(90_000) }.getOrDefault(false)) paintCards(context, settings, ctl)
        }
    } finally {
        TvMining.reading = false
    }
}

/**
 * kuma3 on a TV is never opened, so it never syncs by itself: kumapie asks it to. When a player opens ([wait]): the
 * phone's reviews and new cards come in and the words are coloured again, unless a word was rated meanwhile ([busy]:
 * reading kuma3's queue replaces its Undo). When it closes: what was mined and rated here goes out.
 */
suspend fun syncAnki(context: Context, settings: Settings, ctl: SceneController, wait: Boolean, busy: () -> Boolean = { false }) {
    val language = TvMining.languages(context, settings).of(ctl.lang)
    val synced = runCatching { language.known.syncAnki(if (wait) 90_000 else 0) }.onFailure { Log.w("kumapie", "kuma3 sync: $it") }.getOrDefault(false)
    if (synced && !busy()) paintCards(context, settings, ctl)
}

/** [syncAnki] when leaving the player: outlives it. */
fun syncAnkiLater(context: Context, settings: Settings, ctl: SceneController) {
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { syncAnki(context.applicationContext, settings, ctl, wait = false) }
}

/** Every word of the episode coloured by the card index as it is now (after [paintCards]' read, a rating, an undo). */
private fun repaint(language: Language, ctl: SceneController) {
    val known = language.known
    val written = if (language !is Japanese) emptyMap() else ctl.episode.scenes.asSequence().flatMap { it.cues }.flatMap { it.segments }
        .filter { it.word != null }.groupBy({ it.word!! }, { it.text })
    // "u" (no card of its own) keeps the PC's colour: the PC knows words without cards (Jiten lists, marked, the
    // phone's parses); the TV has none of those, so "u" drew せっかく, のに... red (user, 2026-10-22).
    for (k in ctl.words.keys.toList()) ctl.words[k]?.let { w ->
        known.paint(k, w.copy(forms = written[k]?.associateWith { "" }))
            .let { p -> p.copy(card = p.card?.takeUnless { it == "u" }, forms = p.forms?.mapValues { (_, s) -> if (s == "u") "" else s }) }
            .let { n -> if (n != w) ctl.words[k] = n }
    }
}

// ------------------------------------------------------------ ratings in the word picker (user, 2026-11-09)

val RATINGS = listOf("Again", "Hard", "Good", "Easy")

/** The card states a word can be rated in: every word with a card of its own (user, 2026-11-09), as on the phone: due (green), new (blue: its first review), studied and not due (white: an early review). */
val RATEABLE = setOf("d", "b", "k")

/**
 * The player's rating state: [open] while the arrows rate the picked word, the last rating (its form) for Undo, and
 * [fresh] while that Undo is offered (until the next word card).
 */
class TvRating {
    var open by mutableStateOf(false)
    var last by mutableStateOf<Pair<String, KnownWords.Rated>?>(null)
    var fresh by mutableStateOf(false)
    /** The last rating's name, in the menu language. */
    var said by mutableStateOf("")
    /** When the picked word's meaning was shown: a rating's answer time. */
    var since = 0L
    var busy = false
    /** Cards reviewed from episodes this study day (it turns at 04:00, as the phone's), kept on the TV. */
    var today by mutableStateOf(0)
        private set
    fun count(settings: Settings, n: Int) {
        val day = "rated_" + java.time.LocalDateTime.now().minusHours(4).toLocalDate()
        today = settings.prefs.getInt(day, 0) + n
        settings.prefs.edit().putInt(day, today).apply()
    }
}

/**
 * The picked word's own card: the form that has it (the dictionary form's, else the written form's, as the subtitle
 * colours it) and its state ("d" = due today).
 */
fun pickedCard(ctl: SceneController, picker: WordPicker): Pair<String, String?>? {
    val segment = picker.selected ?: return null
    val key = segment.word ?: return null
    val w = ctl.words[key] ?: return null
    val own = w.formCard(segment.text)
    return if (own != null) segment.text to own else key to w.card.takeUnless { w.marked }
}

/**
 * Rates the picked word's due cards in this TV's kuma3 ([ease] 1 Again .. 4 Easy), as the phone's word card does: only
 * a due (green) word, after its meaning was shown; one rating answers every due card of the word. kuma3 is not opened.
 * Then the card closes and the picker goes to the next word (a line with several due words: rate, rate, rate).
 */
fun rateWord(context: Context, settings: Settings, ctl: SceneController, picker: WordPicker, scope: CoroutineScope, rating: TvRating, ease: Int) {
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    rating.open = false
    val (form, state) = pickedCard(ctl, picker) ?: return
    if (state !in RATEABLE) return say(tr("This word has no card to rate"))
    if (TvMining.reading) return say(tr("Still reading your cards from Anki: try again in a moment"))
    if (!picker.cardOpen) return say(tr("See its meaning first"))
    if (rating.busy) return
    val language = TvMining.languages(context, settings).of(ctl.lang)
    val ms = System.currentTimeMillis() - rating.since
    rating.busy = true
    scope.launch {
        try {
            val done = language.known.rate(form, ease, ms)
            if (done == null) say(tr("kuma3 didn't take the rating."))
            else {
                rating.last = form to done
                rating.said = tr(RATINGS[ease - 1])
                rating.fresh = true
                rating.count(settings, done.cards)
                repaint(language, ctl)
                picker.right() // closes the card
            }
        } catch (e: Exception) {
            say(e.message ?: e.toString())
        } finally {
            rating.busy = false
        }
    }
}

/** Takes the last rating back with kuma3's Undo (while nothing else was done in kuma3 since): the word is due again. */
fun undoRating(context: Context, settings: Settings, ctl: SceneController, scope: CoroutineScope, rating: TvRating) {
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    rating.open = false
    val (form, rated) = rating.last ?: return say(tr("Nothing to undo"))
    if (rating.busy) return
    val language = TvMining.languages(context, settings).of(ctl.lang)
    rating.busy = true
    scope.launch {
        try {
            if (language.known.undo(rated)) {
                rating.last = null
                rating.fresh = false
                rating.count(settings, -rated.cards)
                language.known.refreshDue(force = true) // due again: the rating took it off the queue kumapie holds
                repaint(language, ctl)
                say(tr("Undone: %s is due again", form))
            } else say(tr("kuma3 didn't undo it (something else was done there since?)."))
        } catch (e: Exception) {
            say(e.message ?: e.toString())
        } finally {
            rating.busy = false
        }
    }
}

/** Characters' names as one word, as on the phone (壬|氏|様 -> 壬氏様): only with a character dictionary on this TV. */
suspend fun joinNames(context: Context, settings: Settings, episode: io.github.pedrubik2000.kumapie.data.EpisodeDetail): io.github.pedrubik2000.kumapie.data.EpisodeDetail {
    val language = TvMining.languages(context, settings).of(episode.lang)
    return withContext(Dispatchers.Default) { language.joinNames(episode) }
}
