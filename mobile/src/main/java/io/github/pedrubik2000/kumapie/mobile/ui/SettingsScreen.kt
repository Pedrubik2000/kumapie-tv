package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import io.github.pedrubik2000.kumapie.mobile.BuildConfig
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.mobile.unlock.unlockScenes
import io.github.pedrubik2000.kumapie.mobile.local.sonioxKey
import io.github.pedrubik2000.kumapie.mobile.local.englishSource
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
            title = { Text(if (firstRun) "Welcome to kumapie" else "Settings") },
            navigationIcon = { if (!firstRun) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Server", color = Colors.accent)
            OutlinedTextField(server, { server = it }, label = { Text("https://my-pc.my-tailnet.ts.net:8445") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val url = Settings.normalizeServer(server)
                message = "Checking…"
                scope.launch {
                    runCatching { Api(url).info() }
                        .onSuccess {
                            settings.server = url
                            server = url
                            message = "Connected: ${it.episodes} episodes in ${it.language}."
                            onSaved()
                        }
                        .onFailure { message = "No answer from $url: ${it.message}" }
                }
            }) { Text("Save") }
            if (message.isNotEmpty()) Text(message, color = Colors.dim, fontSize = 14.sp)

            if (!firstRun) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                KnownWordsSection(library)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                DictionarySection(library)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NewEpisodesSection(library)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                UnlockSection(library)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("On this device", color = Colors.accent)
                Text("Downloads: ${"%.1f".format(used / 1e9)} GB", fontSize = 15.sp)
                OutlinedButton(onClick = { library.downloads.deleteAll(); refresh++ }) { Text("Remove all downloads") }
                Text(if (waiting == 0) "Nothing waiting to be sent to the PC." else "$waiting reports waiting for the PC (sent when it answers).",
                    fontSize = 15.sp, color = Colors.dim)
                if (waiting > 0) OutlinedButton(onClick = {
                    scope.launch { library.pending.flush(library.api, force = true); refresh++ }
                }) { Text("Send now") }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = {
                    message = "Looking for updates…"
                    scope.launch {
                        runCatching { Updater.newer() }
                            .onSuccess { r -> if (r == null) message = "This is the newest version." else { message = ""; onUpdate(r) } }
                            .onFailure { message = "Couldn't check: ${it.message}" }
                    }
                }) { Text("Check for updates") }
            }
            Text("Version ${BuildConfig.VERSION_NAME}", color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
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

    Text("Word colours from Anki", color = Colors.accent)
    Text(status, fontSize = 15.sp)

    // 1. The German model (spaCy, the same as morphs on the PC).
    when {
        modelReady -> Text("German model: ready (de_core_news_lg).", fontSize = 14.sp, color = Colors.dim)
        modelState != null -> Text("German model: $modelState", fontSize = 14.sp, color = Colors.dim)
        else -> {
            Text("The German model (about 550 MB, once) finds each word's form like morphs on the PC.", fontSize = 14.sp, color = Colors.dim)
            OutlinedButton(onClick = { known.model.download() }) { Text("Download the German model") }
        }
    }

    // 2. Reading AnkiDroid.
    when {
        app == null -> Text("No kuma3 Anki or AnkiDroid on this device.", fontSize = 14.sp, color = Colors.dim)
        !allowed -> OutlinedButton(onClick = { ask.launch(known.permission(app)) }) { Text("Allow reading Anki") }
        else -> Text("Anki: " + when (app) {
            "io.github.pedrubik2000.kuma3" -> "kuma3 Anki"
            "com.ichi2.anki.debug" -> "kuma3 test build"
            else -> app
        } + ".", fontSize = 14.sp, color = Colors.dim)
    }

    // 3. Known from this stability (days) on.
    OutlinedTextField(days, { v ->
        days = v
        v.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { known.threshold = it }
    }, label = { Text("Known from stability (days)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())

    if (modelReady && allowed) OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch { known.refresh(); busy = false }
    }) { Text(if (busy) "Reading Anki…" else "Read Anki now") }
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

    Text("Dictionary and word audio", color = Colors.accent)
    when {
        state != null -> Text("Dictionary: $state", fontSize = 15.sp)
        built != null -> Text("Dictionary: offline, built $built. Words it lacks are looked up on Wiktionary and saved.", fontSize = 15.sp)
        else -> Text("Meanings offline: the German-English dictionary (about 25 MB download, 85 MB on the device).", fontSize = 15.sp)
    }
    if (state == null) OutlinedButton(onClick = { dictionary.download() }) {
        Text(if (built == null) "Download the dictionary" else "Update the dictionary")
    }
    if (voice.isNotEmpty()) Text(voice, fontSize = 14.sp, color = Colors.dim)
    OutlinedButton(onClick = {
        runCatching { context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) }
    }) { Text("Speech settings") }
    Text("Words are read by a person's recording when Wikimedia Commons has one, else by this voice.",
        fontSize = 13.sp, color = Colors.dim)
    Text(io.github.pedrubik2000.kumapie.mobile.german.Dictionary.ATTRIBUTION, fontSize = 12.sp, color = Colors.dim)
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

    Text("Unlock", color = Colors.accent)
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Show an i+1 scene every time I unlock", fontSize = 15.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = on, onCheckedChange = {
            on = it
            library.settings.unlockScenes = it
            io.github.pedrubik2000.kumapie.mobile.unlock.UnlockService.sync(context)
        })
    }
    if (on && !overlay) {
        Text("Allow \"Display over other apps\" so the scene can open when you unlock.", fontSize = 14.sp, color = Colors.dim)
        OutlinedButton(onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + context.packageName)))
        }) { Text("Allow display over other apps") }
    }
    if (on) Text(if (pool > 0) "$pool i+1 scenes to pick from (refreshed when kumapie opens)."
        else "Reading the episodes for i+1 scenes…", fontSize = 13.sp, color = Colors.dim)
    Text("If the separate Unlock Cards app is still on, turn it off so only one opens.", fontSize = 13.sp, color = Colors.dim)
}

/** New episodes made on the tablet: the Soniox key (kept only in kumapie's private settings) and the English source. */
@Composable
private fun NewEpisodesSection(library: Library) {
    var key by remember { mutableStateOf(library.settings.sonioxKey) }
    var english by remember { mutableStateOf(library.settings.englishSource) }
    Text("New episodes (YouTube)", color = Colors.accent)
    OutlinedTextField(key, { key = it; library.settings.sonioxKey = it }, label = { Text("Soniox API key") }, singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Text("English subtitles", fontSize = 15.sp)
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilterChip(selected = english == "device", label = { Text("On the device (free)") },
            onClick = { english = "device"; library.settings.englishSource = "device" })
        androidx.compose.material3.FilterChip(selected = english == "soniox", label = { Text("Soniox (better, no extra cost)") },
            onClick = { english = "soniox"; library.settings.englishSource = "soniox" })
    }
    Text("Add episodes with + on the home screen, or share a YouTube link to kumapie.", fontSize = 13.sp, color = Colors.dim)
}
