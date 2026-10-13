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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.Dp
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** The series' AniList title in the person's language, else its folder name. */
private fun seriesTitle(meta: org.json.JSONObject?, name: String) =
    meta?.let { io.github.pedrubik2000.kumapie.mobile.local.ShowMeta.title(it, Lang.speaker) } ?: name

/** A volume's cover: its first page (from the PC when this device hasn't got it). */
private fun cover(books: Books, book: Book) = book.pages?.firstOrNull()?.let { books.picture(book, it.image) }

/**
 * A manga series, laid out like a show ([ShowScreen]): the first cover fading into the page, title, volumes, pages and
 * Read / Continue; then its volumes with progress, Download and Delete. Tablet: the header left, the volumes right.
 */
@Composable
internal fun MangaSeriesScreen(books: Books, lang: Lang, name: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val volumes = remember(lang, name, tick) { series(books, lang).firstOrNull { it.name == name }?.volumes.orEmpty() }
    LaunchedEffect(volumes.isEmpty()) { if (volumes.isEmpty()) onBack() } // the last one deleted
    if (volumes.isEmpty()) return
    var menu by remember { mutableStateOf<Book?>(null) }
    menu?.let { VolumeMenu(books, it, onClose = { menu = null; tick++ }) }
    var downloading by remember { mutableStateOf<String?>(null) }
    // AniList's entry for the series (title, cover, description): the best match on first open, "Fix match" for another.
    val showMeta = remember { io.github.pedrubik2000.kumapie.mobile.local.ShowMeta(context) }
    var meta by remember(name) { mutableStateOf(books.seriesMeta(name)) }
    var fixing by remember { mutableStateOf(false) }
    val langs = listOf(Lang.speaker, lang.code, "en")
    LaunchedEffect(name) {
        if (meta == null) runCatching {
            withContext(Dispatchers.IO) {
                showMeta.search("manga", name).firstOrNull()?.let { showMeta.save(books.seriesDir(name), it.getString("source"), it.getString("id"), langs) }
            }
        }.onSuccess { meta = books.seriesMeta(name) } // offline: try again next time
    }
    if (fixing) MatchDialog(showMeta, "manga", meta?.optString("original")?.ifBlank { null } ?: name, tr("Fix match"),
        onPick = { _, c -> withContext(Dispatchers.IO) { showMeta.save(books.seriesDir(name), c.getString("source"), c.getString("id"), langs) } },
        onDismiss = { fixing = false; meta = books.seriesMeta(name) })
    fun done(b: Book) = books.pos(b.id) >= b.pages!!.size - 1
    // Read: the volume left partway, else the first not finished, else the first.
    val next = volumes.firstOrNull { books.pos(it.id) > 0 && !done(it) } ?: volumes.firstOrNull { !done(it) } ?: volumes.first()
    val header = @Composable { modifier: Modifier ->
        Box(modifier) {
            val art = if (wide) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 10f)
            AsyncImage(remember(meta) { books.seriesPoster(name) } ?: cover(books, volumes.first()), null, contentScale = ContentScale.Crop, modifier = art)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to Color.Transparent, 0.45f to Colors.background.copy(alpha = 0.35f), 1f to Colors.background)))
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), tint = Color.White)
            }
            TextButton(onClick = { fixing = true }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))) { Text(tr("Fix match"), color = Color.White) }
            Column(Modifier.align(Alignment.BottomStart).let { if (wide) it.navigationBarsPadding() else it }.statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(seriesTitle(meta, name), fontSize = if (wide) 30.sp else 24.sp, lineHeight = if (wide) 36.sp else 30.sp, fontWeight = FontWeight.Bold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                val n = volumes.size
                Text(listOfNotNull(meta?.optInt("year")?.takeIf { it > 0 }?.toString(), if (n == 1) tr("1 volume") else tr("%d volumes", n),
                    tr("%d pages", volumes.sumOf { it.pages!!.size })).joinToString(" · "), fontSize = 14.sp, color = Colors.dim)
                meta?.let { io.github.pedrubik2000.kumapie.mobile.local.ShowMeta.description(it, Lang.speaker) }?.let { text ->
                    var all by remember { mutableStateOf(false) }
                    Text(text, fontSize = 14.sp, maxLines = if (all) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { all = !all })
                }
                Button(onClick = { onOpen(next.id) }) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(if (next != volumes.first() || books.pos(next.id) > 0) tr("Continue: %s", Books.volumeOf(next.title)) else tr("Read"),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
    val row = @Composable { v: Book ->
        val n = v.pages!!.size
        val missing = remember(v.id, tick) { books.missing(v).size }
        val at = books.pos(v.id)
        Row(Modifier.fillMaxWidth().clickable { onOpen(v.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(if (wide) 72.dp else 56.dp)) {
                AsyncImage(cover(books, v), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(6.dp)))
                if (at > 0) ProgressBar((at + 1f) / n, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(Books.volumeOf(v.title), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tr("%d pages", n) + " · " + (if (missing > 0) tr("on the PC") else tr("on this device")), fontSize = 12.sp, color = Colors.dim)
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (downloading == v.id) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else if (missing > 0 && books.onPc(v.id)) IconButton(enabled = downloading == null, onClick = {
                    downloading = v.id
                    scope.launch {
                        runCatching { books.download(v) {} }.onFailure {
                            android.widget.Toast.makeText(context, tr("Couldn't reach the PC: %s", it.message ?: it.toString()), android.widget.Toast.LENGTH_LONG).show()
                        }
                        downloading = null; tick++
                    }
                }) { Icon(Icons.Default.Download, tr("Download")) }
            }
            IconButton(onClick = { menu = v }) { Icon(Icons.Default.Delete, tr("Delete")) }
        }
    }
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Colors.background, contentColor = Colors.text) {
        if (wide) Row(Modifier.fillMaxSize()) {
            header(Modifier.weight(0.42f).fillMaxHeight())
            LazyColumn(Modifier.weight(0.58f).fillMaxHeight(), contentPadding = PaddingValues(vertical = 24.dp)) {
                items(volumes, key = { it.id }) { row(it) }
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") { header(Modifier.fillMaxWidth()) }
            items(volumes, key = { it.id }) { row(it) }
        }
    }
}

/**
 * Manga on this device (Home's Manga), as a library of series (cover, number of volumes); a series opens
 * [MangaSeriesScreen]. Imports .cbz/.zip volumes, a zip of a whole series, or a folder ([Books.import], [Books.importFolder]).
 */
@Composable
internal fun MangaShelf(books: Books, lang: Lang, width: Dp, onSeries: (String?) -> Unit, onOpen: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val list = remember(lang, tick) { series(books, lang) }
    val imports = rememberImports(books, lang, onDone = { ids -> tick++; if (ids.size == 1) onOpen(ids[0]) })
    var menu by remember { mutableStateOf<Book?>(null) }
    menu?.let { VolumeMenu(books, it, onClose = { menu = null; tick++ }) }
    ShelfGrid(width, imports, if (list.isEmpty()) tr("No manga yet.") else null, buttons = {
        TextButton(onClick = { imports.file.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream")) }) {
            Text(tr("Import manga (.cbz, .zip)"))
        }
        TextButton(onClick = { imports.folder.launch(null) }) { Text(tr("Import a folder")) }
    }) {
        items(list, key = { it.name }) { s ->
            val n = s.volumes.size
            Poster(books.seriesPoster(s.name) ?: cover(books, s.volumes.first()), seriesTitle(books.seriesMeta(s.name), s.name), if (n == 1) tr("1 volume") else tr("%d volumes", n), width,
                onLongClick = if (n == 1) ({ menu = s.volumes[0] }) else null) {
                if (n == 1) onOpen(s.volumes[0].id) else onSeries(s.name)
            }
        }
    }
}

/**
 * A volume's long press: "Download" (its pages here, to read without the PC), "Delete here" (only this device's
 * pages; it stays and reads from the PC) and "Delete everywhere" (off the PC and all the owner's devices).
 */
@Composable
private fun VolumeMenu(books: Books, book: Book, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf("") }
    val pages = book.pages!!.size
    val missing = remember(book.id) { books.missing(book).size }
    val onPc = remember(book.id) { books.onPc(book.id) }
    fun fail(e: Throwable) = android.widget.Toast.makeText(context, tr("Couldn't reach the PC: %s", e.message ?: e.toString()), android.widget.Toast.LENGTH_LONG).show()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(book.title) },
        text = {
            Text(listOfNotNull(
                said.takeIf { it.isNotEmpty() },
                if (missing > 0) tr("%d of %d pages are on this device; the others are read from the PC.", pages - missing, pages) else null,
                if (onPc && missing < pages) tr("Delete here: only this device's pages go; the volume stays and reads from the PC.") else null,
                tr("Delete everywhere: off the PC and all your devices."),
            ).joinToString("\n\n"))
        },
        confirmButton = {
            Row {
                if (missing > 0 && onPc) TextButton(onClick = {
                    scope.launch { runCatching { books.download(book) { said = it } }.onSuccess { onClose() }.onFailure { said = ""; fail(it) } }
                }) { Text(tr("Download")) }
                if (onPc && missing < pages) TextButton(onClick = { books.removeCopy(book.id); onClose() }) { Text(tr("Delete here")) }
                TextButton(onClick = {
                    scope.launch { runCatching { withContext(Dispatchers.IO) { books.deleteEverywhere(book.id) } }.onSuccess { onClose() }.onFailure(::fail) }
                }) { Text(tr("Delete everywhere")) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Keep")) } },
    )
}

/** Home's Books and Manga: the import [buttons], the import's progress, [empty] when there's nothing, then posters as on Home. */
@Composable
internal fun ShelfGrid(width: Dp, imports: Imports, empty: String?, buttons: (@Composable RowScope.() -> Unit)? = null, content: LazyGridScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        buttons?.let { Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), content = it) }
        imports.Status()
        empty?.let { Text(it, color = Colors.dim, modifier = Modifier.padding(16.dp)) }
        LazyVerticalGrid(GridCells.FixedSize(width), contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(22.dp), content = content)
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
        val (w, h, blocks) = ocr.read(books.ensurePage(book.id, pages[n].image), n * 1000)
        Manga.Page(pages[n].image, w, h, blocks).also { books.setPage(book.id, n, it) }
    }.also { pages[n] = it }

    LaunchedEffect(shown, landscape) {
        picked = null
        failed = null
        books.setPos(book.id, shown.first())
        val spread = runCatching {
            if (shown.any { !File(book.dir, pages[it].image).exists() }) status = tr("Loading the page from the PC…")
            withContext(Dispatchers.IO) { shown.forEach { books.ensurePage(book.id, pages[it].image) } } // a volume read from the PC
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
        scope.launch {
            for (n in next until minOf(pages.size, next + shown.size)) runCatching {
                withContext(Dispatchers.IO) { books.ensurePage(book.id, pages[n].image) }
                if (pages[n].blocks == null) read(n)
            }
        }
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
    val context = LocalContext.current
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
                .onSuccess { ids -> said = if (ids.size == 1) "" else tr("%d books imported.", ids.size); onDone(ids)
                    io.github.pedrubik2000.kumapie.mobile.local.SyncWorker.now(context) } // to the PC and the other devices
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
