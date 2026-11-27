package io.github.pedrubik2000.kumapie.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Onboarding
import io.github.pedrubik2000.kumapie.data.RdGate
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Startup
import io.github.pedrubik2000.kumapie.i18n.Tr
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import io.github.pedrubik2000.kumapie.lang.LivePairing
import io.github.pedrubik2000.kumapie.lang.Pairing
import kotlinx.coroutines.launch

const val ALONE_LANG = "alone_lang"

@Composable
fun TvOnboarding(settings: Settings, onDone: () -> Unit) = TvOnboarding(settings, LivePairing(LocalContext.current.applicationContext), onDone)

@Composable
internal fun TvOnboarding(settings: Settings, pairing: Pairing, onDone: () -> Unit) {
    val prefs = settings.prefs
    var changed by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf(Onboarding.next(Onboarding.done(prefs), Onboarding.copied(prefs), true) ?: Onboarding.DOWNLOADS) }
    var lang by remember { mutableStateOf(settings.menuLanguage) }
    remember(lang) { Tr.use(lang) }
    val nav = remember { StepNav(settings, { step }, { step = it; changed++ }, onDone) }
    val first = Onboarding.STEPS.first()
    Row(Modifier.fillMaxSize().onBackKey(enabled = step != first) { nav.back() }) {
        SetupList(nav.items(step, changed), step) { step = it }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            key(step) {
                if (step == Onboarding.COLOURING && step !in nav.viewOnly()) {
                    TvWordColouring(settings, true, nav::back, nav.number(step), nav.total, { nav.advance(step) }, { nav.advance(step) })
                } else TvStepPage(Modifier.fillMaxSize()) { StepBody(settings, pairing, nav, step, lang) { lang = it } }
            }
            if (step != Onboarding.COLOURING || step in nav.viewOnly()) HintBar()
        }
    }
    BackHandler(enabled = step != first) { nav.back() }
}

internal class StepNav(val settings: Settings, val step: () -> String, val set: (String) -> Unit, val finish: () -> Unit) {
    private val prefs get() = settings.prefs
    val total = Onboarding.counted(true).size
    var pc by mutableStateOf(false)

    fun number(s: String) = Onboarding.counted(true).indexOf(s) + 1

    fun advance(from: String) {
        Onboarding.markDone(prefs, from)
        val next = Onboarding.next(Onboarding.done(prefs), Onboarding.copied(prefs), true)
        if (next == null || from == Onboarding.DOWNLOADS) { Startup.onboard(prefs); finish() } else set(next)
    }

    fun back() { Onboarding.STEPS.getOrNull(Onboarding.STEPS.indexOf(step()) - 1)?.let(set) }

    fun viewOnly(): Set<String> = Onboarding.copied(prefs) + (if (Onboarding.MAIN in Onboarding.done(prefs)) setOf(Onboarding.MAIN) else emptySet())

    private fun detail(): Map<String, String> = buildMap {
        DeviceSync.with(settings)?.let { put(Onboarding.MAIN, it.name) }
        if (settings.anilist.isNotBlank()) put(Onboarding.ANILIST, settings.anilist)
        put(Onboarding.LANGUAGE, when (settings.menuLanguage) { "en" -> "English"; "es" -> "Español"; else -> tr("The TV's language") })
    }

    @Suppress("UNUSED_PARAMETER")
    fun items(current: String, changed: Int = 0) = Onboarding.list(Onboarding.done(prefs), Onboarding.copied(prefs), true, detail(), current, pc && current == Onboarding.MAIN)
}

@Composable
private fun SetupList(items: List<Onboarding.Item>, current: String, onOpen: (String) -> Unit) {
    Column(Modifier.width(300.dp).fillMaxHeight().background(Arcade.band).padding(horizontal = 20.dp, vertical = 28.dp).focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Setup"), color = Colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp, start = 12.dp))
        items.forEach { SetupRow(it, it.step == current, onOpen) }
    }
}

@Composable
private fun SetupRow(item: Onboarding.Item, here: Boolean, onOpen: (String) -> Unit) {
    val settled = item.state == Onboarding.State.DONE || item.state == Onboarding.State.COPIED
    val shape = RoundedCornerShape(14.dp)
    val source = remember(item.step) { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().then(if (here) Modifier.background(Color.White, shape) else Modifier)
        .then(if (settled && !here) Modifier.hint(HintKind.CHOOSE).tvRing(shape, source).clickable(interactionSource = source, indication = null, role = Role.Button) { onOpen(item.step) } else Modifier)
        .padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (here) "●" else if (settled) "✓" else "○", color = if (here) Color.Black else if (item.state == Onboarding.State.PC) Colors.dim else Colors.accent, fontSize = 18.sp)
        Column {
            Text(item.label, color = if (here) Color.Black else if (item.state == Onboarding.State.PC) Colors.dim else Colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(item.sub, color = if (here) Color.Black.copy(alpha = 0.7f) else Colors.dim, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun TvContinue(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = modifier.hint(HintKind.CHOOSE),
        colors = ButtonDefaults.colors(containerColor = Color.White, contentColor = Color.Black, focusedContainerColor = Arcade.accent, focusedContentColor = Color.Black)) { Text(label) }

@Composable
internal fun TvLink(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val source = remember { MutableInteractionSource() }
    Text(text, color = Colors.text, fontSize = 16.sp, textDecoration = TextDecoration.Underline,
        modifier = modifier.hint(HintKind.CHOOSE).tvRing(shape, source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 3.dp))
}

@Composable
private fun StepBody(settings: Settings, pairing: Pairing, nav: StepNav, step: String, lang: String, onLang: (String) -> Unit) {
    val copied = Onboarding.copied(settings.prefs)
    when {
        step == Onboarding.LANGUAGE -> LanguageStep(settings, lang, onLang) { nav.advance(step) }
        step in nav.viewOnly() -> DoneStep(settings, nav, step, step in copied)
        step == Onboarding.MAIN -> TvMainDevice(settings.prefs, pairing, nav.total, { nav.pc = it }, nav.finish) { nav.advance(step); onLang(settings.menuLanguage) }
        step == Onboarding.ANILIST -> AniListStep(settings, nav) { nav.advance(step) }
        step == Onboarding.VIDEOS -> VideosStep(settings, nav) { nav.advance(step) }
        else -> DownloadsStep(settings, nav) { nav.advance(step) }
    }
}

private fun caption(nav: StepNav, step: String, needed: Boolean = true) =
    tr("Step %1\$d of %2\$d", nav.number(step), nav.total) + " · " + if (needed) tr("Needed") else tr("Optional")

@Composable
private fun LanguageStep(settings: Settings, lang: String, onLang: (String) -> Unit, onContinue: () -> Unit) {
    val first = remember { FocusRequester() }
    TvStepHeader(null, tr("Languages"))
    val menus = listOf("" to tr("The TV's language"), "en" to "English", "es" to "Español")
    StepRow(tr("Menu language"), (menus.firstOrNull { it.first == lang } ?: menus[0]).second, Modifier.width(640.dp).focusRequester(first), onStep = { by ->
        val code = menus[(menus.indexOfFirst { it.first == lang }.coerceAtLeast(0) + by).mod(menus.size)].first
        onLang(code)
        settings.menuLanguage = code
        if (code.isNotEmpty()) settings.speaks = code
    }, onLeave = {}, valueMax = 240.dp, wrap = true)
    TvContinue(tr("Continue") + " ›", onClick = onContinue)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun DoneStep(settings: Settings, nav: StepNav, step: String, copied: Boolean) {
    val peer = DeviceSync.with(settings)?.name.orEmpty()
    val item = nav.items(step).first { it.step == step }
    val shape = RoundedCornerShape(16.dp)
    val focus = remember { FocusRequester() }
    val what = settings.anilist.takeIf { step == Onboarding.ANILIST && it.isNotBlank() } ?: item.label
    TvStepHeader(if (step == Onboarding.MAIN) null else caption(nav, step, item.state != Onboarding.State.OPTIONAL), item.label)
    Column(Modifier.fillMaxWidth().background(Arcade.slab, shape).border(1.dp, Arcade.line, shape).padding(20.dp)) {
        Text(if (copied) tr("%s · copied from %s", what, peer) else if (peer.isNotEmpty()) tr("Paired with %s", peer) else tr("this is my first device"),
            color = Colors.text, fontSize = 20.sp)
    }
    TvContinue(tr("Continue") + " ›", Modifier.focusRequester(focus)) { nav.advance(step) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun AniListStep(settings: Settings, nav: StepNav, onContinue: () -> Unit) {
    val focus = remember { FocusRequester() }
    TvStepHeader(caption(nav, Onboarding.ANILIST), tr("AniList"), tr("Home, Library and Discover come from your AniList anime list."))
    PhonePage(settings, SetupPart.ANILIST, heading = false)
    TvContinue(tr("Continue") + " ›", Modifier.focusRequester(focus), onContinue)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun VideosStep(settings: Settings, nav: StepNav, onContinue: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var needKey by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf("") }
    var failed by remember { mutableIntStateOf(0) }
    val checking = tr("Checking with %s…", "Real-Debrid")
    TvStepHeader(caption(nav, Onboarding.VIDEOS), tr("Videos & subtitles"), tr("Real-Debrid (paid) or torrents on this device stream the files Torrentio finds; Jimaku brings the Japanese subtitles."))
    PhonePage(settings, SetupPart.KEYS, onSaved = { if (settings.jimakuKey.isNotBlank()) needKey = false }, heading = false)
    if (needKey) Text(tr("Add your Jimaku key from your phone first: without it, streams get no Japanese lines."), color = Colors.accent, fontSize = 18.sp)
    if (said.isNotEmpty()) Text(said, color = Colors.accent, fontSize = 18.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvContinue(tr("Continue") + " ›", Modifier.focusRequester(focus)) {
            if (settings.jimakuKey.isBlank()) needKey = true
            else if (said != checking) scope.launch {
                said = checking
                val g = Startup.rdCheck(settings, failed)
                if (g == RdGate.UNREACHABLE) failed++
                said = Startup.rdSay(g)
                if (g.mayFinish) onContinue()
            }
        }
        Text(tr("or") + " ", color = Colors.dim, fontSize = 16.sp)
        TvLink(tr("Skip"), onClick = onContinue)
        Text(": " + tr("nothing plays until both keys are in"), color = Colors.dim, fontSize = 16.sp)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun DownloadsStep(settings: Settings, nav: StepNav, onFinish: () -> Unit) {
    val focus = remember { FocusRequester() }
    TvStepHeader(tr("Step %1\$d of %2\$d", nav.number(Onboarding.DOWNLOADS), nav.total) + " · " + tr("Automatic"), tr("Downloads"),
        tr("Dictionaries for lookups and the word splitter. They finish in the background."))
    TvDownloads(settings)
    Text(tr("You can start watching while they finish. Wi-Fi only."), color = Colors.dim, fontSize = 16.sp)
    TvContinue(tr("Start watching") + " ›", Modifier.focusRequester(focus), onFinish)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
internal fun PhonePage(settings: Settings, part: SetupPart = SetupPart.ALL, onSaved: () -> Unit = {}, heading: Boolean = true) {
    var saved by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val page = remember { SetupPage(settings, context.applicationContext, part) { saved++; onSaved() } }
    val url = remember { page.start() }
    DisposableEffect(Unit) { onDispose { page.stop() } }
    val aniList = part == SetupPart.ANILIST
    if (heading) Heading(if (aniList) tr("Log in to AniList from your phone") else tr("Set up kumapie from your phone"))
    if (url == null) return Text(if (aniList) tr("This TV isn't on a home network: connect it, then log in here.") else tr("This TV isn't on a home network: connect it, or add the keys in Settings later."), color = Colors.dim)
    Row(Modifier.padding(top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        val qr = remember(url) { qr(url, 480) }
        Image(qr.asImageBitmap(), null, Modifier.size(200.dp).background(Color.White).padding(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Scan this with your phone's camera (on the same Wi-Fi), or open:"), color = Colors.text, fontSize = 16.sp)
            Text(url, color = Colors.accent, fontSize = 16.sp)
            Text(when (part) {
                SetupPart.ANILIST -> tr("There: the AniList login link and the token field.")
                SetupPart.KEYS -> tr("There: your Jimaku key, your Real-Debrid token and Kitsu.")
                SetupPart.ALL -> tr("There: your Jimaku key, your Real-Debrid token, Kitsu and your AniList username.")
            }, color = Colors.dim, fontSize = 16.sp)
            @Suppress("UNUSED_EXPRESSION") saved
            val p = settings.prefs
            @Composable fun row(ok: Boolean, what: String) = Text((if (ok) "✓ " else "· ") + what, color = if (ok) Colors.text else Colors.dim, fontSize = 16.sp)
            if (!aniList) {
                row(settings.jimakuKey.isNotEmpty(), tr("Japanese subtitles (Jimaku)"))
                row(settings.addons.any { "torrentio.strem.fun" in it } && (settings.torrents || io.github.pedrubik2000.kumapie.data.Startup.rdKey(settings.rdToken, settings.addons).isNotEmpty()), tr("Videos (Torrentio)"))
                row(Addons.KITSU in settings.addons, "Kitsu")
                row(Addons.OPENSUBTITLES in settings.addons, tr("English subtitles (OpenSubtitles)"))
            }
            if (part == SetupPart.ALL) row(settings.anilist.isNotEmpty(), "AniList" + settings.anilist.let { if (it.isEmpty()) "" else ": $it" })
            if (part != SetupPart.KEYS) row(settings.anilistToken.isNotEmpty(), tr("AniList login: your lists stay in step"))
        }
    }
}

@Composable
private fun TvDownloads(settings: Settings) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { startJapaneseDictionaries(context, settings) }
    val model = remember { io.github.pedrubik2000.kumapie.lang.JapaneseModel(context.applicationContext) }
    LaunchedEffect(Unit) { if (!model.isReady) model.download() }
    val modelState by remember { model.state() }.collectAsState(initial = null)
    var dicts by remember { mutableStateOf("") }
    var free by remember { mutableLongStateOf(0L) }
    val yomitan = remember { io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.get(context.applicationContext) }
    LaunchedEffect(Unit) { while (true) { dicts = dictionariesState(context, settings); free = yomitan.base.usableSpace; kotlinx.coroutines.delay(1000) } }
    Text(tr("Dictionaries: %s", dicts), color = Colors.text, fontSize = 18.sp)
    Text(tr("Word splitter: %s", modelState ?: if (model.isReady) tr("✓ ready") else tr("Starting…")), color = Colors.text, fontSize = 18.sp)
    Text(tr("Free on this TV: %d MB", io.github.pedrubik2000.kumapie.lang.Recommended.mb(free)), color = Colors.text, fontSize = 18.sp)
}

@Composable
internal fun TvDictionaries(settings: Settings) {
    val context = LocalContext.current
    val yomitan = remember { io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.get(context.applicationContext) }
    val all by yomitan.all.collectAsState()
    val japanese = all.filter { it.lang == Lang.JAPANESE.code }
    val sizes by androidx.compose.runtime.produceState(emptyMap<String, Long>(), japanese) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            japanese.associate { d -> d.folder to java.io.File(yomitan.base, d.folder).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        }
    }
    val free = remember(all) { yomitan.base.usableSpace }
    var phone by remember { mutableStateOf(false) }
    Heading(tr("Japanese dictionaries"), Modifier.padding(top = 24.dp))
    val mb = io.github.pedrubik2000.kumapie.lang.Recommended::mb
    Text(japanese.joinToString("  ·  ") { d -> d.title + (sizes[d.folder]?.let { " ${mb(it)} MB" } ?: "") }.ifEmpty { tr("None yet.") },
        color = Colors.text, fontSize = 18.sp)
    Text(tr("Free on this TV: %d MB", mb(free)), color = Colors.text, fontSize = 18.sp)
    Button(onClick = { phone = !phone }) { Text(if (phone) tr("Hide the QR code") else tr("Add a dictionary from your phone (QR code)")) }
    val shown = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    if (phone) Column(Modifier.bringIntoViewRequester(shown)) { PhonePage(settings, SetupPart.KEYS) }
    LaunchedEffect(phone) { if (phone) { kotlinx.coroutines.delay(100); shown.bringIntoView() } }
}

@Composable
fun RdMissingLine(settings: Settings, onSettings: () -> Unit) {
    if (!io.github.pedrubik2000.kumapie.data.Startup.rdMissing(settings)) return
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    DisposableEffect(Unit) {
        MissingKeyLine.shown = true
        onDispose {
            MissingKeyLine.shown = false
            MissingKeyLine.focused = false
            MissingKeyLine.from = null
        }
    }
    Button(onClick = onSettings, modifier = Modifier.fillMaxWidth().wrapContentWidth().padding(top = 24.dp).focusRequester(MissingKeyLine.focus)
        .onFocusChanged { MissingKeyLine.focused = it.isFocused }.onPreviewKeyEvent { e ->
            if (e.key != Key.DirectionDown) false
            else {
                if (e.type == KeyEventType.KeyDown && !MissingKeyLine.down()) focusManager.moveFocus(FocusDirection.Down)
                true
            }
        }) {
        Text(tr("Real-Debrid key missing: no video will play"))
    }
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
