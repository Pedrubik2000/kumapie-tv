package io.github.pedrubik2000.kumapie.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.ColourSources
import io.github.pedrubik2000.kumapie.ui.Colors

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColourAnkiSettings(sources: ColourSources, settings: io.github.pedrubik2000.kumapie.data.Settings, onAllow: () -> Unit) {
    val context = LocalContext.current
    if (!sources.permitted) {
        Text(tr("Reading Anki is off: allow it to use this source."), color = Colors.text, fontSize = 14.sp)
        OutlinedButton(onClick = onAllow) { Text(tr("Allow reading Anki")) }
        OutlinedButton(onClick = { context.startActivity(settingsIntent(context)) }) { Text(tr("Open kumapie's settings in Android")) }
        return
    }
    sources.listing.problem?.let {
        Text(it, color = Colors.text, fontSize = 14.sp)
        OutlinedButton(onClick = sources::retry) { Text(tr("Retry")) }
    }
    var type by remember { mutableStateOf("") }
    val types = sources.listing.types
    sources.languages.forEach { language ->
        val lang = language.lang
        var picked by remember(lang, sources.choice) { mutableStateOf(settings.ankiDecks(lang)) }
        var word by remember(lang, sources.choice) { mutableStateOf(settings.ankiWordField(lang)) }
        var reading by remember(lang, sources.choice) { mutableStateOf(settings.ankiReadingField(lang)) }
        val fields = (types.firstOrNull { it.name == type }?.let { listOf(it) } ?: types).flatMap { it.fields }.distinct()
        Text(tr("%s: decks", tr(lang.name)), color = Colors.text, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (sources.listing.decks + picked).distinct().forEach { d ->
                FilterChip(selected = d in picked, label = { Text(d) }, onClick = {
                    picked = (if (d in picked) picked - d else picked + d).ifEmpty { listOf(lang.deck) }
                    settings.setAnkiDecks(lang, picked)
                    sources.reread()
                })
            }
        }
        Text(tr("Note type (to list its fields)"), color = Colors.text, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type.isEmpty(), onClick = { type = "" }, label = { Text(tr("All")) })
            types.forEach { t -> FilterChip(selected = type == t.name, onClick = { type = t.name }, label = { Text(t.name) }) }
        }
        Text(tr("Word field"), color = Colors.text, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (fields + word).distinct().forEach { f ->
                FilterChip(selected = f == word, label = { Text(f) }, onClick = { word = f; settings.setAnkiWordField(lang, f); sources.reread() })
            }
        }
        if (lang == Lang.JAPANESE) {
            Text(tr("Reading field"), color = Colors.text, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (fields + reading).distinct().forEach { f ->
                    FilterChip(selected = f == reading, label = { Text(f) }, onClick = { reading = f; settings.setAnkiReadingField(lang, f); sources.reread() })
                }
            }
        }
        val read by language.known.ankiState.collectAsState()
        Text(read.error?.let { tr("Anki was not read: %s", it) } ?: tr("%d words known from your Anki.", read.count), color = Colors.dim, fontSize = 14.sp)
    }
}

internal fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

internal fun settingsIntent(context: Context) = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
