package io.github.pedrubik2000.kumapie.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.Tr
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.launch

const val ALONE_LANG = "alone_lang"

@Composable
fun TvOnboarding(settings: Settings, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var lang by remember { mutableStateOf(settings.menuLanguage) }
    var needKey by remember { mutableStateOf(false) }
    remember(lang) { Tr.use(lang) }
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tr("Step %1\$d of %2\$d", step + 1, STEPS), color = Colors.dim, fontSize = 16.sp)
        when (step) {
            0 -> {
                Heading(tr("Languages"))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("" to tr("The TV's language"), "en" to "English", "es" to "Español").forEachIndexed { i, (code, label) ->
                        Button(onClick = {
                            lang = code
                            settings.menuLanguage = code
                            if (code.isNotEmpty()) settings.speaks = code
                        }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            Text((if (lang == code) "✓ " else "") + label)
                        }
                    }
                }
            }
            1 -> TvJitenImport(settings)
            2 -> {
                PhonePage(settings, onSaved = { if (settings.jimakuKey.isNotBlank()) needKey = false })
                if (needKey) Text(tr("Add your Jimaku key from your phone first: without it, streams get no Japanese lines."), color = Colors.accent, fontSize = 18.sp)
            }
            else -> TvDownloads(settings)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (step > 0) Button(onClick = { step-- }) { Text(tr("Back")) }
            Button(onClick = {
                when {
                    step == PHONE_STEP && settings.jimakuKey.isBlank() -> needKey = true
                    step < STEPS - 1 -> step++
                    else -> { io.github.pedrubik2000.kumapie.data.Startup.onboard(settings.prefs); onDone() }
                }
            },
                modifier = if (step > 0) Modifier.focusRequester(first) else Modifier) {
                Text((if (step < STEPS - 1) tr("Next") else tr("Start watching")))
            }
        }
    }
    LaunchedEffect(step) { runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler(enabled = step > 0) { step-- }
}

private const val STEPS = 4
private const val PHONE_STEP = 2

@Composable
internal fun TvJitenImport(settings: Settings) {
    val context = LocalContext.current
    val known = remember { japaneseKnown(context, settings) }
    val jiten = io.github.pedrubik2000.kumapie.lang.Jiten
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val last = remember { known.jitenChoice }
    var top by remember { mutableIntStateOf(last?.first ?: 10000) }
    val sets = remember { androidx.compose.runtime.mutableStateMapOf(*jiten.SETS.keys.map { it to (last?.second?.contains(it) ?: true) }.toTypedArray()) }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf(known.importedWords.size.takeIf { it > 0 }?.let { tr("%d words imported as known.", it) } ?: "") }
    Heading(tr("Words you know"))
    Text(tr("Already know some Japanese? Import the most frequent words as known, so the subtitles colour only what's new. Skip it if you're starting out."),
        color = Colors.text, fontSize = 18.sp)
    Text(tr("Most frequent words"), color = Colors.dim, fontSize = 16.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(0, 1000, 5000, 10000, 20000).forEach { n ->
            Button(onClick = { top = n }) { Text((if (top == n) "✓ " else "") + if (n == 0) tr("None") else "%,d".format(n)) }
        }
    }
    jiten.SETS.entries.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            row.forEach { (slug, name) ->
                Button(onClick = { sets[slug] = sets[slug] != true }) { Text((if (sets[slug] == true) "✓ " else "") + tr(name)) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = {
            if (busy) return@Button
            busy = true
            said = tr("Downloading from jiten.moe…")
            scope.launch {
                said = runCatching { tr("%d words imported as known.", known.importJiten(top, sets.filterValues { it }.keys.toSet())) }
                    .getOrElse { it.message ?: it.toString() }
                busy = false
            }
        }) { Text(tr("Import")) }
        if (known.importedWords.isNotEmpty()) Button(onClick = { if (!busy) { known.removeImported(); said = "" } }) { Text(tr("Remove imported")) }
    }
    if (said.isNotEmpty()) Text(said, color = Colors.text, fontSize = 18.sp)
}

@Composable
internal fun PhonePage(settings: Settings, onSaved: () -> Unit = {}) {
    var saved by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val page = remember { SetupPage(settings, context.applicationContext) { saved++; onSaved() } }
    val url = remember { page.start() }
    DisposableEffect(Unit) { onDispose { page.stop() } }
    Heading(tr("Set up kumapie from your phone"))
    if (url == null) return Text(tr("This TV isn't on a home network: connect it, or add the keys in Settings later."), color = Colors.dim)
    Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        val qr = remember(url) { qr(url, 480) }
        Image(qr.asImageBitmap(), null, Modifier.size(260.dp).background(Color.White).padding(10.dp))
        Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Scan this with your phone's camera (on the same Wi-Fi), or open:"), color = Colors.text, fontSize = 18.sp)
            Text(url, color = Colors.accent, fontSize = 18.sp)
            Text(tr("There: your Jimaku key, your Real-Debrid token, Kitsu and your AniList username."), color = Colors.dim, fontSize = 16.sp)
            @Suppress("UNUSED_EXPRESSION") saved
            val p = settings.prefs
            @Composable fun row(ok: Boolean, what: String) = Text((if (ok) "✓ " else "· ") + what, color = if (ok) Colors.text else Colors.dim, fontSize = 18.sp)
            row(settings.jimakuKey.isNotEmpty(), tr("Japanese subtitles (Jimaku)"))
            row(settings.addons.any { "torrentio.strem.fun" in it }, tr("Videos (Torrentio with Real-Debrid)"))
            row(Addons.KITSU in settings.addons, "Kitsu")
            row(Addons.OPENSUBTITLES in settings.addons, tr("English subtitles (OpenSubtitles)"))
            row(settings.anilist.isNotEmpty(), "AniList" + settings.anilist.let { if (it.isEmpty()) "" else ": $it" })
        }
    }
}

@Composable
private fun TvDownloads(settings: Settings) {
    val context = LocalContext.current
    Heading(tr("Downloads"))
    Text(tr("Two downloads, once: the Japanese dictionaries (meanings, about 33 MB) and the word splitter (about 80 MB). You can start watching while they finish."),
        color = Colors.text, fontSize = 18.sp)
    LaunchedEffect(Unit) { startJapaneseDictionaries(context, settings) }
    val model = remember { io.github.pedrubik2000.kumapie.lang.JapaneseModel(context.applicationContext) }
    LaunchedEffect(Unit) { if (!model.isReady) model.download() }
    val modelState by remember { model.state() }.collectAsState(initial = null)
    var dicts by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { dicts = dictionariesState(context, settings); kotlinx.coroutines.delay(1000) } }
    Text(tr("Dictionaries: %s", dicts), color = Colors.text, fontSize = 18.sp)
    Text(tr("Word splitter: %s", modelState ?: if (model.isReady) tr("✓ ready") else tr("Starting…")), color = Colors.text, fontSize = 18.sp)
}

internal fun qr(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { if (m[it % size, it / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
}

fun moveAloneLang(settings: Settings) {
    val old = settings.prefs.getString(ALONE_LANG, null) ?: return
    if (settings.menuLanguage.isEmpty()) settings.menuLanguage = old
    settings.prefs.edit().remove(ALONE_LANG).apply()
}
