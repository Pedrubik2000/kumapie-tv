package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.ColourSources

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvAnkiSettings(sources: ColourSources, settings: Settings, onAllow: () -> Unit) {
    if (!sources.permitted) {
        Text(tr("Reading Anki is off: allow it to use this source."), color = Colors.text, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TvChip(tr("Allow reading Anki"), false) { onAllow() }
            TvOpenSettings()
        }
        return
    }
    sources.listing.problem?.let {
        Text(it, color = Colors.text, fontSize = 14.sp)
        TvChip(tr("Retry"), false) { sources.retry() }
    }
    var type by remember { mutableStateOf("") }
    val types = sources.listing.types
    val language = sources.languages.first()
    val lang = language.lang
    var picked by remember(sources.choice) { mutableStateOf(settings.ankiDecks(lang)) }
    var word by remember(sources.choice) { mutableStateOf(settings.ankiWordField(lang)) }
    var reading by remember(sources.choice) { mutableStateOf(settings.ankiReadingField(lang)) }
    val fields = (types.firstOrNull { it.name == type }?.let { listOf(it) } ?: types).flatMap { it.fields }.distinct()
    val gap = Arrangement.spacedBy(6.dp)
    Text(tr("%s: decks", tr(lang.name)), color = Colors.text, fontSize = 14.sp)
    FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
        (sources.listing.decks + picked).distinct().forEach { d ->
            TvChip(d, d in picked) {
                picked = (if (d in picked) picked - d else picked + d).ifEmpty { listOf(lang.deck) }
                settings.setAnkiDecks(lang, picked)
                sources.reread()
            }
        }
    }
    Text(tr("Note type (to list its fields)"), color = Colors.text, fontSize = 14.sp)
    FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
        TvChip(tr("All"), type.isEmpty()) { type = "" }
        types.forEach { t -> TvChip(t.name, type == t.name) { type = t.name } }
    }
    Text(tr("Word field"), color = Colors.text, fontSize = 14.sp)
    FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
        (fields + word).distinct().forEach { f -> TvChip(f, f == word) { word = f; settings.setAnkiWordField(lang, f); sources.reread() } }
    }
    if (lang == Lang.JAPANESE) {
        Text(tr("Reading field"), color = Colors.text, fontSize = 14.sp)
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            (fields + reading).distinct().forEach { f -> TvChip(f, f == reading) { reading = f; settings.setAnkiReadingField(lang, f); sources.reread() } }
        }
    }
    val read by language.known.ankiState.collectAsState()
    Text(read.error?.let { tr("Anki was not read: %s", it) } ?: tr("%d words known from your Anki.", read.count), color = Colors.dim, fontSize = 14.sp)
}

@Composable
internal fun TvOpenSettings() {
    val context = androidx.compose.ui.platform.LocalContext.current
    TvChip(tr("Open kumapie's settings in Android"), false) {
        runCatching {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null)))
        }
    }
}
