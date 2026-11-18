package io.github.pedrubik2000.kumapie.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.JapaneseModel
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries
import io.github.pedrubik2000.kumapie.mobile.local.jimakuKey
import io.github.pedrubik2000.kumapie.mobile.local.rdToken
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch

/** Set once the onboarding was finished (or skipped through): it doesn't come back. */
const val ONBOARDED = "onboarded"

/**
 * The first run without a PC (kumapie_plan.md "Onboarding"): languages, the Jimaku key (Japanese subtitles), the
 * sources (Torrentio with the user's Real-Debrid token, Kitsu's titles, their AniList lists), and the downloads
 * (dictionaries, Sudachi). Every step can be skipped; Settings has all of it later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(library: Library, onDone: () -> Unit) {
    val settings = library.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var jimaku by remember { mutableStateOf(settings.jimakuKey) }
    var rd by remember { mutableStateOf(settings.rdToken) }
    var kitsu by remember { mutableStateOf(true) }
    var english by remember { mutableStateOf(true) }
    var anilist by remember { mutableStateOf(settings.prefs.getString(Addons.ANILIST, "").orEmpty()) }
    var anilistSaid by remember { mutableStateOf<String?>(null) }
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    fun saveSources() {
        settings.addons = (settings.addons + listOfNotNull(Addons.KITSU.takeIf { kitsu }, Addons.OPENSUBTITLES.takeIf { english }, rd.takeIf { it.isNotBlank() }?.let(Addons::torrentio))).distinct()
        settings.prefs.edit().putString(Addons.ANILIST, anilist.trim()).apply()
    }
    val steps = 4
    Scaffold(topBar = { TopAppBar(title = { Text(tr("Set up kumapie")) }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(tr("Step %1\$d of %2\$d", step + 1, steps), color = Colors.dim, fontSize = 14.sp)
            when (step) {
                0 -> {
                    Text(tr("Languages"), fontSize = 22.sp)
                    MenuLanguage(settings)
                }
                1 -> {
                    Text(tr("Japanese subtitles"), fontSize = 22.sp)
                    Text(tr("kumapie shows each line in Japanese while you watch. They come from Jimaku, a free site of anime subtitles: make an account there, then copy your API key from Account."))
                    OutlinedButton(onClick = { open("https://jimaku.cc/login") }) { Text(tr("Open jimaku.cc")) }
                    KeyField(jimaku, { jimaku = it; settings.jimakuKey = it }, tr("Jimaku API key"), KeyService.JIMAKU)
                }
                2 -> {
                    Text(tr("What to watch"), fontSize = 22.sp)
                    Text(tr("Videos: Torrentio finds an episode's files and Real-Debrid streams them (a paid account). Copy your token from real-debrid.com/apitoken."))
                    OutlinedButton(onClick = { open("https://real-debrid.com/apitoken") }) { Text(tr("Open real-debrid.com")) }
                    KeyField(rd, { rd = it; settings.rdToken = it }, tr("Real-Debrid token"), KeyService.REAL_DEBRID)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(kitsu, { kitsu = it })
                        Text(tr("Kitsu: anime titles, posters and episodes to pick from"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(english, { english = it })
                        Text(tr("English under the lines: OpenSubtitles (free, no account)"))
                    }
                    Text(tr("Your AniList lists (optional): what you're watching and plan to watch, at the top of Discover. Your profile must be public."), color = Colors.dim, fontSize = 14.sp)
                    OutlinedTextField(anilist, { anilist = it; anilistSaid = null }, label = { Text(tr("AniList username")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth(),
                        supportingText = anilistSaid?.let { { Text(it) } })
                    if (anilist.isNotBlank()) OutlinedButton(onClick = {
                        anilistSaid = tr("Checking with %s…", "AniList")
                        scope.launch {
                            anilistSaid = runCatching { AniList.rows(anilist.trim(), settings.prefs) }
                                .fold({ r -> tr("✓ %d titles in your lists", r.sumOf { it.second.size }) }, { it.message ?: it.toString() })
                        }
                    }) { Text(tr("Check")) }
                }
                else -> Downloads(library)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) OutlinedButton(onClick = { step-- }) { Text(tr("Back")) }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    if (step == 2) saveSources()
                    if (step < steps - 1) step++ else { settings.prefs.edit().putBoolean(ONBOARDED, true).apply(); onDone() }
                }) { Text(if (step < steps - 1) tr("Next") else tr("Start watching")) }
            }
        }
    }
}

/** The last step: the dictionaries (meanings) and Sudachi (splits lines into words) start now, each with its state. */
@Composable
private fun Downloads(library: Library) {
    val context = LocalContext.current
    Text(tr("Downloads"), fontSize = 22.sp)
    Text(tr("Two downloads, once: the Japanese dictionaries (meanings, about 27 MB) and the word splitter (about 80 MB). You can start watching while they finish."))
    val dicts = remember { YomitanDictionaries.get(context.applicationContext) }
    var dictSaid by remember { mutableStateOf(if (dicts.of(Lang.JAPANESE).isNotEmpty()) tr("✓ ready") else tr("Starting…")) }
    LaunchedEffect(Unit) {
        if (dicts.of(Lang.JAPANESE).isEmpty()) {
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
}
