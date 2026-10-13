package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
    var list by remember { mutableStateOf(books.all().filter { it.lang == lang }) }
    var said by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var bad by remember { mutableStateOf(false) } // [said] is an error
    val scope = rememberCoroutineScope()
    /** Runs an import: one book opens, several are listed ("%d books imported"). */
    fun run(job: suspend ((String) -> Unit) -> List<String>) {
        if (importing) return
        scope.launch {
            importing = true
            bad = false
            said = tr("Importing…")
            runCatching { job { said = it } }
                .onSuccess { ids ->
                    list = books.all().filter { it.lang == lang }
                    said = if (ids.size == 1) "" else tr("%d books imported.", ids.size)
                    if (ids.size == 1) onOpen(ids[0])
                }
                .onFailure { bad = true; said = it.message ?: it.toString() }
            importing = false
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run { p -> books.import(uri, lang, p) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) run { p -> books.importFolder(tree, lang, p) }
    }
    BackHandler { onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Books")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { pick.launch(arrayOf("application/epub+zip", "text/plain", "application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream")) }) { Text(tr("Import a book: EPUB, text or manga (%s)", lang.displayName)) }
            TextButton(onClick = { pickFolder.launch(null) }) { Text(tr("Import a folder (every book or volume in it)")) }
            if (said.isNotEmpty()) Text(said, color = if (bad) Colors.unknown else Colors.dim, fontSize = 13.sp)
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
    var picked by remember { mutableStateOf<Pair<Int, Int>?>(null) } // paragraph, piece
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
        onWord = { p, i -> picked = p to i; web.evaluateJavascript("select($p, $i)", null) },
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
    }, bottomBar = {
        val (p, i) = picked ?: return@Scaffold
        val ps = pieces?.getOrNull(p - from) ?: return@Scaffold
        if (ps.getOrNull(i)?.key != null) WordPanel(library, book.lang, book.paragraphs[p], ps, i, book.title) {
            // After a rating: the section's words take their new colours.
            val map = org.json.JSONObject()
            pieces.orEmpty().flatten().mapNotNull { it.key }.toSet().forEach { map.put(it, BookPage.status(language.known.shown(it))) }
            web.evaluateJavascript("recolor($map)", null)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            failed?.let { Text(it, color = Colors.unknown, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            if (pieces == null && failed == null) Text(tr("Splitting the text into words…"), color = Colors.dim, modifier = Modifier.padding(16.dp))
            androidx.compose.ui.viewinterop.AndroidView({ web }, Modifier.fillMaxSize())
        }
    }
}

private const val SECTION = 100

/** background, text, due, mined, unknown, picked: [Colors] as CSS. */
private val COLORS = listOf(Colors.background, Colors.text, Colors.due, Colors.mined, Colors.unknown, Colors.surface).map { c ->
    "#%06X".format(c.toArgb() and 0xFFFFFF)
}

/**
 * A manga volume ([Manga]): one page at a time, its speech bubbles over the picture (a tap shows a bubble's words,
 * a tap on a word opens it below). Turning as in Japanese manga; pinch to zoom. `pos.txt` keeps the page. A page
 * without a .mokuro is read on the device the first time it is shown ([MangaOcr], the model downloaded once), kept in
 * book.json, and the next page is read ahead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaReader(library: Library, books: Books, book: Book, onBack: () -> Unit) {
    val pages = remember { androidx.compose.runtime.mutableStateListOf(*book.pages!!.toTypedArray()) }
    val language = library.languages.of(book.lang)
    val context = androidx.compose.ui.platform.LocalContext.current
    val ocr = remember { io.github.pedrubik2000.kumapie.mobile.read.MangaOcr(context) }
    var page by remember { mutableIntStateOf(books.pos(book.id).coerceIn(0, pages.size - 1)) }
    var pieces by remember { mutableStateOf<Map<Int, List<Piece>>?>(null) }
    var picked by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    val web = rememberBookWebView(files = true)
    val scope = rememberCoroutineScope()
    fun unpick() { picked = null; web.evaluateJavascript("select(-1, -1)", null) }
    BackHandler { if (picked != null) unpick() else onBack() }

    /** Page [n] with its bubbles: as stored, else read now (and kept). */
    suspend fun read(n: Int): Manga.Page = pages[n].takeIf { it.blocks != null } ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!ocr.isReady) ocr.download { status = it }
        val (w, h, blocks) = ocr.read(java.io.File(book.dir, pages[n].image), n * 1000)
        Manga.Page(pages[n].image, w, h, blocks).also { books.setPage(book.id, n, it) }
    }.also { pages[n] = it }

    LaunchedEffect(page) {
        picked = null
        failed = null
        books.setPos(book.id, page)
        val shown = runCatching {
            if (pages[page].blocks == null) status = tr("Reading the page…")
            read(page)
        }.onFailure { android.util.Log.w("kumabook", "manga page ${page + 1}", it); failed = it.message ?: it.toString() }.getOrNull()
        status = ""
        val blocks = shown?.blocks.orEmpty()
        val split = runCatching { books.pieces(library, book.lang, blocks.map { it.text }) }
            .onFailure { failed = it.message ?: it.toString() }.getOrDefault(blocks.map { emptyList() })
        pieces = blocks.mapIndexed { i, b -> b.p to split[i] }.toMap()
        if (shown != null) web.loadDataWithBaseURL(book.dir!!.toURI().toString(), Manga.html(shown, pieces!!, language.known::shown, COLORS),
            "text/html", "utf-8", null)
        // Read the next page ahead, so turning to it doesn't wait.
        if (page + 1 < pages.size && pages[page + 1].blocks == null) scope.launch { runCatching { read(page + 1) } }
    }
    // ponytail: time on this screen counts as reading (no idle detection), as for books.
    LaunchedEffect(book.id) {
        while (true) {
            delay(60_000)
            library.progress.record(book.id, page.toDouble(), emptyList(), 60.0)
        }
    }
    BookBridge(web,
        onWord = { p, i -> picked = p to i; web.evaluateJavascript("select($p, $i)", null) },
        onPage = {},
        onEdge = { d -> if (page + d in pages.indices) page += d },
        onMiddle = { unpick() })

    Scaffold(topBar = {
        TopAppBar(title = { Text(book.title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
            actions = { Text("${page + 1} / ${pages.size}", color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp)) })
    }, bottomBar = {
        val (p, i) = picked ?: return@Scaffold
        val ps = pieces?.get(p) ?: return@Scaffold
        val text = pages.getOrNull(p / 1000)?.blocks?.firstOrNull { it.p == p }?.text ?: return@Scaffold
        val block = pages[p / 1000].blocks!!.first { it.p == p }
        if (ps.getOrNull(i)?.key != null) WordPanel(library, book.lang, text, ps, i, "${book.title}, p. ${p / 1000 + 1}",
            { Manga.crop(java.io.File(book.dir, pages[p / 1000].image), block, java.io.File(context.cacheDir, "mining").apply { mkdirs() }) }) {
            val map = org.json.JSONObject()
            pieces.orEmpty().values.flatten().mapNotNull { it.key }.toSet().forEach { map.put(it, BookPage.status(language.known.shown(it))) }
            web.evaluateJavascript("recolor($map)", null)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            failed?.let { Text(it, color = Colors.unknown, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            if (status.isNotEmpty()) Text(status, color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            androidx.compose.ui.viewinterop.AndroidView({ web }, Modifier.fillMaxSize())
        }
    }
}

/** [files]: a manga page, whose images load from the book's folder (its base URL), with pinch zoom. */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun rememberBookWebView(files: Boolean = false): android.webkit.WebView {
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
private fun BookBridge(web: android.webkit.WebView, onWord: (Int, Int) -> Unit, onPage: (Int) -> Unit, onEdge: (Int) -> Unit, onMiddle: () -> Unit) {
    val word by androidx.compose.runtime.rememberUpdatedState(onWord)
    val page by androidx.compose.runtime.rememberUpdatedState(onPage)
    val edge by androidx.compose.runtime.rememberUpdatedState(onEdge)
    val middle by androidx.compose.runtime.rememberUpdatedState(onMiddle)
    androidx.compose.runtime.DisposableEffect(web) {
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface fun word(p: Int, i: Int) { web.post { word(p, i) } }
            @android.webkit.JavascriptInterface fun page(p: Int) { web.post { page(p) } }
            @android.webkit.JavascriptInterface fun edge(d: Int) { web.post { edge(d) } }
            @android.webkit.JavascriptInterface fun middle() { web.post { middle() } }
        }, BookPage.JS_BRIDGE)
        onDispose { web.removeJavascriptInterface(BookPage.JS_BRIDGE); web.destroy() }
    }
}

/** The picked word: its dictionaries (the popup) and its rating rows; Japanese can make a word card for a missing form. */
@Composable
private fun WordPanel(library: Library, lang: Lang, text: String, pieces: List<Piece>, i: Int, source: String,
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
