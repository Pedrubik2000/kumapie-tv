package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.mobile.read.Book
import io.github.pedrubik2000.kumapie.mobile.read.BookPage
import io.github.pedrubik2000.kumapie.mobile.read.Books
import io.github.pedrubik2000.kumapie.mobile.read.Manga
import io.github.pedrubik2000.kumapie.mobile.read.MangaOcr
import io.github.pedrubik2000.kumapie.mobile.read.Piece
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A manga series on this device: its volumes in order (by title). */
private class Series(val name: String, val volumes: List<Book>)

private fun series(books: Books, lang: Lang) = books.all().filter { it.lang == lang && it.pages != null }
    .groupBy { Books.seriesOf(it.title) }.map { (name, vols) -> Series(name, vols.sortedBy { it.title.lowercase() }) }
    .sortedBy { it.name.lowercase() }

/** A volume's cover: its first page. */
private fun cover(book: Book) = book.pages?.firstOrNull()?.let { File(book.dir, it.image) }

/**
 * Manga on this device, as a library of series (one card each: cover, number of volumes); a series opens its volumes.
 * Imports .cbz/.zip volumes, a zip of a whole series, or a folder ([Books.import], [Books.importFolder]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaScreen(library: Library, books: Books, homeLang: String, onSeries: (String) -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val lang = Lang.of(homeLang)
    var list by remember { mutableStateOf(series(books, lang)) }
    val imports = rememberImports(books, lang, onDone = { ids -> list = series(books, lang); if (ids.size == 1) onOpen(ids[0]) })
    BackHandler { onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Manga")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Row {
                TextButton(onClick = { imports.file.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream")) }) {
                    Text(tr("Import manga (.cbz, .zip)"))
                }
                TextButton(onClick = { imports.folder.launch(null) }) { Text(tr("Import a folder")) }
            }
            imports.Status()
            if (list.isEmpty()) Text(tr("No manga yet."), color = Colors.dim, modifier = Modifier.padding(8.dp))
            LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.name }) { s ->
                    Card(cover(s.volumes.first()), s.name, s.volumes.size.toString()) {
                        if (s.volumes.size == 1) onOpen(s.volumes[0].id) else onSeries(s.name)
                    }
                }
            }
        }
    }
}

/** One series' volumes, as covers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaSeriesScreen(books: Books, homeLang: String, name: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val volumes = remember(name) { series(books, Lang.of(homeLang)).firstOrNull { it.name == name }?.volumes.orEmpty() }
    BackHandler { onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(name, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
    }) { padding ->
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(padding).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(volumes, key = { it.id }) { v ->
                val at = books.pos(v.id)
                Card(cover(v), Books.volumeOf(v.title), if (at > 0) "${at + 1}/${v.pages!!.size}" else null) { onOpen(v.id) }
            }
        }
    }
}

@Composable
private fun Card(image: File?, title: String, badge: String?, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)).background(Colors.surface).clickable(onClick = onClick)) {
        if (image != null) AsyncImage(image, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Text(title, color = Colors.text, fontSize = 15.sp, maxLines = 2, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f)).padding(8.dp))
        badge?.let {
            Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                .background(Colors.mined, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/**
 * A manga volume ([Manga]): one page upright, two-page spreads in landscape (right to left; the shift button moves
 * the pairing by one page, kept per volume). A tap on a bubble shows its words; a tap on a word opens it in a panel over
 * the far half of the page (the page doesn't move). Pages without a .mokuro are read on the device the first time they
 * are shown ([MangaOcr]) and kept; the next pages are read ahead. Making a card asks for the picture: a crop of the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaReader(library: Library, books: Books, book: Book, onBack: () -> Unit) {
    val pages = remember { mutableStateListOf(*book.pages!!.toTypedArray()) }
    val language = library.languages.of(book.lang)
    val context = LocalContext.current
    val ocr = remember { MangaOcr(context) }
    val prefs = library.settings.prefs
    val landscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    var shift by remember { mutableStateOf(prefs.getBoolean("manga_shift_${book.id}", false)) }
    /** The spread page [n] is in: its first page and its pages. */
    fun spread(n: Int): List<Int> {
        if (!landscape) return listOf(n)
        val start = if (!shift) n - n % 2 else if (n == 0) 0 else n - (n + 1) % 2
        return if (shift && start == 0) listOf(0) else listOf(start, start + 1).filter { it < pages.size }
    }
    var at by remember { mutableIntStateOf(books.pos(book.id).coerceIn(0, pages.size - 1)) }
    val shown = spread(at)
    var pieces by remember { mutableStateOf<Map<Int, List<Piece>>?>(null) }
    var picked by remember { mutableStateOf<Triple<Int, Int, Float>?>(null) } // block, piece, where on screen (0 top .. 1)
    var failed by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var crop by remember { mutableStateOf<CropAsk?>(null) }
    val web = rememberBookWebView(files = true)
    val scope = rememberCoroutineScope()
    fun unpick() { picked = null; web.evaluateJavascript("select(-1, -1)", null) }
    BackHandler { if (picked != null) unpick() else onBack() }

    /** Page [n] with its bubbles: as stored, else read now (and kept). */
    suspend fun read(n: Int): Manga.Page = pages[n].takeIf { it.blocks != null } ?: withContext(Dispatchers.IO) {
        if (!ocr.isReady) ocr.download { status = it }
        val (w, h, blocks) = ocr.read(File(book.dir, pages[n].image), n * 1000)
        Manga.Page(pages[n].image, w, h, blocks).also { books.setPage(book.id, n, it) }
    }.also { pages[n] = it }

    LaunchedEffect(shown, landscape) {
        picked = null
        failed = null
        books.setPos(book.id, shown.first())
        val spread = runCatching {
            if (shown.any { pages[it].blocks == null }) status = tr("Reading the page…")
            shown.map { read(it) }
        }.onFailure { android.util.Log.w("kumabook", "manga page ${shown.first() + 1}", it); failed = it.message ?: it.toString() }.getOrNull()
        status = ""
        val blocks = spread.orEmpty().flatMap { it.blocks.orEmpty() }
        val split = runCatching { books.pieces(library, book.lang, blocks.map { it.text }) }
            .onFailure { failed = it.message ?: it.toString() }.getOrDefault(blocks.map { emptyList() })
        pieces = blocks.mapIndexed { i, b -> b.p to split[i] }.toMap()
        if (spread != null) web.loadDataWithBaseURL(book.dir!!.toURI().toString(), Manga.html(spread, pieces!!, language.known::shown, COLORS),
            "text/html", "utf-8", null)
        // Read the next pages ahead, so turning to them doesn't wait.
        val next = shown.last() + 1
        scope.launch { for (n in next until minOf(pages.size, next + shown.size)) if (pages[n].blocks == null) runCatching { read(n) } }
    }
    // ponytail: time on this screen counts as reading (no idle detection), as for books.
    LaunchedEffect(book.id) {
        while (true) {
            delay(60_000)
            library.progress.record(book.id, at.toDouble(), emptyList(), 60.0)
        }
    }
    BookBridge(web,
        onWord = { p, i, y -> picked = Triple(p, i, y); web.evaluateJavascript("select($p, $i)", null) },
        onPage = {},
        onEdge = { d ->
            val n = if (d > 0) shown.last() + 1 else spread(shown.first() - 1).firstOrNull() ?: -1
            if (n in pages.indices) at = n
        },
        onMiddle = { unpick() })

    Scaffold(topBar = {
        TopAppBar(title = { Text(book.title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
            actions = {
                if (landscape) IconButton(onClick = { shift = !shift; prefs.edit().putBoolean("manga_shift_${book.id}", shift).apply() }) {
                    Icon(Icons.Default.SwapHoriz, tr("Shift the pages by one"))
                }
                Text(shown.joinToString("-") { "${it + 1}" } + " / ${pages.size}", color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
            })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.ui.viewinterop.AndroidView({ web }, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopCenter)) {
                failed?.let { Text(it, color = Colors.unknown, fontSize = 13.sp, modifier = Modifier.background(Colors.background).padding(16.dp)) }
                if (status.isNotEmpty()) Text(status, color = Colors.dim, fontSize = 13.sp, modifier = Modifier.background(Colors.background).padding(16.dp))
            }
            picked?.let { (p, i, y) ->
                val page = pages.getOrNull(p / 1000)
                val block = page?.blocks?.firstOrNull { it.p == p }
                val ps = pieces?.get(p)
                if (page != null && block != null && ps?.getOrNull(i)?.key != null) {
                    // Over the half of the screen the word isn't on: the page stays where it is.
                    Box(Modifier.align(if (y > 0.5f) Alignment.TopCenter else Alignment.BottomCenter).background(Colors.background)) {
                        WordPanel(library, book.lang, block.text, ps, i, "${book.title}, p. ${p / 1000 + 1}", {
                            val answer = CompletableDeferred<List<Double>?>()
                            crop = CropAsk(File(book.dir, page.image), page, Manga.around(block, page), answer)
                            val rect = answer.await()
                            crop = null
                            rect?.let { r -> withContext(Dispatchers.IO) { Manga.crop(File(book.dir, page.image), r, File(context.cacheDir, "mining").apply { mkdirs() }) } }
                        }) {
                            val map = org.json.JSONObject()
                            pieces.orEmpty().values.flatten().mapNotNull { it.key }.toSet().forEach { map.put(it, BookPage.status(language.known.shown(it))) }
                            web.evaluateJavascript("recolor($map)", null)
                        }
                    }
                }
            }
        }
    }
    crop?.let { CropDialog(it) }
}

/** A card's picture to choose: [image] (its [page]), the box suggested at first; [answer] gets the box or null (none). */
private class CropAsk(val image: File, val page: Manga.Page, val start: List<Double>, val answer: CompletableDeferred<List<Double>?>)

/**
 * The card's picture: the page with a box to move (drag inside) or resize (drag a corner), starting around the
 * bubble; "Use this", "Whole page" or "No picture".
 */
@Composable
private fun CropDialog(ask: CropAsk) {
    val bitmap = remember(ask.image) { android.graphics.BitmapFactory.decodeFile(ask.image.path) }
    var rect by remember(ask) { mutableStateOf(ask.start) } // x1, y1, x2, y2 in page pixels
    var view by remember { mutableStateOf(IntSize.Zero) }
    Dialog(onDismissRequest = { ask.answer.complete(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Colors.background)) {
            Text(tr("The card's picture: move the box, or drag a corner"), color = Colors.text, modifier = Modifier.padding(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { view = it }) {
                if (bitmap != null && view.width > 0) {
                    val w = bitmap.width.toDouble(); val h = bitmap.height.toDouble()
                    val s = minOf(view.width / w, view.height / h).toFloat()
                    val ox = (view.width - w.toFloat() * s) / 2; val oy = (view.height - h.toFloat() * s) / 2
                    Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    var mode by remember { mutableIntStateOf(-1) } // 0..3 a corner (tl, tr, br, bl), 4 move
                    Canvas(Modifier.fillMaxSize().pointerInput(s) {
                        detectDragGestures(onDragStart = { at ->
                            val (x1, y1, x2, y2) = rect.map { it.toFloat() }
                            val corners = listOf(Offset(ox + x1 * s, oy + y1 * s), Offset(ox + x2 * s, oy + y1 * s),
                                Offset(ox + x2 * s, oy + y2 * s), Offset(ox + x1 * s, oy + y2 * s))
                            val near = corners.indexOfFirst { (it - at).getDistance() < 48.dp.toPx() }
                            mode = if (near >= 0) near else 4
                        }) { change, d ->
                            change.consume()
                            val dx = (d.x / s).toDouble(); val dy = (d.y / s).toDouble()
                            var (x1, y1, x2, y2) = rect
                            when (mode) {
                                0 -> { x1 += dx; y1 += dy }
                                1 -> { x2 += dx; y1 += dy }
                                2 -> { x2 += dx; y2 += dy }
                                3 -> { x1 += dx; y2 += dy }
                                else -> {
                                    val mx = dx.coerceIn(-x1, w - x2); val my = dy.coerceIn(-y1, h - y2)
                                    x1 += mx; x2 += mx; y1 += my; y2 += my
                                }
                            }
                            val min = 24.0
                            rect = listOf(x1.coerceIn(0.0, x2 - min), y1.coerceIn(0.0, y2 - min), x2.coerceIn(x1 + min, w), y2.coerceIn(y1 + min, h))
                        }
                    }) {
                        val (x1, y1, x2, y2) = rect.map { it.toFloat() }
                        val l = ox + x1 * s; val t = oy + y1 * s; val r = ox + x2 * s; val b = oy + y2 * s
                        val dim = Color.Black.copy(alpha = 0.55f)
                        drawRect(dim, Offset(0f, 0f), Size(size.width, t))
                        drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
                        drawRect(dim, Offset(0f, t), Size(l, b - t))
                        drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
                        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(3.dp.toPx()))
                        for (c in listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))) drawCircle(Color.White, 10.dp.toPx(), c)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { ask.answer.complete(null) }) { Text(tr("No picture")) }
                TextButton(onClick = { ask.answer.complete(listOf(0.0, 0.0, ask.page.width.toDouble(), ask.page.height.toDouble())) }) { Text(tr("Whole page")) }
                TextButton(onClick = { ask.answer.complete(rect) }) { Text(tr("Use this")) }
            }
        }
    }
}

/** A file or folder import with its progress line ([Status]); [onDone] gets the new books' ids. */
internal class Imports(val file: androidx.activity.result.ActivityResultLauncher<Array<String>>,
                       val folder: androidx.activity.result.ActivityResultLauncher<android.net.Uri?>,
                       private val said: () -> String, private val bad: () -> Boolean) {
    @Composable
    fun Status() {
        val s = said()
        if (s.isNotEmpty()) Text(s, color = if (bad()) Colors.unknown else Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
internal fun rememberImports(books: Books, lang: Lang, onDone: (List<String>) -> Unit): Imports {
    var said by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(job: suspend ((String) -> Unit) -> List<String>) {
        if (importing) return
        scope.launch {
            importing = true
            bad = false
            said = tr("Importing…")
            runCatching { job { said = it } }
                .onSuccess { ids -> said = if (ids.size == 1) "" else tr("%d books imported.", ids.size); onDone(ids) }
                .onFailure { bad = true; said = it.message ?: it.toString() }
            importing = false
        }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run { p -> books.import(uri, lang, p) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) run { p -> books.importFolder(tree, lang, p) }
    }
    return Imports(file, folder, { said }, { bad })
}
