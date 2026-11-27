package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch

/**
 * Word colours from the device's own Anki: permission to read AnkiDroid (a card reviewed once makes a word known)
 * (shared by every language), then each language this device learns ([LanguageWordsSection]).
 */
@Composable
internal fun KnownWordsSection(library: Library, learning: Set<String>) {
    val known = library.languages.japanese.known // the permission is the same for every language
    val app = remember { known.ankiApp() }
    var allowed by remember { mutableStateOf(app != null && known.hasPermission(app)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
    }

    Text(tr("Word colours from Anki"), color = Colors.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
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

    Text(tr("${language.lang.name} words"), color = Colors.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    // The total Home shows first; the card count under it says where part of it comes from.
    val total = remember(status) { known.knownCount() }
    if (known.ready) Text(tr("%d words known (as on Home: cards, words marked known and imported lists).", total), fontSize = 15.sp)
    Text(tr("From your cards: %s", status), fontSize = 14.sp, color = Colors.dim)
    when {
        state != null -> Text("${tr(model.label)}: $state", fontSize = 14.sp, color = Colors.dim)
        ready -> {
            Text(tr("%1\$s: ready (%2\$s).", tr(model.label), model.name), fontSize = 14.sp, color = Colors.dim)
            // Jiten's word splitter (item 26b): two more files, from the PC or the public release.
            if (model is io.github.pedrubik2000.kumapie.lang.JapaneseModel && !model.splitterReady)
                OutlinedButton(onClick = { model.download() }) {
                    val noPc = io.github.pedrubik2000.kumapie.data.Settings(androidx.compose.ui.platform.LocalContext.current).server.isEmpty()
                    Text(if (noPc) tr("Download Jiten's word splitter") else tr("Download Jiten's word splitter from the PC"))
                }
        }
        else -> {
            if (model.about.isNotEmpty()) Text(tr(model.about), fontSize = 14.sp, color = Colors.dim)
            OutlinedButton(onClick = { model.download() }) { Text(tr(model.downloadText)) }
        }
    }
    if (ready && allowed) OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch { known.refresh(force = true); busy = false }
    }) { Text(if (busy) tr("Reading Anki…") else tr("Read ${language.lang.name} cards now")) }

}
