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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.BuildConfig
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.update.Updater
import kotlinx.coroutines.launch

/**
 * The server address and updates. Also the first-run screen (firstRun = true): the address can be typed here,
 * or sent from the PC: adb shell am start -n <app id>/io.github.pedrubik2000.kumapie.MainActivity --es server <url>
 */
@Composable
fun SettingsScreen(settings: Settings, firstRun: Boolean, onSaved: () -> Unit, onUpdate: (Updater.Release) -> Unit,
                   onButtons: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(settings.server) }
    var status by remember { mutableStateOf("") }
    var updateStatus by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

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

    // Scrolls: the D-pad brings the focused button into view (Font and Updates were below the screen).
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 64.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title(if (firstRun) tr("Welcome to kumapie") else tr("Settings"), fontSize = 32.sp)
        if (firstRun) {
            Text(tr("Type the address of the dojo server on the PC (Tailscale), e.g. https://my-pc.tailnet.ts.net:8445"),
                color = Colors.dim)
        }
        Heading(tr("Server"))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvTextField(server, { server = it }, onDone = ::save,
                modifier = Modifier.width(640.dp).let { if (firstRun) it.focusRequester(focus) else it })
            Button(onClick = ::save) { Text(tr("Save")) }
        }
        if (status.isNotEmpty()) Text(status, color = Colors.dim)
        if (firstRun) {
            // No PC: the app alone (kumapie_plan.md "for other people"). A server can still be added later, here.
            Text(tr("No server? kumapie also works on its own."), color = Colors.dim, modifier = Modifier.padding(top = 24.dp))
            Button(onClick = { settings.standalone = true; onSaved() }) { Text(tr("Use without a server")) }
        }

        if (!firstRun && settings.server.isEmpty()) {
            // Without a PC: the user's own Stremio addons (kumapie has none of its own) and their Jimaku key.
            Heading(tr("Stremio addons"), Modifier.padding(top = 24.dp))
            var addons by remember { mutableStateOf(settings.addons) }
            var names by remember { mutableStateOf<List<String>>(emptyList()) }
            var adding by remember { mutableStateOf("") }
            LaunchedEffect(addons) { names = runCatching { io.github.pedrubik2000.kumapie.data.Addons(addons, settings.prefs).names() }.getOrDefault(emptyList()) }
            Text(tr("Paste an addon's address (…/manifest.json). One that lists titles (Cinemeta, Kitsu) and one that gives links: a torrent addon needs your debrid key in it. A subtitle addon (OpenSubtitles v3) adds English under the lines."),
                color = Colors.dim)
            addons.forEachIndexed { i, a ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(names.getOrNull(i).orEmpty().ifEmpty { tr("(no answer)") } + " · " + java.net.URI(io.github.pedrubik2000.kumapie.data.Addons.base(a)).host.orEmpty(),
                        color = Colors.text, modifier = Modifier.width(640.dp))
                    Button(onClick = { addons = addons - a; settings.addons = addons }) { Text(tr("Remove")) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                fun add() { if (adding.isNotBlank()) { addons = (addons + adding.trim()).distinct(); settings.addons = addons; adding = "" } }
                TvTextField(adding, { adding = it }, onDone = ::add, modifier = Modifier.width(640.dp))
                Button(onClick = ::add) { Text(tr("Add")) }
            }
            Heading(tr("Jimaku"), Modifier.padding(top = 24.dp))
            Text(tr("Japanese subtitles for a stream that has none of its own: your free API key from jimaku.cc."), color = Colors.dim)
            var key by remember { mutableStateOf(settings.prefs.getString("jimaku_key", "").orEmpty()) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                fun keep() = settings.prefs.edit().putString("jimaku_key", key.trim()).apply()
                TvTextField(key, { key = it }, onDone = ::keep, modifier = Modifier.width(640.dp))
                Button(onClick = ::keep) { Text(tr("Save")) }
            }
        }

        if (!firstRun) {
            Heading(tr("Buttons and help"), Modifier.padding(top = 24.dp))
            var showHelp by remember { mutableStateOf(settings.showHelp) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Focus starts here, not in the server field: focusing a text field opens the TV's keyboard.
                Button(onClick = onButtons, modifier = Modifier.focusRequester(focus)) { Text(tr("Change buttons")) }
                Button(onClick = { showHelp = !showHelp; settings.showHelp = showHelp }) {
                    Text(tr("Help screen and key hints: %s", if (showHelp) tr("on") else tr("off")))
                }
            }
            Heading(tr("No spoilers"), Modifier.padding(top = 24.dp))
            var blur by remember { mutableStateOf(settings.blurUnwatched) }
            Button(onClick = { blur = !blur; settings.blurUnwatched = blur }) {
                Text(tr("Blur episodes I haven't watched: %s", if (blur) tr("on") else tr("off")))
            }
            // OK steps through the fonts; the sample line shows the one picked (fetched the first time it's drawn).
            Heading(tr("Japanese subtitles"), Modifier.padding(top = 24.dp))
            var font by remember { mutableStateOf(settings.japaneseFont) }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    font = JapaneseFonts.NAMES[(JapaneseFonts.NAMES.indexOf(font) + 1) % JapaneseFonts.NAMES.size]
                    settings.japaneseFont = font
                }) { Text(tr("Font: %s", JapaneseFonts.label(font))) }
                Text(JapaneseFonts.SAMPLE, fontSize = 26.sp, color = Colors.text, fontFamily = JapaneseFonts.family(font))
            }
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
            // Ideas and bugs: GitHub issues (no browser on a TV: the phone opens it from the QR code).
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
        }
    }
    // Focus on Change buttons scrolled the title and Server off the top: back to the top once it has landed.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; kotlinx.coroutines.delay(100); scroll.scrollTo(0) }
}

/**
 * Home without a PC server (kumapie_plan.md "for other people"): Discover (the user's Stremio addons), the titles kept
 * on Home, Settings. Without an addon it says where to add one.
 */
@Composable
fun AloneHome(settings: Settings, onDiscover: (io.github.pedrubik2000.kumapie.data.Found?) -> Unit, onSettings: () -> Unit,
              onReview: (String) -> Unit = {}) {
    val focus = remember { FocusRequester() }
    val addons = remember { settings.addons }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Review: this TV's own cards (Japanese at launch), "· N" when some are due or new.
    val lang = "ja"
    var due by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { due = runCatching { io.github.pedrubik2000.kumapie.lang.ownReview(context, lang).due().size }.getOrNull() }
    var kept by remember { mutableStateOf<List<io.github.pedrubik2000.kumapie.data.Found>>(emptyList()) }
    LaunchedEffect(Unit) { kept = runCatching { io.github.pedrubik2000.kumapie.data.Addons(addons, settings.prefs).saved() }.getOrDefault(emptyList()) }
    // As the PC's Home: the logo and the buttons on top, then the titles as posters (newest kept first).
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Title("kuma", "pie")
            Spacer(Modifier.width(12.dp))
            if (addons.isNotEmpty()) Button(padding = TAB, onClick = { onDiscover(null) }, modifier = Modifier.focusRequester(focus)) { Text(tr("Discover")) }
            due?.let { n -> Button(padding = TAB, onClick = { onReview(lang) }, colors = arcadeButtonColors(selected = n > 0)) { Text(tr("Review") + if (n > 0) " · $n" else "") } }
            Button(padding = TAB, onClick = onSettings, modifier = if (addons.isEmpty()) Modifier.focusRequester(focus) else Modifier) { Text(tr("Settings")) }
        }
        when {
            addons.isEmpty() -> Column(Modifier.padding(start = 16.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Nothing to watch yet."), color = Colors.text, fontSize = 22.sp)
                Text(tr("kumapie plays what your own Stremio addons give it. Add one in Settings, or the address of a kumapie server on a PC."), color = Colors.dim)
            }
            kept.isEmpty() -> Text(tr("Find something to watch in Discover: what you play shows up here."), color = Colors.dim,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp))
            else -> Shelf(tr("My shows")) {
                items(kept.reversed().size) { i -> kept.reversed()[i].let { f -> Poster(f.poster, f.name, f.year, null, Modifier) { onDiscover(f) } } }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Where people report bugs and ideas (GitHub issues of the public repo). */
const val ISSUES = "https://github.com/Pedrubik2000/kumapie-tv/issues"
