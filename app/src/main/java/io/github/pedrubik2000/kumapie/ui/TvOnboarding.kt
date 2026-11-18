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

/** Set once the TV's onboarding was finished: it doesn't come back. */
const val TV_ONBOARDED = "onboarded"
/** The menu language picked in the onboarding ("" = the TV's own). */
const val ALONE_LANG = "alone_lang"

/**
 * The TV's first run without a PC (kumapie_plan.md "Onboarding"): the language with the remote, then the keys from the
 * phone (a QR code to [SetupPage], the TV shows what came), then the downloads. Every step can be skipped; Settings
 * has the addons and the Jimaku key later.
 */
@Composable
fun TvOnboarding(settings: Settings, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var lang by remember { mutableStateOf(settings.prefs.getString(ALONE_LANG, "").orEmpty()) }
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
                            settings.prefs.edit().putString(ALONE_LANG, code).apply()
                            if (code.isNotEmpty()) settings.speaks = code // meanings and the second line in it too
                        }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            Text((if (lang == code) "✓ " else "") + label)
                        }
                    }
                }
            }
            1 -> TvJitenImport(settings)
            2 -> PhonePage(settings)
            else -> TvDownloads(settings)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (step > 0) Button(onClick = { step-- }) { Text(tr("Back")) }
            Button(onClick = { if (step < STEPS - 1) step++ else { settings.prefs.edit().putBoolean(TV_ONBOARDED, true).apply(); onDone() } },
                modifier = if (step > 0) Modifier.focusRequester(first) else Modifier) {
                Text((if (step < STEPS - 1) tr("Next") else tr("Start watching")))
            }
        }
    }
    LaunchedEffect(step) { runCatching { first.requestFocus() } }
    // Back goes to the step before (it closed the app, and the next launch began again at step 1).
    androidx.activity.compose.BackHandler(enabled = step > 0) { step-- }
}

private const val STEPS = 4

/**
 * Japanese words imported as known from jiten.moe (kumapie_plan.md item 6), like the phone's Settings: the top words
 * (a few sizes, no typing with the remote) and Jiten's word sets. Known while the word has no Mined card.
 */
@Composable
private fun TvJitenImport(settings: Settings) {
    val context = LocalContext.current
    val known = remember { japaneseKnown(context, settings) }
    val jiten = io.github.pedrubik2000.kumapie.lang.Jiten
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var top by remember { mutableIntStateOf(10000) }
    val sets = remember { androidx.compose.runtime.mutableStateMapOf(*jiten.SETS.keys.map { it to true }.toTypedArray()) }
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
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        jiten.SETS.forEach { (slug, name) ->
            Button(onClick = { sets[slug] = sets[slug] != true }) { Text((if (sets[slug] == true) "✓ " else "") + tr(name)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = {
            if (busy) return@Button
            busy = true
            said = tr("Downloading from jiten.moe…")
            scope.launch {
                said = runCatching {
                    val words = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { jiten.words(top, sets.filterValues { it }.keys) }
                    known.setImported(words)
                    tr("%d words imported as known.", words.size)
                }.getOrElse { it.message ?: it.toString() }
                busy = false
            }
        }) { Text(tr("Import")) }
        if (known.importedWords.isNotEmpty()) Button(onClick = { if (!busy) { known.setImported(emptySet()); said = "" } }) { Text(tr("Remove imported")) }
    }
    if (said.isNotEmpty()) Text(said, color = Colors.text, fontSize = 18.sp)
}

/** The QR code to the TV's setup page and what has come from it so far. */
@Composable
private fun PhonePage(settings: Settings) {
    var saved by remember { mutableIntStateOf(0) } // bumped when the phone saves: the rows below read the prefs again
    val page = remember { SetupPage(settings) { saved++ } }
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
            row(!p.getString("jimaku_key", "").isNullOrEmpty(), tr("Japanese subtitles (Jimaku)"))
            row(settings.addons.any { "torrentio.strem.fun" in it }, tr("Videos (Torrentio with Real-Debrid)"))
            row(Addons.KITSU in settings.addons, "Kitsu")
            row(Addons.OPENSUBTITLES in settings.addons, tr("English subtitles (OpenSubtitles)"))
            row(!p.getString(Addons.ANILIST, "").isNullOrEmpty(), "AniList" + p.getString(Addons.ANILIST, "").orEmpty().let { if (it.isEmpty()) "" else ": $it" })
        }
    }
}

/** The dictionaries (meanings) and Sudachi (splits lines into words), started now, each with its state. */
@Composable
private fun TvDownloads(settings: Settings) {
    val context = LocalContext.current
    Heading(tr("Downloads"))
    Text(tr("Two downloads, once: the Japanese dictionaries (meanings, about 27 MB) and the word splitter (about 80 MB). You can start watching while they finish."),
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

/** [text] as a QR code, black on white, [size] pixels. */
internal fun qr(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    // One array, one call: setPixel per pixel (230 000 of them) stalled a Chromecast on every Settings.
    val px = IntArray(size * size) { if (m[it % size, it / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
}
