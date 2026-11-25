package io.github.pedrubik2000.kumapie.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.KeyService
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Startup
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.JapaneseModel
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch

@Composable
internal fun PageHead(caption: String, title: String, text: String) {
    ColourCaption(caption)
    Text(title, color = Colors.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    if (text.isNotEmpty()) Text(text, color = Glass.dim, fontSize = 15.sp)
}

@Composable
private fun CopiedCard(title: String, line: String) =
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text(title, color = Colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(line, color = Glass.dim, fontSize = 14.sp)
    }

@Composable
internal fun LanguagePage(settings: Settings, onContinue: () -> Unit) {
    PageHead("", tr("Language"), "")
    MenuLanguage(settings)
    Pill(tr("Continue"), onContinue, primary = true)
}

@Composable
internal fun AniListPage(settings: Settings, caption: String, copiedFrom: String?, onContinue: () -> Unit) {
    val scope = rememberCoroutineScope()
    var anilist by remember { mutableStateOf(settings.anilist) }
    var said by remember { mutableStateOf<String?>(null) }
    PageHead(caption, tr("AniList"), tr("Home, Library and Discover come from your AniList anime list."))
    if (copiedFrom != null) CopiedCard(settings.anilist, tr("%s · copied from %s", tr("AniList"), copiedFrom))
    else {
        OutlinedTextField(anilist, { anilist = it; said = null }, label = { Text(tr("AniList username")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth(), supportingText = said?.let { { Text(it) } })
        if (anilist.isNotBlank()) OutlinedButton(onClick = {
            said = tr("Checking with %s…", "AniList")
            scope.launch {
                said = runCatching { AniList.rows(anilist.trim(), settings.prefs) }
                    .fold({ r -> tr("✓ %d titles in your lists", r.sumOf { it.second.size }) }, { it.message ?: it.toString() })
            }
        }) { Text(tr("Check")) }
        AniListLogin(settings)
    }
    Pill(tr("Continue"), {
        if (copiedFrom == null && (anilist.isNotBlank() || settings.anilistToken.isEmpty())) settings.anilist = anilist
        onContinue()
    }, primary = true)
}

@Composable
internal fun VideosPage(settings: Settings, caption: String, copiedFrom: String?, onContinue: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var jimaku by remember { mutableStateOf(settings.jimakuKey) }
    var rd by remember { mutableStateOf(settings.rdToken) }
    var kitsu by remember { mutableStateOf(true) }
    var english by remember { mutableStateOf(true) }
    var said by remember { mutableStateOf("") }
    var failed by remember { mutableIntStateOf(0) }
    val checking = tr("Checking with %s…", "Real-Debrid")
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    PageHead(caption, tr("Videos & subtitles"), tr("Real-Debrid (paid) streams the files Torrentio finds; Jimaku brings the Japanese subtitles."))
    if (copiedFrom != null) CopiedCard(tr("Real-Debrid + Jimaku keys"), tr("%s · copied from %s", tr("Videos"), copiedFrom))
    else {
        OutlinedButton(onClick = { open("https://real-debrid.com/apitoken") }) { Text(tr("Open real-debrid.com")) }
        KeyField(rd, { rd = it; settings.rdToken = it }, tr("Real-Debrid token"), KeyService.REAL_DEBRID)
        OutlinedButton(onClick = { open("https://jimaku.cc/login") }) { Text(tr("Open jimaku.cc")) }
        KeyField(jimaku, { jimaku = it; settings.jimakuKey = it }, tr("Jimaku API key"), KeyService.JIMAKU)
        if (said.isNotEmpty()) Text(said, color = if (said == checking) Glass.dim else Colors.unknown)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(kitsu, { kitsu = it })
            Text(tr("Kitsu: anime titles, posters and episodes to pick from"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(english, { english = it })
            Text(tr("English under the lines: OpenSubtitles (free, no account)"))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill(tr("Continue"), {
            if (copiedFrom != null) onContinue()
            else if (said != checking) scope.launch {
                settings.setSources(kitsu, english, rd)
                said = checking
                val g = Startup.rdCheck(settings, failed)
                if (g == io.github.pedrubik2000.kumapie.data.RdGate.UNREACHABLE) failed++
                said = Startup.rdSay(g)
                if (g.mayFinish) onContinue()
            }
        }, primary = true)
        if (copiedFrom == null) Text(tr("Skip: nothing plays until both keys are in"), color = Glass.dim, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
            modifier = Modifier.padding(start = 14.dp).sizeIn(minHeight = 48.dp).clickable { settings.setSources(kitsu, english, rd); onContinue() }.padding(vertical = 14.dp))
    }
}

@Composable
internal fun DownloadsPage(library: Library, caption: String, onFinish: () -> Unit) {
    val context = LocalContext.current
    PageHead(caption, tr("Downloads"), tr("Two downloads, once: the Japanese dictionaries (meanings, about 33 MB) and the word splitter (about 80 MB). You can start watching while they finish."))
    val dicts = remember { YomitanDictionaries.get(context.applicationContext) }
    val noEngine = remember { io.github.pedrubik2000.kumapie.lang.dictionaryEngineError() }
    var dictSaid by remember { mutableStateOf(noEngine?.let { tr("Dictionaries can't run on this device: %s", it) }
        ?: if (dicts.of(Lang.JAPANESE).isNotEmpty()) tr("✓ ready") else tr("Starting…")) }
    LaunchedEffect(Unit) {
        if (noEngine == null && dicts.of(Lang.JAPANESE).isEmpty()) {
            runCatching { dicts.installRecommended(Lang.JAPANESE) { dictSaid = it } }
                .onSuccess { dictSaid = if (dicts.of(Lang.JAPANESE).isNotEmpty()) tr("✓ ready") else it.joinToString("\n") }
                .onFailure { dictSaid = it.message ?: it.toString() }
        }
    }
    val model = remember { JapaneseModel(context.applicationContext) }
    LaunchedEffect(Unit) { if (!model.isReady) model.download() }
    val modelState by remember { model.state() }.collectAsState(initial = null)
    Text(tr("Dictionaries: %s", dictSaid))
    Text(tr("Word splitter: %s", modelState ?: if (model.isReady) tr("✓ ready") else tr("Starting…")))
    Pill(tr("Start watching"), onFinish, primary = true)
}
