package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.pedrubik2000.kumapie.data.AnkiSource
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.ColourDevice
import io.github.pedrubik2000.kumapie.lang.ColourJiten
import io.github.pedrubik2000.kumapie.lang.ColourSources
import io.github.pedrubik2000.kumapie.lang.Language
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors

@Composable
internal fun ColourSourceList(library: Library, learning: List<Language>) {
    val settings = library.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = remember { ColourSources(context, settings, learning, scope) }
    val japanese = learning.firstOrNull { it.lang == Lang.JAPANESE }
    val jiten = remember { japanese?.let { ColourJiten(it.known, scope, library::syncKnownLists) } }
    var open by remember { mutableStateOf<String?>(if (jiten != null) JITEN else null) }
    val importFocus = remember { FocusRequester() }
    var focusImport by remember { mutableIntStateOf(0) }
    val activity = context.activity()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        sources.answered(granted) { activity?.shouldShowRequestPermissionRationale(it) }
    }
    fun tick(which: String) { sources.toggle(which)?.let(ask::launch) }
    LifecycleResumeEffect(Unit) {
        sources.resumed()
        onPauseOrDispose {}
    }
    LaunchedEffect(sources.loadKey) { sources.load() }
    fun allow() { sources.select(sources.choice)?.let(ask::launch) }
    LaunchedEffect(focusImport) { if (focusImport > 0) runCatching { importFocus.requestFocus() } }
    fun expand(key: String) { open = if (open == key) null else key }

    Column(Modifier.glass(RoundedCornerShape(16.dp))) {
        if (jiten != null) {
            JitenRow(jiten, open == JITEN, importFocus, onTick = {
                open = JITEN
                if (jiten.tick()) focusImport++
            }, onExpand = { expand(JITEN) })
            HorizontalDivider(color = Glass.line)
        }
        if (ColourDevice.kumahonPaired(settings)) {
            ColourSourceRow("熊", Colors.accent, "kumahon", tr("Known words and marks come from the sync"), checked = true, expanded = open == KUMAHON,
                count = tr("via sync"), canTick = false, onTick = {}, onExpand = { expand(KUMAHON) }) {
                Text(tr("Words and marks arrive when kumahon syncs with this device."), color = Glass.dim, fontSize = 13.sp)
            }
            HorizontalDivider(color = Glass.line)
        }
        AnkiRow(sources, settings, AnkiSource.KUMA3, open == AnkiSource.KUMA3, { tick(AnkiSource.KUMA3) }, { allow() }, { expand(AnkiSource.KUMA3) })
        HorizontalDivider(color = Glass.line)
        AnkiRow(sources, settings, AnkiSource.ANKIDROID, open == AnkiSource.ANKIDROID, { tick(AnkiSource.ANKIDROID) }, { allow() }, { expand(AnkiSource.ANKIDROID) })
    }
    if (sources.said.isNotEmpty()) Text(sources.said, color = Colors.text, fontSize = 14.sp)
    if (sources.blocked) OutlinedButton(onClick = { context.startActivity(settingsIntent(context)) }) { Text(tr("Open kumapie's settings in Android")) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JitenRow(jiten: ColourJiten, expanded: Boolean, importFocus: FocusRequester, onTick: () -> Unit, onExpand: () -> Unit) {
    ColourSourceRow("辞", Glass.accent.copy(alpha = 0.35f), tr("Most common words (Jiten)"), jiten.subtitle, checked = jiten.on, expanded = expanded,
        onTick = { onTick() }, onExpand = onExpand) {
        if (jiten.confirming) {
            Text(tr("Remove %d imported words on every synced device?", jiten.imported), color = Colors.text, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = jiten::remove) { Text(tr("Remove")) }
                OutlinedButton(onClick = jiten::keep) { Text(tr("Keep")) }
            }
        }
        Text(tr("Count the top N words as known"), color = Glass.dim, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColourJiten.TOPS.forEach { n ->
                FilterChip(selected = jiten.top == n, onClick = { jiten.top = n }, label = { Text("%,d".format(n)) })
            }
        }
        Text(tr("Also:"), color = Glass.dim, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            io.github.pedrubik2000.kumapie.lang.Jiten.SETS.forEach { (slug, name) ->
                FilterChip(selected = jiten.sets[slug] == true, onClick = { jiten.sets[slug] = jiten.sets[slug] != true }, label = { Text(tr(name)) })
            }
        }
        OutlinedButton(enabled = !jiten.busy, onClick = jiten::importNow, modifier = Modifier.padding(top = 4.dp).focusRequester(importFocus)) { Text(tr("Import now")) }
        if (jiten.fromSync) Text(tr("This replaces the %d words from your other device", jiten.imported), color = Glass.dim, fontSize = 13.sp)
    }
}

@Composable
private fun AnkiRow(sources: ColourSources, settings: io.github.pedrubik2000.kumapie.data.Settings, which: String, expanded: Boolean,
                    onTick: () -> Unit, onAllow: () -> Unit, onExpand: () -> Unit) {
    val kuma3 = which == AnkiSource.KUMA3
    val pkg = if (kuma3) sources.kuma3 else sources.ankiDroid
    val active = pkg != null && pkg == sources.app
    val total = sources.states.sumOf { it.collectAsState().value.count }
    val first = sources.languages.first().lang
    val subtitle = when {
        pkg == null -> tr("Not installed on this device")
        !kuma3 && sources.bothInstalled && sources.kuma3On -> tr("Also installed: kuma3 is used first")
        active -> tr("On this device · deck %1\$s · field %2\$s", settings.ankiDecks(first).joinToString(", "), settings.ankiWordField(first))
        else -> tr("On this device")
    }
    val logo = if (kuma3) "k3" else "A"
    val colour = if (kuma3) Colors.mined.copy(alpha = 0.55f) else Colors.due.copy(alpha = 0.55f)
    ColourSourceRow(logo, colour, if (kuma3) "kuma3" else "AnkiDroid", subtitle, checked = if (kuma3) sources.kuma3On else sources.ankiDroidOn,
        expanded = expanded, count = if (pkg == null) null else if (active) tr("%s known", "%,d".format(total)) else tr("Not used"),
        dimCount = !active, onTick = { onTick() }, onExpand = onExpand) {
        when {
            pkg == null -> Text(tr("Install it on this device to use it."), color = Glass.dim, fontSize = 13.sp)
            !active -> Text(if (!kuma3 && sources.kuma3On) tr("kuma3 is read instead, so AnkiDroid is not used.") else tr("Tick it to read its cards."),
                color = Glass.dim, fontSize = 13.sp)
            else -> ColourAnkiSettings(sources, settings, onAllow = onAllow)
        }
    }
}

private const val JITEN = "jiten"
private const val KUMAHON = "kumahon"
