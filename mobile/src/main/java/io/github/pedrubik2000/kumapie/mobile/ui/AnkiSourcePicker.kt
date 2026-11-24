package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.AnkiSource
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.AnkiLookups
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnkiSourcePicker(library: Library, learning: Set<String>) {
    val settings = library.settings
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lookups = remember { AnkiLookups(appContext) }
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf(settings.ankiSource) }
    var apps by remember { mutableStateOf(emptyList<String>()) }
    var said by remember { mutableStateOf("") }
    var pending by rememberSaveable { mutableStateOf(AnkiSource.NONE) }
    var blocked by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var version by remember { mutableStateOf(0) }
    val known = library.languages.german.known
    val app = AnkiSource.appFor(choice, apps)
    var listing by remember { mutableStateOf(AnkiLookups.Listing()) }
    val decks = listing.decks
    val types = listing.types
    var type by remember { mutableStateOf("") }

    fun apply(c: String) {
        settings.ankiSource = c
        choice = c
        version++
        said = ""
        scope.launch { library.languages.all.forEach { it.known.readAnkiSource() } }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) apply(pending) else {
            said = tr("Reading that Anki is off: you did not allow it. The source stays as it was.")
            val target = AnkiSource.appFor(pending, apps)
            blocked = target != null && context.activity()?.shouldShowRequestPermissionRationale(known.permission(target)) == false
        }
    }
    fun choose(c: String) {
        val target = AnkiSource.appFor(c, apps)
        when {
            c == AnkiSource.NONE -> apply(c)
            target == null -> said = tr("That Anki is not on this device. The source stays as it was.")
            known.hasPermission(target) -> apply(c)
            else -> { pending = c; ask.launch(known.permission(target)) }
        }
    }
    LaunchedEffect(Unit) { apps = lookups.apps() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                version++
                scope.launch { library.languages.all.filter { it.lang.code in learning }.forEach { it.known.readAnkiSource() } }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(app, version, retry) { listing = if (app == null) AnkiLookups.Listing() else lookups.list(app) }

    Text(tr("Known words from Anki"), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(AnkiSource.NONE to tr("My kumapie cards only"), AnkiSource.ANY to tr("Anki on this device (kuma3 first)"),
            AnkiSource.KUMA3 to tr("kuma3 Anki"), AnkiSource.ANKIDROID to tr("AnkiDroid")).forEach { (c, label) ->
            FilterChip(selected = choice == c, onClick = { choose(c) }, label = { Text(label) })
        }
    }
    Text(tr("Choosing an Anki app opens it in the background to read its cards."), fontSize = 13.sp)
    if (said.isNotEmpty()) Text(said, fontSize = 14.sp)
    if (blocked) OutlinedButton(onClick = { context.startActivity(settingsIntent(context)) }) { Text(tr("Open kumapie's settings in Android")) }
    if (app == null) return
    if (!known.hasPermission(app)) {
        Text(tr("Reading Anki is off: allow it to use this source."), fontSize = 14.sp)
        OutlinedButton(onClick = { choose(choice) }) { Text(tr("Allow reading Anki")) }
        OutlinedButton(onClick = { context.startActivity(settingsIntent(context)) }) { Text(tr("Open kumapie's settings in Android")) }
        return
    }
    listing.problem?.let {
        Text(it, fontSize = 14.sp)
        OutlinedButton(onClick = { retry++ }) { Text(tr("Retry")) }
    }
    library.languages.all.filter { it.lang.code in learning }.forEach { language ->
        val lang = language.lang
        var picked by remember(lang, version) { mutableStateOf(settings.ankiDecks(lang)) }
        var word by remember(lang, version) { mutableStateOf(settings.ankiWordField(lang)) }
        var reading by remember(lang, version) { mutableStateOf(settings.ankiReadingField(lang)) }
        fun reread() = scope.launch { language.known.readAnkiSource() }
        val fields = (types.firstOrNull { it.name == type }?.let { listOf(it) } ?: types).flatMap { it.fields }.distinct()
        Text(tr("%s: decks", tr(lang.name)), fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (decks + picked).distinct().forEach { d ->
                FilterChip(selected = d in picked, label = { Text(d) }, onClick = {
                    picked = (if (d in picked) picked - d else picked + d).ifEmpty { listOf(lang.deck) }
                    settings.setAnkiDecks(lang, picked)
                    reread()
                })
            }
        }
        Text(tr("Note type (to list its fields)"), fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type.isEmpty(), onClick = { type = "" }, label = { Text(tr("All")) })
            types.forEach { t -> FilterChip(selected = type == t.name, onClick = { type = t.name }, label = { Text(t.name) }) }
        }
        Text(tr("Word field"), fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (fields + word).distinct().forEach { f ->
                FilterChip(selected = f == word, label = { Text(f) }, onClick = { word = f; settings.setAnkiWordField(lang, f); reread() })
            }
        }
        if (lang == Lang.JAPANESE) {
            Text(tr("Reading field"), fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (fields + reading).distinct().forEach { f ->
                    FilterChip(selected = f == reading, label = { Text(f) }, onClick = { reading = f; settings.setAnkiReadingField(lang, f); reread() })
                }
            }
        }
        val read by language.known.ankiState.collectAsState()
        Text(read.error?.let { tr("Anki was not read: %s", it) } ?: tr("%d words known from your Anki.", read.count), fontSize = 14.sp)
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun settingsIntent(context: Context) = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
