package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.focus.onFocusChanged
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

/** Settings' sections, most visited first (kumapie_settings_ia.html, 2026-10-15): key and title. */
private val SECTIONS = listOf(
    "episodes" to "Making episodes", "words" to "Known words", "dicts" to "Dictionaries & word audio",
    "watching" to "Watching", "device" to "You & this device", "storage" to "Storage & updates",
)

/**
 * Settings: six sections, each row with a one-line summary; a phone opens one at a time, a wide screen shows the
 * list and the open section side by side. [section] opens one directly (the Add sheet's "Open Settings").
 * The first run shows only the language, the PC's address and copying from another device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(library: Library, firstRun: Boolean, onSaved: () -> Unit, onUpdate: (Updater.Release) -> Unit, onBack: () -> Unit,
                   section: String? = null) {
    val settings = library.settings
    if (firstRun) {
        Scaffold(topBar = { TopAppBar(title = { Text(tr("Welcome to kumapie")) }) }) { padding ->
            Page(Modifier.padding(padding)) {
                MenuLanguage(settings)
                ServerSection(library, onSaved)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TransferSection()
                Version()
            }
        }
        return
    }
    var open by remember { mutableStateOf(section) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        // A phone: Back closes the open section first (registered after the app's own Back, so it wins).
        androidx.activity.compose.BackHandler(enabled = !wide && open != null) { open = null }
        val title = if (!wide) open?.let { k -> SECTIONS.first { it.first == k }.second } else null
        Scaffold(topBar = {
            TopAppBar(title = { Text(tr(title ?: "Settings")) },
                navigationIcon = { IconButton(onClick = { if (!wide && open != null) open = null else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
        }) { padding ->
            if (wide) androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.width(380.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    SectionList(library, open ?: "episodes") { open = it }
                }
                Page(Modifier.weight(1f).widthIn(max = 760.dp)) { SectionContent(library, open ?: "episodes", onSaved, onUpdate) }
            } else when (val k = open) {
                null -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    SectionList(library, null) { open = it }
                }
                else -> Page(Modifier.padding(padding)) { SectionContent(library, k, onSaved, onUpdate) }
            }
        }
    }
}

/** A scrolling page of settings. */
@Composable
private fun Page(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content)

@Composable
private fun Version(modifier: Modifier = Modifier) =
    Text(tr("Version %s", BuildConfig.VERSION_NAME), color = Colors.dim, fontSize = 13.sp, modifier = modifier.padding(top = 12.dp))

/** The six rows: title and what is set now, so a look is often enough. */
@Composable
private fun SectionList(library: Library, selected: String?, onOpen: (String) -> Unit) {
    val summaries = rememberSummaries(library)
    SECTIONS.forEach { (key, title) ->
        androidx.compose.material3.Surface(onClick = { onOpen(key) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = if (key == selected) Colors.surface else androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(tr(title), fontSize = 17.sp, color = Colors.text)
                summaries[key]?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 14.sp, color = Colors.dim) }
            }
        }
    }
}

/** Each section's one-line summary (read off the IO thread: the known-word lists and the download size take a moment). */
@Composable
private fun rememberSummaries(library: Library): Map<String, String> {
    val settings = library.settings
    val context = androidx.compose.ui.platform.LocalContext.current
    val dicts by library.yomitan.all.collectAsState()
    var slow by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(Unit) {
        slow = withContext(Dispatchers.IO) {
            val learning = settings.learning()
            mapOf(
                "words" to library.languages.all.filter { it.lang.code in learning && it.known.ready }
                    .joinToString(" · ") { "${tr(it.lang.name)} %,d".format(it.known.knownList().size) },
                "storage" to tr("%.1f GB downloaded", library.downloads.bytesUsed() / 1e9) + " · " + tr("version %s", BuildConfig.VERSION_NAME),
            )
        }
    }
    val followed = remember { io.github.pedrubik2000.kumapie.mobile.local.Subscriptions(context).all().size }
    val english = when (settings.englishSource) {
        "soniox" -> tr("Soniox translation"); "gemma" -> tr("Gemma translation"); "device" -> tr("Google translation"); else -> tr("no translation")
    }
    val learning = remember { settings.learning() }
    return slow + mapOf(
        "episodes" to listOfNotNull(if (settings.transcriber == "parakeet") "Parakeet" else "Soniox", english,
            followed.takeIf { it > 0 }?.let { tr("%d followed", it) }).joinToString(" · "),
        "dicts" to io.github.pedrubik2000.kumapie.data.Lang.ALL.filter { it.code in learning }
            .map { l -> "${tr(l.name)} ${dicts.count { it.lang == l.code && it.enabled }}" }.joinToString(" · "),
        "watching" to (if (settings.blurUnwatched) tr("No spoilers on") else tr("No spoilers off")) + " · " +
            tr("popup %1\$d × %2\$d", settings.popupWidth, settings.popupHeight),
        "device" to listOf(when (settings.owner) { "" -> tr("Nobody's (no sync)"); else -> tr("%s's", settings.owner.replaceFirstChar(Char::uppercase)) },
            when (settings.menuLanguage) { "en" -> "English"; "es" -> "Español"; else -> tr("Phone's language") }).joinToString(" · "),
    )
}

/** One section's settings: the existing blocks, regrouped. */
@Composable
private fun SectionContent(library: Library, key: String, onSaved: () -> Unit, onUpdate: (Updater.Release) -> Unit) {
    val settings = library.settings
    val learning = remember { settings.learning() } // only the languages this device learns (a parent's phone: English)
    when (key) {
        "episodes" -> NewEpisodesSection(library, learning)
        "words" -> KnownWordsSection(library, learning)
        "dicts" -> {
            YomitanSection(library, learning)
            if ("de" in learning) { HorizontalDivider(Modifier.padding(vertical = 8.dp)); DictionarySection(library) }
        }
        "watching" -> {
            var blur by remember { mutableStateOf(settings.blurUnwatched) }
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(tr("Blur pictures of episodes I haven't watched (no spoilers)"), fontSize = 15.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(blur, { blur = it; settings.blurUnwatched = it })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            PopupSize(settings)
            if ("ja" in learning) { HorizontalDivider(Modifier.padding(vertical = 8.dp)); JapaneseFont(settings) }
        }
        "device" -> {
            MenuLanguage(settings)
            ServerSection(library, onSaved)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TransferSection()
        }
        "storage" -> StorageSection(library, onUpdate)
    }
}

/** The PC's address: saved only once the PC answers. */
@Composable
private fun ServerSection(library: Library, onSaved: () -> Unit) {
    val settings = library.settings
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(settings.server) }
    var message by remember { mutableStateOf("") }
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
}

/** Downloads on the device, reports waiting for the PC, the app's updates and version. */
@Composable
private fun StorageSection(library: Library, onUpdate: (Updater.Release) -> Unit) {
    val scope = rememberCoroutineScope()
    var used by remember { mutableLongStateOf(0L) }
    var waiting by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(refresh) {
        used = withContext(Dispatchers.IO) { library.downloads.bytesUsed() }
        waiting = library.pending.count()
    }
    Text(tr("On this device"), color = Colors.accent)
    Text(tr("Downloads: %.1f GB", used / 1e9), fontSize = 15.sp)
    var confirmRemove by remember { mutableStateOf(false) }
    OutlinedButton(enabled = used > 0, onClick = { confirmRemove = true }) { Text(tr("Remove all downloads")) }
    if (confirmRemove) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text(tr("Remove all downloads?")) },
        text = { Text(tr("This deletes %.1f GB of episodes saved on this device. You can download them again on Wi-Fi.", used / 1e9)) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { library.downloads.deleteAll(); refresh++; confirmRemove = false }) { Text(tr("Remove downloads")) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmRemove = false }) { Text(tr("Keep them")) } })
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
    }) { Text(tr("Check for a new kumapie version")) }
    if (message.isNotEmpty()) Text(message, color = Colors.dim, fontSize = 14.sp)
    Version()
}

/**
 * Word colours from the device's own Anki: permission to read AnkiDroid and the stability that makes a word known
 * (shared by every language), then each language this device learns ([LanguageWordsSection]).
 */
@Composable
private fun KnownWordsSection(library: Library, learning: Set<String>) {
    val known = library.languages.german.known // the permission is the same for every language
    val app = remember { known.ankiApp() }
    var allowed by remember { mutableStateOf(app != null && known.hasPermission(app)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
    }

    Text(tr("Word colours from Anki"), color = Colors.accent)
    when {
        app == null -> Text(tr("No kuma3 Anki or AnkiDroid on this device."), fontSize = 14.sp, color = Colors.dim)
        !allowed -> OutlinedButton(onClick = { ask.launch(known.permission(app)) }) { Text(tr("Allow reading Anki")) }
        else -> Text(tr("Anki: %s.", when (app) {
            "io.github.pedrubik2000.kuma3" -> "kuma3 Anki"
            "com.ichi2.anki.debug" -> tr("kuma3 test build")
            else -> app
        }), fontSize = 14.sp, color = Colors.dim)
    }

    library.languages.all.filter { it.lang.code in learning }.forEach { LanguageWordsSection(it, allowed, app) }
}

/**
 * One language's words from Anki: its parser (spaCy, the same as morphs on the PC; Sudachi for Japanese; downloaded
 * once) and reading its cards again (also done when the app starts).
 */
@Composable
private fun LanguageWordsSection(language: io.github.pedrubik2000.kumapie.lang.Language, allowed: Boolean, app: String?) {
    val known = language.known
    val model = language.model
    val scope = rememberCoroutineScope()
    val status by known.status.collectAsState()
    val state by remember { model.state() }.collectAsState(initial = null)
    var ready by remember { mutableStateOf(model.isReady) }
    LaunchedEffect(state) { ready = model.isReady }
    var busy by remember { mutableStateOf(false) }

    Text(tr("${language.lang.name} words"), color = Colors.accent, modifier = Modifier.padding(top = 8.dp))
    // The total Home shows first; the card count under it says where part of it comes from.
    val total = remember(status) { known.knownList().size }
    if (known.ready) Text(tr("%d words known (as on Home: cards, words marked known and imported lists).", total), fontSize = 15.sp)
    Text(tr("From your cards: %s", status), fontSize = 14.sp, color = Colors.dim)
    when {
        state != null -> Text("${tr(model.label)}: $state", fontSize = 14.sp, color = Colors.dim)
        ready -> {
            Text(tr("%1\$s: ready (%2\$s).", tr(model.label), model.name), fontSize = 14.sp, color = Colors.dim)
            // Jiten's word splitter (item 26b): two more files from the PC, optional.
            if (model is io.github.pedrubik2000.kumapie.lang.JapaneseModel && !model.splitterReady)
                OutlinedButton(onClick = { model.download() }) { Text(tr("Download Jiten's word splitter from the PC")) }
        }
        else -> {
            if (model.about.isNotEmpty()) Text(tr(model.about), fontSize = 14.sp, color = Colors.dim)
            OutlinedButton(onClick = { model.download() }) { Text(tr(model.downloadText)) }
        }
    }
    if (ready && allowed) OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch { known.refresh(); busy = false }
    }) { Text(if (busy) tr("Reading Anki…") else tr("Read ${language.lang.name} cards now")) }

    if (language is io.github.pedrubik2000.kumapie.lang.Japanese) JitenImport(known)
}

/**
 * Japanese words imported as known from jiten.moe (kumapie_plan.md item 6): its top words by frequency and the
 * picked word sets. They count as known while the word has no Mined card; names and fillers are known anyway.
 */
@Composable
private fun JitenImport(known: io.github.pedrubik2000.kumapie.lang.KnownWords) {
    val jiten = io.github.pedrubik2000.kumapie.lang.Jiten
    val scope = rememberCoroutineScope()
    var top by remember { mutableStateOf("10000") }
    val sets = remember { androidx.compose.runtime.mutableStateMapOf(*jiten.SETS.keys.map { it to (it != "kaishi-15") }.toTypedArray()) }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf(known.importedWords.size.takeIf { it > 0 }?.let { tr("%d words imported as known.", it) } ?: "") }

    Text(tr("Known words from jiten.moe"), color = Colors.accent, modifier = Modifier.padding(top = 8.dp))
    OutlinedTextField(top, { top = it.filter(Char::isDigit) }, label = { Text(tr("Most frequent words")) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    jiten.SETS.forEach { (slug, name) ->
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(sets[slug] == true, { sets[slug] = it })
            Text(tr(name), fontSize = 15.sp)
        }
    }
    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            said = tr("Downloading from jiten.moe…")
            scope.launch {
                said = runCatching {
                    val words = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        jiten.top(top.toIntOrNull() ?: 0) + sets.filterValues { it }.keys.flatMap(jiten::set)
                    }
                    known.setImported(words)
                    tr("%d words imported as known.", words.size)
                }.getOrElse { it.message ?: it.toString() }
                busy = false
            }
        }) { Text(tr("Import")) }
        if (known.importedWords.isNotEmpty()) OutlinedButton(enabled = !busy, onClick = {
            known.setImported(emptySet()); said = ""
        }) { Text(tr("Remove imported")) }
    }
    if (said.isNotEmpty()) Text(said, fontSize = 14.sp, color = Colors.dim)
    Text(tr("Known while the word has no card in Japanese Mined. Names and fillers (えっと) count as known anyway."),
        fontSize = 13.sp, color = Colors.dim)
}

/**
 * Yomitan dictionaries (kumapie_languages_plan.md step 2b): import .zip files per language, turn them on or off,
 * order them (the popup shows meanings in this order), delete. German words are looked up in them first.
 */
@Composable
private fun YomitanSection(library: Library, learning: Set<String> = emptySet()) {
    val dicts = library.yomitan
    val scope = rememberCoroutineScope()
    val all by dicts.all.collectAsState()
    val langs = io.github.pedrubik2000.kumapie.data.Lang.ALL.filter { learning.isEmpty() || it.code in learning }
    var lang by remember { mutableStateOf(langs.firstOrNull() ?: io.github.pedrubik2000.kumapie.data.Lang.GERMAN) }
    var said by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.Dict?>(null) }
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
        if (langs.size > 1) langs.forEach { l ->
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
    if (io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.RECOMMENDED[lang.code] != null) OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch { said = dicts.installRecommended(lang) { said = it }.joinToString("\n"); busy = false }
    }) { Text(tr("Download the recommended %s dictionaries", langName)) }
    if (all.any { it.updatable }) OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch { said = dicts.update { said = it }.ifEmpty { listOf(tr("Every dictionary is up to date.")) }.joinToString("\n"); busy = false }
    }) { Text(tr("Check dictionaries for updates")) }
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
        library.languages.german.voice // starts the speech engine
        kotlinx.coroutines.delay(1500)
        voice = library.languages.german.voice.describe()
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
    Text(io.github.pedrubik2000.kumapie.lang.Dictionary.ATTRIBUTION, fontSize = 12.sp, color = Colors.dim)
}

/** New episodes made on the tablet: the Soniox key (kept only in kumapie's private settings) and the English source. */
@Composable
private fun NewEpisodesSection(library: Library, learning: Set<String>) {
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
    val parakeetJa = remember { io.github.pedrubik2000.kumapie.mobile.local.Parakeet(context, japanese = true) }
    val parakeetJaState by remember { parakeetJa.state() }.collectAsState(initial = null)
    var parakeetJaReady by remember { mutableStateOf(parakeetJa.isReady) }
    LaunchedEffect(parakeetJaState) { parakeetJaReady = parakeetJa.isReady }
    val gemma = remember { io.github.pedrubik2000.kumapie.mobile.local.Gemma(context) }
    val gemmaState by remember { gemma.state() }.collectAsState(initial = null)
    var gemmaReady by remember { mutableStateOf(gemma.isReady) }
    LaunchedEffect(gemmaState) { gemmaReady = gemma.isReady }
    Text(tr("Transcription"), fontSize = 15.sp)
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilterChip(selected = transcriber == "soniox", label = { Text(tr("Soniox (best, paid)")) },
            onClick = { transcriber = "soniox"; library.settings.transcriber = "soniox" })
        androidx.compose.material3.FilterChip(selected = transcriber == "parakeet", label = { Text(tr("Parakeet on this device (free, offline)")) },
            onClick = { transcriber = "parakeet"; library.settings.transcriber = "parakeet" })
    }
    if (transcriber == "parakeet") when {
        parakeetReady -> Text(tr("Parakeet: ready. More mistakes than Soniox (about 1 word in 9 differed in a test)."),
            fontSize = 13.sp, color = Colors.dim)
        parakeetState != null -> Text(tr("Parakeet: %s", parakeetState), fontSize = 13.sp, color = Colors.dim)
        else -> OutlinedButton(onClick = { parakeet.download() }) { Text(tr("Download Parakeet (about 640 MB, once)")) }
    }
    KeyField(key, { key = it; library.settings.sonioxKey = it }, tr("Soniox API key"), KeyService.SONIOX)
    KeyField(rd, { rd = it; library.settings.rdToken = it }, tr("Real-Debrid token (real-debrid.com/apitoken)"), KeyService.REAL_DEBRID)
    if ("ja" in learning) KeyField(jimaku, { jimaku = it; library.settings.jimakuKey = it }, tr("Jimaku API key (jimaku.cc > Account), Japanese subtitles"), KeyService.JIMAKU)
    // Japanese YouTube without Japanese subtitles: transcribed on the device.
    if ("ja" in learning) when {
        parakeetJaReady -> Text(tr("Japanese YouTube: Parakeet ja ready (free, offline). About 1 character in 6 differs from people's subtitles."),
            fontSize = 13.sp, color = Colors.dim)
        parakeetJaState != null -> Text(tr("Parakeet ja: %s", parakeetJaState), fontSize = 13.sp, color = Colors.dim)
        else -> OutlinedButton(onClick = { parakeetJa.download() }) { Text(tr("Download Parakeet ja for Japanese YouTube (about 655 MB, once)")) }
    }
    Text(tr("%s subtitles", tr(io.github.pedrubik2000.kumapie.data.Lang.GERMAN.translationName)), fontSize = 15.sp)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilterChip(selected = english == "soniox", label = { Text(tr("Soniox (best, no extra cost)")) },
            onClick = { english = "soniox"; library.settings.englishSource = "soniox" })
        androidx.compose.material3.FilterChip(selected = english == "gemma", label = { Text(tr("Gemma on this device (free, offline, good)")) },
            onClick = { english = "gemma"; library.settings.englishSource = "gemma" })
        androidx.compose.material3.FilterChip(selected = english == "device", label = { Text(tr("Google's translator (instant, rough)")) },
            onClick = { english = "device"; library.settings.englishSource = "device" })
        androidx.compose.material3.FilterChip(selected = english == "none", label = { Text(tr("None (no translation, fastest)")) },
            onClick = { english = "none"; library.settings.englishSource = "none" })
    }
    if (english == "soniox" && transcriber == "parakeet") {
        Text(tr("With Parakeet there is no Soniox: the %s then comes from Google's translator.",
            io.github.pedrubik2000.kumapie.data.Lang.GERMAN.translationDisplayName), fontSize = 13.sp, color = Colors.dim)
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
                Text((s.name.ifBlank { s.url.substringAfter("youtube.com/").substringBefore('?') }) + " · " + (if (s.kind == "shorts") tr("shorts") else tr("videos"))
                    + if (s.here) "" else " · " + tr("downloads on %s", s.device), fontSize = 15.sp)
                // Checked hourly inside the download hours: perRun is per check, not per night.
                val hours = ((to - from) % 24 + 24) % 24
                Text(tr("%1\$d an hour · up to %2\$d a night", s.perRun, s.perRun * hours) + " · " + (if (s.lastCheck > 0) tr("checked %s", android.text.format.DateUtils.getRelativeTimeSpanString(s.lastCheck)) else tr("not checked yet")),
                    fontSize = 13.sp, color = Colors.dim)
            }
            listOf(1, 5, 10).forEach { n ->
                androidx.compose.material3.FilterChip(selected = s.perRun == n, label = { Text("$n") }, modifier = Modifier.padding(start = 4.dp),
                    onClick = { subs.add(s.copy(perRun = n)); list = subs.all() }) // add replaces it in place, and shares the change
            }
            androidx.compose.material3.TextButton(onClick = { subs.remove(s); list = subs.all() }) { Text(tr("Remove")) }
        }
    }
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Download between"), fontSize = 14.sp)
        HourField(from, 0..23) { from = it; library.settings.subFrom = it }
        Text(tr("and"), fontSize = 14.sp)
        HourField(to, 0..24) { to = it; library.settings.subTo = it }
        Text(tr("o'clock, on Wi-Fi"), fontSize = 14.sp)
    }
    // From == to is an empty window: SubscriptionWorker.inHours never matches, so nothing would ever download.
    if (from == to || (from == 0 && to == 24)) Text(if (from == to) tr("No hours: the two times are the same, so nothing downloads by itself.")
        else tr("All day: downloads may run at any hour."), fontSize = 13.sp, color = Colors.unknown)
    OutlinedButton(onClick = { io.github.pedrubik2000.kumapie.mobile.local.SubscriptionWorker.checkNow(context) }) { Text(tr("Check now")) }
}


/** Japanese subtitles' font (JapaneseFonts): each chip in its own font, the sample line in the one picked. */
@Composable
private fun JapaneseFont(settings: Settings) {
    var font by remember { mutableStateOf(settings.japaneseFont) }
    Text(tr("Japanese subtitle font"), color = Colors.accent)
    Text(io.github.pedrubik2000.kumapie.ui.JapaneseFonts.SAMPLE, fontSize = 22.sp,
        fontFamily = io.github.pedrubik2000.kumapie.ui.JapaneseFonts.family(font))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        io.github.pedrubik2000.kumapie.ui.JapaneseFonts.NAMES.forEach { name ->
            androidx.compose.material3.FilterChip(selected = font == name, onClick = { font = name; settings.japaneseFont = name },
                label = { Text(io.github.pedrubik2000.kumapie.ui.JapaneseFonts.label(name),
                    fontFamily = io.github.pedrubik2000.kumapie.ui.JapaneseFonts.family(name)) })
        }
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
    var asking by remember { mutableStateOf<Pair<String, String>?>(null) } // the owner picked, waiting for Yes
    fun setOwner(key: String) {
        owner = key
        settings.owner = key
        if (key.isNotBlank()) {
            io.github.pedrubik2000.kumapie.mobile.local.SyncWorker.schedule(context)
            io.github.pedrubik2000.kumapie.mobile.local.SyncWorker.now(context)
        }
    }
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("pedro" to "Pedro", "giovanna" to "Giovanna", "jackson" to "Jackson", "" to tr("Nobody (no sync)")).forEach { (key, label) ->
            androidx.compose.material3.FilterChip(selected = owner == key, label = { Text(label) }, onClick = {
                // A first owner is set at once; moving the device to someone else (or to nobody) asks first.
                if (owner.isBlank() || key == owner) setOwner(key) else asking = key to label
            })
        }
    }
    asking?.let { (key, name) ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { asking = null },
            title = { Text(if (key.isBlank()) tr("Stop syncing this device?") else tr("Make this %s's device?", name)) },
            text = { Text(if (key.isBlank()) tr("Episodes made here stay on this device, and the PC's new episodes stop coming here.")
                else tr("Episodes made here will go to %1\$s's library on the PC, and this device's known words will count as %1\$s's.", name)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { setOwner(key); asking = null }) {
                Text(if (key.isBlank()) tr("Stop syncing") else tr("Yes, it's %s's", name)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = null }) { Text(tr("Cancel")) } })
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
    // Android 17 blocks the local network until the app holds ACCESS_LOCAL_NETWORK: asked once, before the first transfer.
    var then by remember { mutableStateOf<(() -> Unit)?>(null) }
    val lan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { then?.invoke(); then = null }
    fun withLan(go: () -> Unit) {
        val p = "android.permission.ACCESS_LOCAL_NETWORK"
        if (android.os.Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED) go()
        else { then = go; lan.launch(p) }
    }
    Text(tr("Another device on this Wi-Fi"), color = Colors.accent)
    Text(tr("Copy episodes, dictionaries, models and keys from one kumapie to another, without the PC."), color = Colors.dim, fontSize = 14.sp)
    if (sending != null) {
        val code = sending.progress.getString("code")
        if (code != null) Text(tr("Sending. On the other device: Receive, then code %1\$s (address %2\$s).", code,
            sending.progress.getString("address") ?: "?"), fontSize = 15.sp)
        OutlinedButton(onClick = { work.cancelUniqueWork(Transfer.SEND) }) { Text(tr("Stop sending")) }
    } else {
        OutlinedButton(onClick = { withLan { Transfer.send(context, (1000..9999).random().toString()) } }) { Text(tr("Send to another device")) }
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
            OutlinedButton(onClick = { withLan { dialog = true } }) { Text(tr("Receive from another device")) }
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

/** The player's word popup: at most this wide and tall (dp). */
var Settings.popupWidth: Int
    get() = prefs.getInt("popup_width", 460)
    set(value) = prefs.edit().putInt("popup_width", value).apply()
var Settings.popupHeight: Int
    get() = prefs.getInt("popup_height", 650)
    set(value) = prefs.edit().putInt("popup_height", value).apply()

@Composable
private fun PopupSize(settings: Settings) {
    var w by remember { mutableStateOf(settings.popupWidth.toString()) }
    var h by remember { mutableStateOf(settings.popupHeight.toString()) }
    Text(tr("Word popup"), color = Colors.accent)
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Max width"), fontSize = 14.sp)
        OutlinedTextField(w, { v -> w = v.filter(Char::isDigit).take(4); w.toIntOrNull()?.takeIf { it >= 240 }?.let { settings.popupWidth = it } },
            singleLine = true, isError = (w.toIntOrNull() ?: 0) < 240, modifier = Modifier.width(80.dp))
        Text(tr("height"), fontSize = 14.sp)
        OutlinedTextField(h, { v -> h = v.filter(Char::isDigit).take(4); h.toIntOrNull()?.takeIf { it >= 300 }?.let { settings.popupHeight = it } },
            singleLine = true, isError = (h.toIntOrNull() ?: 0) < 300, modifier = Modifier.width(80.dp))
        Text("dp", fontSize = 14.sp)
    }
    if ((w.toIntOrNull() ?: 0) < 240 || (h.toIntOrNull() ?: 0) < 300)
        Text(tr("At least 240 wide and 300 high; until then the popup keeps %1\$d × %2\$d.", settings.popupWidth, settings.popupHeight),
            fontSize = 13.sp, color = Colors.unknown)
}

/** The services whose keys Settings checks, with an endpoint that needs the key and answers 401/403 to a wrong one. */
private enum class KeyService(val label: String, val url: String, val bearer: Boolean, val where: String) {
    SONIOX("Soniox", "https://api.soniox.com/v1/files?limit=1", true, "soniox.com > API keys"),
    REAL_DEBRID("Real-Debrid", "https://api.real-debrid.com/rest/1.0/user", true, "real-debrid.com/apitoken"),
    JIMAKU("Jimaku", "https://jimaku.cc/api/entries/search?query=a", false, "jimaku.cc > Account"),
}

/** "ok", "rejected", "offline" or "http <code>": the key tried once against [service]. */
private suspend fun checkKey(service: KeyService, key: String): String = withContext(Dispatchers.IO) {
    runCatching {
        val c = java.net.URL(service.url).openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("Authorization", if (service.bearer) "Bearer $key" else key)
        c.setRequestProperty("User-Agent", "kumapie")
        c.connectTimeout = 8000; c.readTimeout = 8000
        try { c.responseCode } finally { c.disconnect() }
    }.fold({ code -> when (code) { in 200..299 -> "ok"; 401, 403 -> "rejected"; else -> "http $code" } }, { "offline" })
}

/**
 * A key or token: saved as typed (spaces and line breaks from a paste trimmed), hidden unless shown, and checked
 * against its service when the field is left, so a wrong key shows up here and not as a failed episode later.
 */
@Composable
private fun KeyField(value: String, onChange: (String) -> Unit, label: String, service: KeyService) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) } // null: not checked; "checking"; checkKey's answers
    var checked by remember { mutableStateOf<String?>(null) } // the key the result is for
    fun check() {
        if (value.isBlank() || value == checked) return
        checked = value; result = "checking"
        scope.launch { result = checkKey(service, value) }
    }
    OutlinedTextField(value, { v -> onChange(v.trim()); if (v.trim() != checked) result = null },
        label = { Text(label) }, singleLine = true, isError = result == "rejected",
        visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
        trailingIcon = { IconButton(onClick = { shown = !shown }) {
            Icon(if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (shown) tr("Hide") else tr("Show"))
        } },
        supportingText = result?.let { r -> { Text(when {
            r == "checking" -> tr("Checking with %s…", service.label)
            r == "ok" -> tr("✓ %s accepted it.", service.label)
            r == "rejected" -> tr("%1\$s didn't accept this key. Copy it again from %2\$s.", service.label, service.where)
            r == "offline" -> tr("Couldn't reach %s to check it. It's saved and will be tried when you make an episode.", service.label)
            else -> tr("%1\$s answered %2\$s. It's saved; try again later.", service.label, r.removePrefix("http "))
        }, color = if (r == "rejected") Colors.unknown else Colors.dim) } },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) check() })
}

/** An hour of the day: free to clear and retype; saved only when it is a valid hour, else says so. */
@Composable
private fun HourField(value: Int, range: IntRange, onValid: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    val bad = text.toIntOrNull()?.let { it !in range } ?: true
    OutlinedTextField(text, { v -> text = v.filter(Char::isDigit).take(2); text.toIntOrNull()?.takeIf { it in range }?.let(onValid) },
        singleLine = true, isError = bad, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(70.dp))
}
