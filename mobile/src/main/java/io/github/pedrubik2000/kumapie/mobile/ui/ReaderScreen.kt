package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.Headword
import io.github.pedrubik2000.kumapie.lang.Japanese
import io.github.pedrubik2000.kumapie.lang.Tap
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.mobile.read.Book
import io.github.pedrubik2000.kumapie.mobile.read.BookPage
import io.github.pedrubik2000.kumapie.mobile.read.Books
import io.github.pedrubik2000.kumapie.mobile.read.Manga
import io.github.pedrubik2000.kumapie.mobile.read.Piece
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Books on this device in the Home language: open one, or import a .txt (one paragraph per line). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksScreen(library: Library, books: Books, homeLang: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val lang = Lang.of(homeLang)
    fun mine() = books.all().filter { it.lang == lang && it.pages == null }
    var list by remember { mutableStateOf(mine()) }
    val imports = rememberImports(books, lang, onDone = { ids -> list = mine(); if (ids.size == 1) onOpen(ids[0]) })
    BackHandler { onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Books")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { imports.file.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream")) }) {
                Text(tr("Import a book: EPUB or text (%s)", lang.displayName))
            }
            TextButton(onClick = { imports.folder.launch(null) }) { Text(tr("Import a folder")) }
            imports.Status()
            if (list.isEmpty()) Text(tr("No books yet."), color = Colors.dim)
            list.forEach { b ->
                Text(b.title, color = Colors.text, fontSize = 18.sp, modifier = Modifier.fillMaxWidth().clickable { onOpen(b.id) }.padding(vertical = 8.dp))
            }
        }
    }
}

/**
 * A book with page turning ([BookPage]: Japanese vertical, right to left; German and English horizontal), words
 * coloured as in subtitles; a tap on a word opens it below (dictionaries, rating rows). The book is split and shown
 * [SECTION] paragraphs at a time; turning past a section's end opens the next. Time here counts as study time (Stats).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(library: Library, books: Books, id: String, onBack: () -> Unit) {
    val book = remember(id) { books.load(id) } ?: return onBack()
    if (book.pages != null) return MangaReader(library, books, book, onBack)
    val language = library.languages.of(book.lang)
    val vertical = book.lang == Lang.JAPANESE
    var at by remember { mutableIntStateOf(books.pos(id).coerceIn(0, maxOf(0, book.paragraphs.size - 1))) } // first paragraph on the page
    var section by remember { mutableIntStateOf(at / SECTION) }
    var atEnd by remember { mutableStateOf(false) } // a backward turn opens the previous section at its last page
    var pieces by remember { mutableStateOf<List<List<Piece>>?>(null) }
    var picked by remember { mutableStateOf<Triple<Int, Int, Float>?>(null) } // paragraph, piece, where on screen (0 top .. 1)
    var failed by remember { mutableStateOf<String?>(null) }
    val web = rememberBookWebView()
    fun unpick() { picked = null; web.evaluateJavascript("select(-1, -1)", null) }
    BackHandler { if (picked != null) unpick() else onBack() }

    val from = section * SECTION
    val texts = remember(section) { book.paragraphs.subList(from, minOf(book.paragraphs.size, from + SECTION)) }
    LaunchedEffect(section) {
        pieces = null
        runCatching { books.pieces(library, book.lang, texts) }.onSuccess { pieces = it }.onFailure { failed = it.message ?: it.toString() }
    }
    LaunchedEffect(pieces) {
        val ps = pieces ?: return@LaunchedEffect
        web.loadDataWithBaseURL(null, BookPage.html(from, texts, ps, language.known::shown, vertical, at, atEnd, COLORS),
            "text/html", "utf-8", null)
    }
    // ponytail: time on this screen counts as reading (no idle detection); per-minute records, capped by Progress.
    LaunchedEffect(id) {
        while (true) {
            delay(60_000)
            library.progress.record(id, at.toDouble(), emptyList(), 60.0)
        }
    }
    BookBridge(web,
        onWord = { p, i, y -> picked = Triple(p, i, y); web.evaluateJavascript("select($p, $i)", null) },
        onPage = { p -> if (p >= 0) { at = p; books.setPos(id, p) } },
        onEdge = { d ->
            val next = section + d
            if (next >= 0 && next * SECTION < book.paragraphs.size) { unpick(); atEnd = d < 0; at = next * SECTION; section = next }
        },
        onMiddle = { unpick() })

    Scaffold(topBar = {
        TopAppBar(title = { Text(book.title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
            actions = { Text("${at + 1} / ${book.paragraphs.size}", color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp)) })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                failed?.let { Text(it, color = Colors.unknown, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
                if (pieces == null && failed == null) Text(tr("Splitting the text into words…"), color = Colors.dim, modifier = Modifier.padding(16.dp))
                androidx.compose.ui.viewinterop.AndroidView({ web }, Modifier.fillMaxSize())
            }
            picked?.let { (p, i, y) ->
                val ps = pieces?.getOrNull(p - from)
                if (ps?.getOrNull(i)?.key != null) Box(Modifier.align(if (y > 0.5f) Alignment.TopCenter else Alignment.BottomCenter).background(Colors.background)) {
                    WordPanel(library, book.lang, book.paragraphs[p], ps, i, book.title) {
                        // After a rating: the section's words take their new colours.
                        val map = org.json.JSONObject()
                        pieces.orEmpty().flatten().mapNotNull { it.key }.toSet().forEach { map.put(it, BookPage.status(language.known.shown(it))) }
                        web.evaluateJavascript("recolor($map)", null)
                    }
                }
            }
        }
    }
}

private const val SECTION = 100

/** background, text, due, mined, unknown, picked: [Colors] as CSS. */
internal val COLORS = listOf(Colors.background, Colors.text, Colors.due, Colors.mined, Colors.unknown, Colors.surface).map { c ->
    "#%06X".format(c.toArgb() and 0xFFFFFF)
}

/** [files]: a manga page, whose images load from the book's folder (its base URL), with pinch zoom. */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun rememberBookWebView(files: Boolean = false): android.webkit.WebView {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        android.webkit.WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = files
            settings.builtInZoomControls = files
            settings.displayZoomControls = false
            setBackgroundColor(Colors.background.toArgb())
            // The page's console.log in logcat (tag kumabook): layout problems show there.
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.i("kumabook", "${m.message()} (${m.lineNumber()})"); return true
                }
            }
            // Our own generated page only: no link leaves it.
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest) = true
            }
        }
    }
}

/** The page's calls ([BookPage.JS_BRIDGE]) → the callbacks, on the main thread. */
@Composable
internal fun BookBridge(web: android.webkit.WebView, onWord: (Int, Int, Float) -> Unit, onPage: (Int) -> Unit, onEdge: (Int) -> Unit, onMiddle: () -> Unit) {
    val word by androidx.compose.runtime.rememberUpdatedState(onWord)
    val page by androidx.compose.runtime.rememberUpdatedState(onPage)
    val edge by androidx.compose.runtime.rememberUpdatedState(onEdge)
    val middle by androidx.compose.runtime.rememberUpdatedState(onMiddle)
    androidx.compose.runtime.DisposableEffect(web) {
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface fun word(p: Int, i: Int, y: Float) { web.post { word(p, i, y) } }
            @android.webkit.JavascriptInterface fun page(p: Int) { web.post { page(p) } }
            @android.webkit.JavascriptInterface fun edge(d: Int) { web.post { edge(d) } }
            @android.webkit.JavascriptInterface fun middle() { web.post { middle() } }
        }, BookPage.JS_BRIDGE)
        onDispose { web.removeJavascriptInterface(BookPage.JS_BRIDGE); web.destroy() }
    }
}

/** The picked word: its dictionaries (the popup) and its rating rows; Japanese can make a word card for a missing form. */
@Composable
internal fun WordPanel(library: Library, lang: Lang, text: String, pieces: List<Piece>, i: Int, source: String,
                      picture: (suspend () -> java.io.File?)? = null, repaint: () -> Unit) {
    val language = library.languages.of(lang)
    val offset = pieces.take(i).sumOf { it.text.length }
    val piece = pieces[i]
    val sentence = remember(text, offset) { Books.sentenceAt(text, offset) }
    val tap = Tap(text, offset, piece.text.length, piece.key)
    var headwords by remember(tap) { mutableStateOf<List<Headword>?>(null) }
    var forms by remember(tap) { mutableStateOf<List<RateForm>>(emptyList()) }
    val ratings: Ratings = remember { mutableStateMapOf() }
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateOf(false) }
    LaunchedEffect(tap) {
        headwords = runCatching { language.lookup(tap) }.getOrDefault(emptyList())
        // A form without a card of its own gets one first: this sentence, no audio or clip (Miner.mineText).
        forms = if (language is Japanese) {
            val found = runCatching { language.forms(text, offset) }.getOrDefault(emptyList())
            japaneseRateForms(library, found, null) { w, _ -> library.miner.mineText(lang, sentence, w, source, picture?.invoke()) }
        } else listOf(RateForm(piece.key!!, null) { _ ->
            // The meaning and monolingual definition as the mine sheet picks them at first (no line English to fit here).
            val all = language.entries(Tap(piece.text, 0, piece.text.length, piece.key))
            val mono = { e: io.github.pedrubik2000.kumapie.lang.DictEntry -> library.yomitan.groupOf(lang, e.dict) == "Monolingual" }
            val entry = all.filterNot(mono).ifEmpty { all }.firstOrNull()
            val sense = entry?.senses?.firstOrNull()
            library.miner.mineText(lang, sentence, io.github.pedrubik2000.kumapie.lang.Miner.Word(piece.text, piece.key, entry?.word,
                sense?.gloss.orEmpty(), sense?.examples?.firstOrNull(), mono = all.firstOrNull(mono)?.senses?.firstOrNull()?.gloss),
                source, picture?.invoke())
        })
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider(color = Colors.dim.copy(alpha = 0.3f))
        if (forms.isNotEmpty()) RatingRows(language.known, library.settings.prefs, forms, ratings, scope, busy, repaint = repaint)
        when (val hws = headwords) {
            null -> Text(tr("Looking it up…"), color = Colors.dim, fontSize = 14.sp)
            emptyList<Headword>() -> Text(tr("Not in the dictionary."), color = Colors.dim, fontSize = 14.sp)
            else -> YomitanPopup(library, lang, hws, onSpeak = language::say, compact = true)
        }
    }
}
