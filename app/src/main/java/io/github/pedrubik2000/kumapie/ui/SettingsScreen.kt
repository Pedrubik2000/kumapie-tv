package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.BuildConfig
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.profile
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.update.Updater
import kotlinx.coroutines.launch

internal object SettingsFocus { var returning = false }

@Composable
internal fun SettingsScreen(settings: Settings, api: io.github.pedrubik2000.kumapie.data.Discovery, nav: RailNav, onSaved: () -> Unit, onUpdate: (Updater.Release) -> Unit,
                   onButtons: () -> Unit = {}) {
    val focus = remember { FocusRequester() }
    val buttons = remember { FocusRequester() }
    val back = remember { SettingsFocus.returning.also { SettingsFocus.returning = false } }
    val scroll = rememberScrollState()
    val colour = remember { FocusRequester() }
    var colouring by remember { mutableStateOf(false) }
    var fromColouring by remember { mutableStateOf(false) }
    if (colouring) return TvWordColouring(settings, onboarding = false, onBack = {
        colouring = false
        fromColouring = true
    })
    TvPage(api, RailAt.SETTINGS, nav, toContent = focus) {
        val scrolled by remember { androidx.compose.runtime.derivedStateOf { scroll.value > 0 } }
        ReportsScroll(scrolled, scroll.canScrollForward)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 32.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TvAniList(settings, focus)
            ServerSettings(settings, onSaved)
            CheckSetup(settings)
            if (settings.server.isEmpty()) AloneSources(settings)
            TvPreferences(settings, buttons, colour, onColouring = { colouring = true }) { SettingsFocus.returning = true; onButtons() }
            TvNotForMe(settings)
            TvDictionaries(settings)
            TvExport()
            if (settings.server.isEmpty()) TvSync(settings)
            TvAbout(settings, onUpdate)
        }
    }
    LaunchedEffect(Unit) {
        if (fromColouring) {
            fromColouring = false
            runCatching { colour.requestFocus() }
        } else if (back) runCatching { buttons.requestFocus() }
        else { runCatching { focus.requestFocus() }; kotlinx.coroutines.delay(100); scroll.scrollTo(0) }
    }
}

@Composable
private fun ServerSettings(settings: Settings, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var server by remember { mutableStateOf(settings.server) }
    var status by remember { mutableStateOf("") }

    fun save() {
        val url = Settings.normalizeServer(server)
        status = tr("Connecting…")
        scope.launch {
            runCatching { Api(url).info() }
                .onSuccess {
                    settings.server = url
                    status = tr("Connected: %d episodes", it.episodes)
                    onSaved()
                }
                .onFailure { status = tr("Can't reach %1\$s (%2\$s)", url, it.message) }
        }
    }
    Heading(tr("Server"))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TvTextField(server, { server = it }, onDone = ::save, modifier = Modifier.width(640.dp))
        Button(onClick = ::save) { Text(tr("Save")) }
    }
    if (status.isNotEmpty()) Text(status, color = Colors.dim)
    if (settings.server.isNotEmpty()) {
        Button(onClick = {
            status = tr("Copying your words, cards and progress…")
            scope.launch {
                runCatching {
                    val pc = Api(settings.server, settings.profile)
                    io.github.pedrubik2000.kumapie.lang.LeavePc.run(context, pc, settings.server, settings.profile,
                        tvLanguages(context, settings).all,
                        io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs))
                }.onSuccess { settings.server = ""; onSaved() }
                    .onFailure { status = tr("Can't reach %1\$s (%2\$s)", settings.server, it.message) }
            }
        }) { Text(tr("Use without the PC (keeps your words, cards and progress)")) }
    }
}

@Composable
private fun CheckSetup(settings: Settings) {
    val scope = rememberCoroutineScope()
    Heading(tr("Check setup"), Modifier.padding(top = 24.dp))
    var lines by remember { mutableStateOf<List<io.github.pedrubik2000.kumapie.data.SetupCheck.Line>?>(null) }
    var checking by remember { mutableStateOf(false) }
    Button(onClick = { if (!checking) { checking = true; scope.launch { lines = io.github.pedrubik2000.kumapie.data.SetupCheck.run(settings); checking = false } } }) {
        Text(if (checking) tr("Checking…") else tr("Check setup"))
    }
    lines?.forEach { SetupLine(it) }
}

@Composable
private fun AloneSources(settings: Settings) {
    var phone by remember { mutableStateOf(false) }
    var fromPhone by remember { mutableIntStateOf(0) }
    Button(onClick = { phone = !phone }, modifier = Modifier.padding(top = 24.dp)) { Text(if (phone) tr("Hide the QR code") else tr("Set up from your phone (QR code)")) }
    if (phone) PhonePage(settings, SetupPart.KEYS, onSaved = { fromPhone++ })
    Heading(tr("Stremio addons"), Modifier.padding(top = 24.dp))
    var addons by remember(fromPhone) { mutableStateOf(settings.addons) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var adding by remember { mutableStateOf("") }
    LaunchedEffect(addons) { names = runCatching { io.github.pedrubik2000.kumapie.data.Addons(addons, settings.prefs).names() }.getOrDefault(emptyList()) }
    Text(tr("Paste an addon's address (…/manifest.json). One that lists titles (Cinemeta, Kitsu) and one that gives links: a torrent addon needs your debrid key in it. A subtitle addon (OpenSubtitles v3) adds English under the lines."),
        color = Colors.dim)
    addons.forEachIndexed { i, a ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(names.getOrNull(i).orEmpty().ifEmpty { tr("(no answer)") } + " · " + io.github.pedrubik2000.kumapie.data.Addons.base(a).substringAfter("//").substringBefore('/'),
                color = Colors.text, modifier = Modifier.width(640.dp))
            Button(onClick = { addons = addons - a; settings.addons = addons }) { Text(tr("Remove")) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        fun add() { if (adding.isNotBlank()) { addons = (addons + adding.trim()).distinct(); settings.addons = addons; adding = "" } }
        TvTextField(adding, { adding = it }, onDone = ::add, modifier = Modifier.width(640.dp))
        Button(onClick = ::add) { Text(tr("Add")) }
    }
    Heading(tr("Torrents"), Modifier.padding(top = 24.dp))
    Text(tr("Downloads the video on this device and shares pieces with others while it plays. Your IP address is visible to them: a VPN is wise."), color = Colors.dim)
    var torrents by remember(fromPhone) { mutableStateOf(settings.torrents) }
    Choice(tr("Stream torrents without Real-Debrid"), onOff(torrents)) { torrents = !torrents }
    Debounced(torrents, fromPhone) { settings.streamTorrents(it); addons = settings.addons }
    Heading(tr("Jimaku"), Modifier.padding(top = 24.dp))
    Text(tr("Japanese subtitles for a stream that has none of its own: your free API key from jimaku.cc."), color = Colors.dim)
    var key by remember(fromPhone) { mutableStateOf(settings.jimakuKey) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        fun keep() { settings.jimakuKey = key }
        TvTextField(key, { key = it }, onDone = ::keep, modifier = Modifier.width(640.dp))
        Button(onClick = ::keep) { Text(tr("Save")) }
    }
}

@Composable
private fun TvPreferences(settings: Settings, buttons: FocusRequester, colour: FocusRequester, onColouring: () -> Unit, onButtons: () -> Unit) {
    Heading(tr("Buttons and help"), Modifier.padding(top = 24.dp))
    var showHelp by remember { mutableStateOf(settings.showHelp) }
    Button(onClick = onButtons, modifier = Modifier.focusRequester(buttons)) { Text(tr("Change buttons")) }
    Choice(tr("Help screen and key hints"), onOff(showHelp)) { showHelp = !showHelp; settings.showHelp = showHelp }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val menus = listOf("" to tr("The TV's language"), "en" to "English", "es" to "Español")
    var menu by remember { mutableStateOf(settings.menuLanguage) }
    Choice(tr("Menu language"), (menus.firstOrNull { it.first == menu } ?: menus[0]).second) { by ->
        menu = menus[(menus.indexOfFirst { it.first == menu }.coerceAtLeast(0) + by).mod(menus.size)].first
    }
    Debounced(menu) { code ->
        if (code == settings.menuLanguage) return@Debounced
        settings.menuLanguage = code
        if (code.isNotEmpty()) settings.speaks = code
        activity?.recreate()
    }
    Heading(tr("No spoilers"), Modifier.padding(top = 24.dp))
    var blur by remember { mutableStateOf(settings.blurUnwatched) }
    Choice(tr("Blur episodes I haven't watched"), onOff(blur)) { blur = !blur; settings.blurUnwatched = blur }
    Choice(tr("Word colouring"), if (settings.plainWords) tr("off (lookup only)") else tr("on"), Modifier.focusRequester(colour), onOpen = onColouring)
    Heading(tr("Japanese subtitles"), Modifier.padding(top = 24.dp))
    var font by remember { mutableStateOf(settings.japaneseFont) }
    Choice(tr("Font"), JapaneseFonts.label(font)) { by ->
            font = JapaneseFonts.NAMES[(JapaneseFonts.NAMES.indexOf(font) + by).mod(JapaneseFonts.NAMES.size)]
            settings.japaneseFont = font
    }
    Text(JapaneseFonts.SAMPLE, fontSize = 26.sp, color = Colors.text, fontFamily = JapaneseFonts.family(font), maxLines = 1, softWrap = false)
}

@Composable
private fun TvAbout(settings: Settings, onUpdate: (Updater.Release) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var updateStatus by remember { mutableStateOf("") }
    Heading(tr("Card pictures and audio"), Modifier.padding(top = 24.dp))
    val usbHere = remember { io.github.pedrubik2000.kumapie.lang.CardMedia.usbDir(context) != null }
    var onUsb by remember { mutableStateOf(settings.prefs.getBoolean(io.github.pedrubik2000.kumapie.lang.CardMedia.USB, false)) }
    if (!usbHere) Text(tr("Kept on this TV (no USB drive plugged in)."), color = Colors.dim)
    else Choice(tr("Kept on"), if (onUsb) tr("USB drive") else tr("This TV")) { onUsb = !onUsb }
    if (usbHere) Debounced(onUsb) { on -> kotlin.concurrent.thread { io.github.pedrubik2000.kumapie.lang.CardMedia.setUsb(context, on) } }
    Heading(tr("Updates"), Modifier.padding(top = 24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = {
            updateStatus = tr("Checking…")
            scope.launch {
                runCatching { Updater.newer() }
                    .onSuccess { r -> if (r == null) updateStatus = tr("Up to date") else { updateStatus = ""; onUpdate(r) } }
                    .onFailure { updateStatus = tr("Couldn't check: %s", it.message) }
            }
        }) { Text(tr("Check for updates")) }
    }
    if (updateStatus.isNotEmpty()) Text(updateStatus, color = Colors.dim)
    Heading(tr("Ideas and bugs"), Modifier.padding(top = 24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        val code = remember { qr(ISSUES, 320) }
        androidx.compose.foundation.Image(code.asImageBitmap(), null,
            Modifier.size(140.dp).background(androidx.compose.ui.graphics.Color.White).padding(6.dp))
        Column {
            Text(tr("Report a bug or share an idea: scan this with your phone, or open"), color = Colors.text)
            Text(ISSUES, color = Colors.accent)
        }
    }
    Text(tr("Version %s", BuildConfig.VERSION_NAME), color = Colors.dim, modifier = Modifier.padding(top = 24.dp))
    Text(io.github.pedrubik2000.kumapie.lang.TitleDifficulty.credit(), color = Colors.dim)
}

const val ISSUES = "https://github.com/Pedrubik2000/kumapie-tv/issues"

@Composable
internal fun SetupLine(l: io.github.pedrubik2000.kumapie.data.SetupCheck.Line) {
    val (mark, colour) = when (l.verdict) {
        io.github.pedrubik2000.kumapie.data.SetupCheck.Verdict.OK -> "✓" to Colors.due
        io.github.pedrubik2000.kumapie.data.SetupCheck.Verdict.WARN -> "!" to Colors.levelOne
        io.github.pedrubik2000.kumapie.data.SetupCheck.Verdict.FAIL -> "✗" to Colors.unknown
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(mark, color = colour, modifier = Modifier.width(24.dp))
        Text(l.what, color = Colors.text, modifier = Modifier.width(220.dp))
        Text(l.say, color = if (l.verdict == io.github.pedrubik2000.kumapie.data.SetupCheck.Verdict.OK) Colors.dim else colour)
    }
}

@Composable
private fun TvExport() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("") }
    Heading(tr("Your data"), Modifier.padding(top = 24.dp))
    Text(tr("Everything kumapie keeps here in one file, for another device to import: words, cards, reviews, progress."), color = Colors.dim)
    Button(onClick = {
        status = tr("Exporting…")
        scope.launch {
            status = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    io.github.pedrubik2000.kumapie.lang.DeviceFiles(context, tvLanguages(context, Settings(context)).all).export()
                }
            }.fold({ it.line() }, { tr("Couldn't export: %s", it.message) })
        }
    }) { Text(tr("Export everything")) }
    if (status.isNotEmpty()) Text(status, color = Colors.dim)
}

@Composable
internal fun Choice(label: String, value: String, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null, onStep: (Int) -> Unit = {}) =
    StepRow(label, value, modifier.width(640.dp), onStep, {}, onOpen = onOpen, valueMax = 240.dp, stepOnOk = true, wrap = true)

internal fun onOff(on: Boolean) = if (on) tr("on") else tr("off")

@Composable
internal fun <T> Debounced(value: T, source: Any? = null, apply: (T) -> Unit) {
    val applied = remember { arrayOf<Any?>(value, source) }
    if (applied[1] != source) { applied[1] = source; applied[0] = value }
    val latest by androidx.compose.runtime.rememberUpdatedState(value)
    val act by androidx.compose.runtime.rememberUpdatedState(apply)
    LaunchedEffect(value) {
        kotlinx.coroutines.delay(1_500)
        if (value != applied[0]) { applied[0] = value; act(value) }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (latest != applied[0]) { applied[0] = latest; act(latest) } } }
}
