package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.BuildConfig
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.mobile.unlock.unlockScenes
import io.github.pedrubik2000.kumapie.mobile.local.rdToken
import io.github.pedrubik2000.kumapie.mobile.local.jimakuKey
import io.github.pedrubik2000.kumapie.mobile.local.subFrom
import io.github.pedrubik2000.kumapie.mobile.local.subTo
import io.github.pedrubik2000.kumapie.mobile.local.sonioxKey
import io.github.pedrubik2000.kumapie.mobile.local.englishSource
import io.github.pedrubik2000.kumapie.mobile.local.transcriber
import io.github.pedrubik2000.kumapie.mobile.local.Transfer
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The server address (first run too), downloads on the device, reports waiting, updates and the version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(library: Library, firstRun: Boolean, onSaved: () -> Unit, onUpdate: (Updater.Release) -> Unit, onBack: () -> Unit) {
    val settings = library.settings
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(settings.server) }
    var message by remember { mutableStateOf("") }
    var used by remember { mutableLongStateOf(0L) }
    var waiting by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        used = withContext(Dispatchers.IO) { library.downloads.bytesUsed() }
        waiting = library.pending.count()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (firstRun) tr("Welcome to kumapie") else tr("Settings")) },
            navigationIcon = { if (!firstRun) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MenuLanguage(settings)
            Text(tr("Server"), color = Colors.accent)
            OutlinedTextField(server, { server = it }, label = { Text("https://my-pc.my-tailnet.ts.net:8445") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val url = Settings.normalizeServer(server)
                message = tr("Checking…")
                scope.launch {
                    runCatching { Api(url).info() }
                        .onSuccess {
                            settings.server = url
                            server = url
                            message = tr("Connected: %1\$s episodes in %2\$s.", it.episodes, it.language)
                            onSaved()
                        }
                        .onFailure { message = tr("No answer from %1\$s: %2\$s", url, it.message) }
                }
            }) { Text(tr("Save")) }
            if (message.isNotEmpty()) Text(message, color = Colors.dim, fontSize = 14.sp)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TransferSection()

            if (!firstRun) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                KnownWordsSection(library)
                JapaneseWordsSection(library)
                EnglishWordsSection(library)
                HorizontalDivider()
                YomitanSection(library)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                DictionarySection(library)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NewEpisodesSection(library)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                UnlockSection(library)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(tr("On this device"), color = Colors.accent)
                Text(tr("Downloads: %.1f GB", used / 1e9), fontSize = 15.sp)
                OutlinedButton(onClick = { library.downloads.deleteAll(); refresh++ }) { Text(tr("Remove all downloads")) }
                Text(if (waiting == 0) tr("Nothing waiting to be sent to the PC.") else tr("%d reports waiting for the PC (sent when it answers).", waiting),
                    fontSize = 15.sp, color = Colors.dim)
                if (waiting > 0) OutlinedButton(onClick = {
                    scope.launch { library.pending.flush(library.api, force = true); refresh++ }
                }) { Text(tr("Send now")) }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = {
                    message = tr("Looking for updates…")
                    scope.launch {
                        runCatching { Updater.newer() }
                            .onSuccess { r -> if (r == null) message = tr("This is the newest version.") else { message = ""; onUpdate(r) } }
                            .onFailure { message = tr("Couldn't check: %s", it.message) }
                    }
                }) { Text(tr("Check for updates")) }
            }
            Text(tr("Version %s", BuildConfig.VERSION_NAME), color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/**
 * Word colours from the device's own Anki: the German model (downloaded once), permission to read AnkiDroid,
 * the stability that makes a word known, and reading Anki again (it is also read when the app starts).
 */
@Composable
private fun KnownWordsSection(library: Library) {
    val known = library.known
    val scope = rememberCoroutineScope()
    val status by known.status.collectAsState()
    val modelState by remember { known.model.state() }.collectAsState(initial = null)
    var modelReady by remember { mutableStateOf(known.model.isReady) }
    LaunchedEffect(modelState) { modelReady = known.model.isReady }
    val app = remember { known.ankiApp() }
    var allowed by remember { mutableStateOf(app != null && known.hasPermission(app)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
    }
    var days by remember { mutableStateOf(known.threshold.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }) }
    var busy by remember { mutableStateOf(false) }

    Text(tr("Word colours from Anki"), color = Colors.accent)
    Text(status, fontSize = 15.sp)

    // 1. The German model (spaCy, the same as morphs on the PC).
    when {
        modelReady -> Text(tr("German model: ready (de_core_news_lg)."), fontSize = 14.sp, color = Colors.dim)
        modelState != null -> Text(tr("German model: %s", modelState), fontSize = 14.sp, color = Colors.dim)
        else -> {
            Text(tr("The German model (about 550 MB, once) finds each word's form like morphs on the PC."), fontSize = 14.sp, color = Colors.dim)
            OutlinedButton(onClick = { known.model.download() }) { Text(tr("Download the German model")) }
        }
    }

    // 2. Reading AnkiDroid.
    when {
        app == null -> Text(tr("No kuma3 Anki or AnkiDroid on this device."), fontSize = 14.sp, color = Colors.dim)
        !allowed -> OutlinedButton(onClick = { ask.launch(known.permission(app)) }) { Text(tr("Allow reading Anki")) }
        else -> Text(tr("Anki: %s.", when (app) {
            "io.github.pedrubik2000.kuma3" -> "kuma3 Anki"
            "com.ichi2.anki.debug" -> tr("kuma3 test build")
            else -> app
        }), fontSize = 14.sp, color = Colors.dim)
    }

    // 3. Known from this stability (days) on.
    OutlinedTextField(days, { v ->
        days = v
        v.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { known.threshold = it }
    }, label = { Text(tr("Known from stability (days)")) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())

    if (modelReady && allowed) OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch { known.refresh(); busy = false }
    }) { Text(if (busy) tr("Reading Anki…") else tr("Read Anki now")) }

    // 4. morphs' Recalc (the PC's daily `morphs recalc`): first what would change, then Apply. kuma3 Anki only.
    if (modelReady && allowed && app != "com.ichi2.anki") {
        var plan by remember { mutableStateOf<io.github.pedrubik2000.kumapie.mobile.lang.Recalc.Plan?>(null) }
        var said by remember { mutableStateOf("") }
        val recalc = remember { io.github.pedrubik2000.kumapie.mobile.lang.Recalc(known) }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            said = tr("Working out the order…")
            scope.launch {
                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { recalc.plan() } }
                    .onSuccess { plan = it; said = it.describe() }.onFailure { said = it.message ?: it.toString() }
                busy = false
            }
        }) { Text(tr("Order new cards (morphs)")) }
        if (said.isNotEmpty()) Text(said, fontSize = 14.sp, color = Colors.dim)
        plan?.takeIf { !it.empty }?.let { p ->
            androidx.compose.material3.Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    said = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { recalc.apply(p) } }
                        .getOrElse { it.message ?: it.toString() }
                    plan = null
                    busy = false
                }
            }) { Text(tr("Apply")) }
        }
        Text(tr("Like morphs recalc on the PC: after reviewing, new cards with one unknown word come first. Run it on " +
            "one device only (the PC's morphs and this would undo each other's marked-known words)."), fontSize = 13.sp, color = Colors.dim)
    }
}

/** Japanese words from 🐻 Japanese (Kaishi): the Sudachi dictionary (downloaded once) and reading Anki for Japanese. */
@Composable
private fun JapaneseWordsSection(library: Library) {
    val known = library.knownJa
    val scope = rememberCoroutineScope()
    val status by known.status.collectAsState()
    val state by remember { known.japanese.state() }.collectAsState(initial = null)
    var ready by remember { mutableStateOf(known.modelReady) }
    LaunchedEffect(state) { ready = known.modelReady }
    var busy by remember { mutableStateOf(false) }
    val app = remember { known.ankiApp() }

    Text(tr("Japanese words"), color = Colors.accent, modifier = Modifier.padding(top = 8.dp))
    Text(status, fontSize = 15.sp)
    when {
        ready -> Text(tr("Japanese dictionary: ready (Sudachi core)."), fontSize = 14.sp, color = Colors.dim)
        state != null -> Text(tr("Japanese dictionary: %s", state), fontSize = 14.sp, color = Colors.dim)
        else -> {
            Text(tr("The Japanese dictionary (about 80 MB to download, 200 MB on the device, once) splits Japanese into words."),
                fontSize = 14.sp, color = Colors.dim)
            OutlinedButton(onClick = { known.japanese.download() }) { Text(tr("Download the Japanese dictionary")) }
        }
    }
    if (ready && app != null && known.hasPermission(app)) OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch { known.refresh(); busy = false }
    }) { Text(if (busy) tr("Reading Anki…") else tr("Read Japanese cards now")) }
}

/**
 * Yomitan dictionaries (kumapie_languages_plan.md step 2b): import .zip files per language, turn them on or off,
 * order them (the popup shows meanings in this order), delete. German words are looked up in them first.
 */
@Composable
private fun YomitanSection(library: Library) {
    val dicts = library.yomitan
    val scope = rememberCoroutineScope()
    val all by dicts.all.collectAsState()
    var lang by remember { mutableStateOf(io.github.pedrubik2000.kumapie.data.Lang.GERMAN) }
    var said by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<io.github.pedrubik2000.kumapie.mobile.lang.YomitanDictionaries.Dict?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            said = dicts.import(uris, lang) { said = it }.joinToString("\n")
            busy = false
        }
    }

    Text(tr("Dictionaries (Yomitan)"), color = Colors.accent)
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        io.github.pedrubik2000.kumapie.data.Lang.ALL.forEach { l ->
            androidx.compose.material3.FilterChip(selected = lang == l, onClick = { lang = l }, label = { Text(tr(l.name)) })
        }
    }
    val mine = all.filter { it.lang == lang.code }
    val langName = tr(lang.name).let { if (io.github.pedrubik2000.kumapie.i18n.Tr.spanish) it.lowercase() else it } // "alemán" mid-sentence
    if (mine.isEmpty()) Text(tr("No %s dictionaries yet. Import Yomitan .zip files (e.g. kty-de-en for German, Jitendex for Japanese).", langName),
        fontSize = 14.sp, color = Colors.dim)
    mine.forEachIndexed { i, d ->
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.title, fontSize = 15.sp)
                Text(d.kinds + (if (d.revision.isNotBlank()) " · ${d.revision}" else "") + (if (d.updatable) " · " + tr("updates itself") else ""),
                    fontSize = 12.sp, color = Colors.dim)
            }
            IconButton(enabled = i > 0, onClick = { dicts.move(d.folder, -1) }) { Icon(androidx.compose.material.icons.Icons.Default.KeyboardArrowUp, tr("Up")) }
            IconButton(enabled = i < mine.lastIndex, onClick = { dicts.move(d.folder, 1) }) { Icon(androidx.compose.material.icons.Icons.Default.KeyboardArrowDown, tr("Down")) }
            androidx.compose.material3.Switch(d.enabled, { dicts.setEnabled(d.folder, it) })
            IconButton(onClick = { deleting = d }) { Icon(androidx.compose.material.icons.Icons.Default.Delete, tr("Delete")) }
        }
    }
    OutlinedButton(enabled = !busy, onClick = { pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
        Text(if (busy) tr("Working…") else tr("Import %s dictionaries (.zip)", langName))
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val zips = withContext(Dispatchers.IO) { dicts.zipsIn(tree) }
            var n = 0
            val lines = dicts.import(zips, lang) { said = "${++n}/${zips.size}: $it" }
            said = tr("%1\$d of %2\$d imported.", lines.count { "imported" in it }, zips.size) + lines.filterNot { "imported" in it }.joinToString("") { "\n$it" }
            busy = false
        }
    }
    OutlinedButton(enabled = !busy, onClick = { pickFolder.launch(null) }) { Text(tr("Import a folder of %s dictionaries", langName)) }
    if (io.github.pedrubik2000.kumapie.mobile.lang.YomitanDictionaries.RECOMMENDED[lang.code] != null) OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch { said = dicts.installRecommended(lang) { said = it }.joinToString("\n"); busy = false }
    }) { Text(tr("Download the recommended %s dictionaries", langName)) }
    if (all.any { it.updatable }) OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch { said = dicts.update { said = it }.ifEmpty { listOf(tr("Every dictionary is up to date.")) }.joinToString("\n"); busy = false }
    }) { Text(tr("Check for updates now")) }
    Text(tr("Dictionaries that can update themselves are checked weekly on Wi-Fi."), fontSize = 12.sp, color = Colors.dim)
    if (said.isNotEmpty()) Text(said, fontSize = 13.sp, color = Colors.dim)
    deleting?.let { d ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(tr("Delete %s?", d.title)) }, text = { Text(tr("You can import it again later.")) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { scope.launch(Dispatchers.IO) { dicts.delete(d.folder) }; deleting = null }) { Text(tr("Delete")) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text(tr("Cancel")) } })
    }
}

/** The offline dictionary (download / update), the device's German voice, and the data's attribution. */
@Composable
private fun DictionarySection(library: Library) {
    val dictionary = library.dictionary
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by remember { dictionary.state() }.collectAsState(initial = null)
    var built by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) { built = withContext(Dispatchers.IO) { dictionary.built() } }
    var voice by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        library.voice // starts the speech engine
        kotlinx.coroutines.delay(1500)
        voice = library.voice.describe()
    }

    Text(tr("Word audio"), color = Colors.accent)
    when {
        state != null -> Text(tr("Recordings list: %s", state), fontSize = 15.sp)
        built != null -> Text(tr("Recordings list: offline, built %s (which German words have a person's recording).", built), fontSize = 15.sp)
        else -> Text(tr("Which German words have a person's recording on Wikimedia Commons: a small list (about 3 MB), once."), fontSize = 15.sp)
    }
    if (state == null) OutlinedButton(onClick = { dictionary.download() }) {
        Text(if (built == null) tr("Download the recordings list") else tr("Update the recordings list"))
    }
    if (voice.isNotEmpty()) Text(voice, fontSize = 14.sp, color = Colors.dim)
    OutlinedButton(onClick = {
        runCatching { context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) }
    }) { Text(tr("Speech settings")) }
    Text(tr("Words are read by a person's recording when Wikimedia Commons has one, else by this voice."),
        fontSize = 13.sp, color = Colors.dim)
    Text(io.github.pedrubik2000.kumapie.mobile.lang.Dictionary.ATTRIBUTION, fontSize = 12.sp, color = Colors.dim)
}

/** A scene on every unlock: the switch, "Display over other apps" (needed to open over the lock screen), the pool. */
@Composable
private fun UnlockSection(library: Library) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(library.settings.unlockScenes) }
    var overlay by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var pool by remember { mutableIntStateOf(io.github.pedrubik2000.kumapie.mobile.unlock.UnlockPool.size(context)) }
    LaunchedEffect(on) { // switched on with no scenes yet: read the episodes now
        if (on && pool == 0) {
            runCatching { library.allEpisodes(library.shows().first) }
            pool = io.github.pedrubik2000.kumapie.mobile.unlock.UnlockPool.size(context)
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) { // back from the system settings
        lifecycle.lifecycle.currentStateFlow.collect { overlay = android.provider.Settings.canDrawOverlays(context) }
    }

    Text(tr("Unlock"), color = Colors.accent)
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(tr("Show an i+1 scene every time I unlock"), fontSize = 15.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = on, onCheckedChange = {
            on = it
            library.settings.unlockScenes = it
            io.github.pedrubik2000.kumapie.mobile.unlock.UnlockService.sync(context)
        })
    }
    if (on && !overlay) {
        Text(tr("Allow \"Display over other apps\" so the scene can open when you unlock."), fontSize = 14.sp, color = Colors.dim)
        OutlinedButton(onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + context.packageName)))
        }) { Text(tr("Allow display over other apps")) }
    }
    if (on) Text(if (pool > 0) tr("%d i+1 scenes to pick from (refreshed when kumapie opens).", pool)
        else tr("Reading the episodes for i+1 scenes…"), fontSize = 13.sp, color = Colors.dim)
    Text(tr("If the separate Unlock Cards app is still on, turn it off so only one opens."), fontSize = 13.sp, color = Colors.dim)
}

/** New episodes made on the tablet: the Soniox key (kept only in kumapie's private settings) and the English source. */
@Composable
private fun NewEpisodesSection(library: Library) {
    var key by remember { mutableStateOf(library.settings.sonioxKey) }
    var rd by remember { mutableStateOf(library.settings.rdToken) }
    var jimaku by remember { mutableStateOf(library.settings.jimakuKey) }
    var english by remember { mutableStateOf(library.settings.englishSource) }
    var transcriber by remember { mutableStateOf(library.settings.transcriber) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val parakeet = remember { io.github.pedrubik2000.kumapie.mobile.local.Parakeet(context) }
    val parakeetState by remember { parakeet.state() }.collectAsState(initial = null)
    var parakeetReady by remember { mutableStateOf(parakeet.isReady) }
    LaunchedEffect(parakeetState) { parakeetReady = parakeet.isReady }
    val gemma = remember { io.github.pedrubik2000.kumapie.mobile.local.Gemma(context) }
    val gemmaState by remember { gemma.state() }.collectAsState(initial = null)
    var gemmaReady by remember { mutableStateOf(gemma.isReady) }
    LaunchedEffect(gemmaState) { gemmaReady = gemma.isReady }
    Text(tr("New episodes"), color = Colors.accent)
    Text(tr("Transcription"), fontSize = 15.sp)
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilterChip(selected = transcriber == "soniox", label = { Text(tr("Soniox (best, paid)")) },
            onClick = { transcriber = "soniox"; library.settings.transcriber = "soniox" })
        androidx.compose.material3.FilterChip(selected = transcriber == "parakeet", label = { Text(tr("Parakeet on the tablet (free, offline)")) },
            onClick = { transcriber = "parakeet"; library.settings.transcriber = "parakeet" })
    }
    if (transcriber == "parakeet") when {
        parakeetReady -> Text(tr("Parakeet: ready. More mistakes than Soniox (about 1 word in 9 differed in a test)."),
            fontSize = 13.sp, color = Colors.dim)
        parakeetState != null -> Text(tr("Parakeet: %s", parakeetState), fontSize = 13.sp, color = Colors.dim)
        else -> OutlinedButton(onClick = { parakeet.download() }) { Text(tr("Download Parakeet (about 640 MB, once)")) }
    }
    OutlinedTextField(key, { key = it; library.settings.sonioxKey = it }, label = { Text(tr("Soniox API key")) }, singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(rd, { rd = it; library.settings.rdToken = it }, label = { Text(tr("Real-Debrid token (real-debrid.com/apitoken)")) },
        singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(jimaku, { jimaku = it; library.settings.jimakuKey = it }, label = { Text(tr("Jimaku API key (jimaku.cc > Account), Japanese subtitles")) },
        singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Text(tr("%s subtitles", tr(io.github.pedrubik2000.kumapie.data.Lang.GERMAN.translationName)), fontSize = 15.sp)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilterChip(selected = english == "soniox", label = { Text(tr("Soniox (best, no extra cost)")) },
            onClick = { english = "soniox"; library.settings.englishSource = "soniox" })
        androidx.compose.material3.FilterChip(selected = english == "gemma", label = { Text(tr("Gemma on the tablet (free, offline, good)")) },
            onClick = { english = "gemma"; library.settings.englishSource = "gemma" })
        androidx.compose.material3.FilterChip(selected = english == "device", label = { Text(tr("Google's translator (instant, rough)")) },
            onClick = { english = "device"; library.settings.englishSource = "device" })
        androidx.compose.material3.FilterChip(selected = english == "none", label = { Text(tr("None (no translation, fastest)")) },
            onClick = { english = "none"; library.settings.englishSource = "none" })
    }
    if (english == "soniox" && transcriber == "parakeet") {
        Text(tr("With Parakeet there is no Soniox: the English then comes from Google's translator."), fontSize = 13.sp, color = Colors.dim)
    }
    if (english == "gemma") when {
        gemmaReady -> Text(tr("Gemma: ready. It takes about 3 seconds a line (some 15 minutes for a 10-minute video) in the background."),
            fontSize = 13.sp, color = Colors.dim)
        gemmaState != null -> Text(tr("Gemma: %s", gemmaState), fontSize = 13.sp, color = Colors.dim)
        else -> OutlinedButton(onClick = { gemma.download() }) { Text(tr("Download Gemma (about 2.8 GB, once)")) }
    }
    Text(tr("Add episodes with + on the home screen (YouTube, magnets, Real-Debrid links, video files), or share a link to kumapie."), fontSize = 13.sp, color = Colors.dim)
    FollowedChannels(library)
}

/** Followed YouTube channels (Add an episode > Follow): the hours they may download in, how many a check, remove, check now. */
@Composable
private fun FollowedChannels(library: Library) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val subs = remember { io.github.pedrubik2000.kumapie.mobile.local.Subscriptions(context) }
    var list by remember { mutableStateOf(subs.all()) }
    var from by remember { mutableStateOf(library.settings.subFrom) }
    var to by remember { mutableStateOf(library.settings.subTo) }
    Text(tr("Followed channels"), color = Colors.accent, modifier = Modifier.padding(top = 12.dp))
    if (list.isEmpty()) {
        Text(tr("None yet. In Add an episode, paste a channel's video, pick its shorts or videos and turn on Follow."), fontSize = 13.sp, color = Colors.dim)
        return
    }
    list.forEach { s ->
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((s.name.ifBlank { s.url.substringAfter("youtube.com/").substringBefore('?') }) + " · " + (if (s.kind == "shorts") tr("shorts") else tr("videos")), fontSize = 15.sp)
                Text(tr("%d a night", s.perRun) + " · " + (if (s.lastCheck > 0) tr("checked %s", android.text.format.DateUtils.getRelativeTimeSpanString(s.lastCheck)) else tr("not checked yet")),
                    fontSize = 13.sp, color = Colors.dim)
            }
            listOf(1, 5, 10).forEach { n ->
                androidx.compose.material3.FilterChip(selected = s.perRun == n, label = { Text("$n") }, modifier = Modifier.padding(start = 4.dp),
                    onClick = { subs.save(subs.all().map { if (it == s) it.copy(perRun = n) else it }); list = subs.all() })
            }
            androidx.compose.material3.TextButton(onClick = { subs.remove(s); list = subs.all() }) { Text(tr("Remove")) }
        }
    }
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Download between"), fontSize = 14.sp)
        OutlinedTextField(from.toString(), { v -> v.filter(Char::isDigit).take(2).toIntOrNull()?.takeIf { it in 0..23 }?.let { from = it; library.settings.subFrom = it } },
            singleLine = true, modifier = Modifier.width(70.dp))
        Text(tr("and"), fontSize = 14.sp)
        OutlinedTextField(to.toString(), { v -> v.filter(Char::isDigit).take(2).toIntOrNull()?.takeIf { it in 0..24 }?.let { to = it; library.settings.subTo = it } },
            singleLine = true, modifier = Modifier.width(70.dp))
        Text(tr("o'clock, on Wi-Fi"), fontSize = 14.sp)
    }
    OutlinedButton(onClick = { io.github.pedrubik2000.kumapie.mobile.local.SubscriptionWorker.checkNow(context) }) { Text(tr("Check now")) }
}

/** English words from 🐻 English (English Core 1000): spaCy's English model (downloaded once) and reading Anki for English. */
@Composable
private fun EnglishWordsSection(library: Library) {
    val known = library.knownEn
    val scope = rememberCoroutineScope()
    val status by known.status.collectAsState()
    val state by remember { known.model.state() }.collectAsState(initial = null)
    var ready by remember { mutableStateOf(known.model.isReady) }
    LaunchedEffect(state) { ready = known.model.isReady }
    var busy by remember { mutableStateOf(false) }
    val app = remember { known.ankiApp() }
    Text(tr("English words"), color = Colors.accent, modifier = Modifier.padding(top = 8.dp))
    Text(status, fontSize = 15.sp)
    when {
        ready -> Text(tr("English model: ready (%s).", known.model.name), fontSize = 14.sp, color = Colors.dim)
        state != null -> Text(tr("English model: %s", state), fontSize = 14.sp, color = Colors.dim)
        else -> OutlinedButton(onClick = { known.model.download() }) { Text(tr("Download the English model (about 40 MB)")) }
    }
    if (ready && app != null && known.hasPermission(app)) OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch { known.refresh(); busy = false }
    }) { Text(if (busy) tr("Reading Anki…") else tr("Read English cards now")) }
    // Mined English cards: order by unknown words and unlock their English definitions (as for German).
    if (ready && app != null && app != "com.ichi2.anki" && known.hasPermission(app)) {
        var said by remember { mutableStateOf("") }
        val recalc = remember { io.github.pedrubik2000.kumapie.mobile.lang.Recalc(known) }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            said = tr("Working out the order…")
            scope.launch {
                said = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val plan = recalc.plan()
                    plan.describe() + if (plan.empty) "" else "\n" + recalc.apply(plan)
                } }.getOrElse { it.message ?: it.toString() }
                busy = false
            }
        }) { Text(tr("Order mined English cards and unlock definitions")) }
        if (said.isNotEmpty()) Text(said, fontSize = 14.sp, color = Colors.dim)
    }
}

/** The menus' language: the phone's, English or Spanish. The screen restarts so every text redraws. */
@Composable
private fun MenuLanguage(settings: Settings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Text(tr("Menu language"), color = Colors.accent)
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to tr("Phone's language"), "en" to "English", "es" to "Español").forEach { (code, label) ->
            androidx.compose.material3.FilterChip(selected = settings.menuLanguage == code, label = { Text(label) }, onClick = {
                settings.menuLanguage = code
                (context as? android.app.Activity)?.recreate()
            })
        }
    }
    // The language the person speaks: translation lines and meanings (Spanish for Giovanna and Jackson).
    Text(tr("I speak"), color = Colors.accent)
    Text(tr("Meanings and the second subtitle line come in this language."), color = Colors.dim, fontSize = 14.sp)
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("en" to "English", "es" to "Español").forEach { (code, label) ->
            androidx.compose.material3.FilterChip(selected = settings.speaks == code, label = { Text(label) }, onClick = {
                settings.speaks = code
                (context as? android.app.Activity)?.recreate()
            })
        }
    }
    // Whose device: its episodes go to that person's PC library and to their other devices.
    Text(tr("This device belongs to"), color = Colors.accent)
    Text(tr("Episodes made here go to the PC, and the PC's new episodes come here (on Wi-Fi)."), color = Colors.dim, fontSize = 14.sp)
    var owner by remember { mutableStateOf(settings.owner) }
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("pedro" to "Pedro", "giovanna" to "Giovanna", "jackson" to "Jackson", "" to tr("Nobody (no sync)")).forEach { (key, label) ->
            androidx.compose.material3.FilterChip(selected = owner == key, label = { Text(label) }, onClick = {
                owner = key
                settings.owner = key
                if (key.isNotBlank()) {
                    io.github.pedrubik2000.kumapie.mobile.local.SyncWorker.schedule(context)
                    io.github.pedrubik2000.kumapie.mobile.local.SyncWorker.now(context)
                }
            })
        }
    }
}

/**
 * kumapie to / from another device on the same Wi-Fi, without the PC ([io.github.pedrubik2000.kumapie.mobile.local.Transfer]):
 * episodes, dictionaries, models and keys. Also on the first-run screen, so a new phone gets everything from another.
 */
@Composable
private fun TransferSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val work = remember { androidx.work.WorkManager.getInstance(context) }
    val sends by remember { work.getWorkInfosForUniqueWorkFlow(Transfer.SEND) }.collectAsState(initial = emptyList())
    val gets by remember { work.getWorkInfosForUniqueWorkFlow(Transfer.RECEIVE) }.collectAsState(initial = emptyList())
    val sending = sends.firstOrNull { !it.state.isFinished }
    val receiving = gets.lastOrNull()
    var dialog by remember { mutableStateOf(false) }
    Text(tr("Another device on this Wi-Fi"), color = Colors.accent)
    Text(tr("Copy episodes, dictionaries, models and keys from one kumapie to another, without the PC."), color = Colors.dim, fontSize = 14.sp)
    if (sending != null) {
        val code = sending.progress.getString("code")
        if (code != null) Text(tr("Sending. On the other device: Receive, then code %1\$s (address %2\$s).", code,
            sending.progress.getString("address") ?: "?"), fontSize = 15.sp)
        OutlinedButton(onClick = { work.cancelUniqueWork(Transfer.SEND) }) { Text(tr("Stop sending")) }
    } else {
        OutlinedButton(onClick = { Transfer.send(context, (1000..9999).random().toString()) }) { Text(tr("Send to another device")) }
    }
    when (receiving?.state) {
        androidx.work.WorkInfo.State.RUNNING, androidx.work.WorkInfo.State.ENQUEUED -> {
            val f = receiving.progress.getFloat("progress", 0f)
            Text(tr("Copying: %1\$s (%2\$d%%)", receiving.progress.getString("what") ?: "…", (f * 100).toInt()), fontSize = 15.sp)
            OutlinedButton(onClick = { work.cancelUniqueWork(Transfer.RECEIVE) }) { Text(tr("Stop copying")) }
        }
        else -> {
            receiving?.outputData?.getString("done")?.let { Text(tr("Copied: %s.", it), fontSize = 15.sp, color = Colors.dim) }
            receiving?.outputData?.getString("error")?.let { Text(tr("Copy failed: %s", it), fontSize = 15.sp, color = Colors.dim) }
            OutlinedButton(onClick = { dialog = true }) { Text(tr("Receive from another device")) }
        }
    }
    if (dialog) ReceiveDialog { dialog = false }
}

/** Finds sending devices (network service discovery), then the code and what to copy. */
@Composable
private fun ReceiveDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val found = remember { androidx.compose.runtime.mutableStateListOf<Pair<String, String>>() }
    val chosen = remember { androidx.compose.runtime.mutableStateListOf(*Transfer.CATEGORIES.toTypedArray()) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val nsd = context.getSystemService(android.net.nsd.NsdManager::class.java)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val listener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onServiceFound(info: android.net.nsd.NsdServiceInfo) {
                @Suppress("DEPRECATION") // its replacement needs Android 14
                nsd.resolveService(info, object : android.net.nsd.NsdManager.ResolveListener {
                    override fun onServiceResolved(i: android.net.nsd.NsdServiceInfo) {
                        val a = "${i.host.hostAddress}:${i.port}"
                        main.post {
                            if (found.none { it.second == a }) found += i.serviceName.removePrefix("kumapie ") to a
                            if (address.isEmpty()) address = a
                        }
                    }
                    override fun onResolveFailed(i: android.net.nsd.NsdServiceInfo, e: Int) {}
                })
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(i: android.net.nsd.NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        nsd.discoverServices(Transfer.SERVICE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener)
        onDispose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
    val labels = mapOf("episodes" to tr("Episodes"), "dictionaries" to tr("Dictionaries"),
        "models" to tr("Models (speech, translation, words)"), "keys" to tr("Keys and the PC's address"))
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Receive from another device")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (found.isEmpty()) tr("Looking for a device that is sending… (or type its address)") else tr("Found:"),
                    fontSize = 14.sp, color = Colors.dim)
                found.forEach { (name, a) ->
                    androidx.compose.material3.FilterChip(selected = address == a, onClick = { address = a }, label = { Text(name) })
                }
                OutlinedTextField(address, { address = it.trim() }, label = { Text(tr("Address")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(4) }, label = { Text(tr("Code")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Transfer.CATEGORIES.forEach { c ->
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(c in chosen, { if (it) chosen += c else chosen -= c })
                        Text(labels.getValue(c))
                    }
                }
                Text(tr("Only what this device doesn't have yet is copied; keys only where this device has none."),
                    fontSize = 13.sp, color = Colors.dim)
            }
        },
        confirmButton = {
            Button(enabled = address.isNotBlank() && code.length == 4 && chosen.isNotEmpty(), onClick = {
                Transfer.receive(context, address, code, chosen.toSet())
                onDismiss()
            }) { Text(tr("Copy")) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
