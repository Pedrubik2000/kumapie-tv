package io.github.pedrubik2000.kumapie.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.AnkiSource
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.ColourDevice
import io.github.pedrubik2000.kumapie.lang.ColourJiten
import io.github.pedrubik2000.kumapie.lang.ColourSources
import io.github.pedrubik2000.kumapie.lang.Jiten
import io.github.pedrubik2000.kumapie.lang.Language

private const val JITEN = "jiten"
private const val KUMAHON = "kumahon"

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun TvColourSources(settings: Settings, language: Language) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = remember { ColourSources(context, settings, listOf(language), scope) }
    val jiten = remember { ColourJiten(language.known, scope).takeIf { language.lang == Lang.JAPANESE } }
    var open by remember { mutableStateOf<String?>(if (jiten != null) JITEN else null) }
    val importFocus = remember { FocusRequester() }
    var focusImport by remember { mutableIntStateOf(0) }
    val checkFocus = remember { FocusRequester() }
    var focusCheck by remember { mutableIntStateOf(0) }
    val activity = remember(context) { context.activity() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        sources.answered(granted) { activity?.shouldShowRequestPermissionRationale(it) }
    }
    fun askFor(permission: String?) {
        permission?.let(ask::launch)
    }
    LifecycleResumeEffect(Unit) {
        sources.resumed()
        onPauseOrDispose {}
    }
    LaunchedEffect(sources.loadKey) { sources.load() }
    LaunchedEffect(focusImport) { if (focusImport > 0) runCatching { importFocus.requestFocus() } }
    LaunchedEffect(focusCheck) { if (focusCheck > 0) runCatching { checkFocus.requestFocus() } }
    fun expand(key: String) { open = if (open == key) null else key }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().background(Arcade.slab, shape).border(1.dp, Arcade.line, shape)) {
        if (jiten != null) {
            TvJitenRow(jiten, open == JITEN, importFocus, checkFocus, onConfirmed = { focusCheck++ }, onTick = {
                open = JITEN
                if (jiten.tick()) focusImport++
            }, onExpand = { expand(JITEN) })
            TvDivider()
        }
        if (ColourDevice.kumahonPaired(settings)) {
            TvSourceRow("熊", Colors.accent, "kumahon", tr("Known words and marks come from the sync"), checked = true, expanded = open == KUMAHON,
                count = tr("via sync"), canTick = false, onTick = {}, onExpand = { expand(KUMAHON) }) {
                Text(tr("Words and marks arrive when kumahon syncs with this device."), color = Colors.dim, fontSize = 14.sp)
            }
            TvDivider()
        }
        val allow = { askFor(sources.select(sources.choice)) }
        TvAnkiRow(sources, settings, AnkiSource.KUMA3, open == AnkiSource.KUMA3, { askFor(sources.toggle(AnkiSource.KUMA3)) }, allow) { expand(AnkiSource.KUMA3) }
        TvDivider()
        TvAnkiRow(sources, settings, AnkiSource.ANKIDROID, open == AnkiSource.ANKIDROID, { askFor(sources.toggle(AnkiSource.ANKIDROID)) }, allow) { expand(AnkiSource.ANKIDROID) }
    }
    if (sources.said.isNotEmpty()) Text(sources.said, color = Colors.text, fontSize = 14.sp)
    if (sources.blocked) TvOpenSettings()
}

@Composable
private fun TvDivider() = Column(Modifier.fillMaxWidth().height(1.dp).background(Arcade.line)) {}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvJitenRow(jiten: ColourJiten, expanded: Boolean, importFocus: FocusRequester, checkFocus: FocusRequester, onConfirmed: () -> Unit, onTick: () -> Unit, onExpand: () -> Unit) {
    TvSourceRow("辞", Arcade.accent.copy(alpha = 0.35f), tr("Most common words (Jiten)"), jiten.subtitle, checked = jiten.on, expanded = expanded, checkModifier = Modifier.focusRequester(checkFocus),
        onTick = { onTick() }, onExpand = onExpand) {
        if (jiten.confirming) {
            Text(tr("Remove %d imported words on every synced device?", jiten.imported), color = Colors.text, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TvChip(tr("Remove"), false) {
                    jiten.remove()
                    onConfirmed()
                }
                TvChip(tr("Keep"), false) {
                    jiten.keep()
                    onConfirmed()
                }
            }
        }
        Text(tr("Count the top N words as known"), color = Colors.dim, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ColourJiten.TOPS.forEach { n -> TvChip("%,d".format(n), jiten.top == n) { jiten.top = n } }
        }
        Text(tr("Also:"), color = Colors.dim, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Jiten.SETS.forEach { (slug, name) -> TvChip(tr(name), jiten.sets[slug] == true) { jiten.sets[slug] = jiten.sets[slug] != true } }
            TvChip(tr("Import now"), false, Modifier.focusRequester(importFocus)) { jiten.importNow() }
        }
        if (jiten.fromSync) Text(tr("This replaces the %d words from your other device", jiten.imported), color = Colors.dim, fontSize = 14.sp)
    }
}

@Composable
private fun TvAnkiRow(sources: ColourSources, settings: Settings, which: String, expanded: Boolean, onTick: () -> Unit, onAllow: () -> Unit, onExpand: () -> Unit) {
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
    val colour = if (kuma3) Colors.mined.copy(alpha = 0.55f) else Colors.due.copy(alpha = 0.55f)
    TvSourceRow(if (kuma3) "k3" else "A", colour, if (kuma3) "kuma3" else "AnkiDroid", subtitle, checked = if (kuma3) sources.kuma3On else sources.ankiDroidOn,
        expanded = expanded, count = if (pkg == null) null else if (active) tr("%s known", "%,d".format(total)) else tr("Not used"),
        dimCount = !active, onTick = { onTick() }, onExpand = onExpand) {
        when {
            pkg == null -> Text(tr("Install it on this device to use it."), color = Colors.dim, fontSize = 14.sp)
            !active -> Text(if (!kuma3 && sources.kuma3On) tr("kuma3 is read instead, so AnkiDroid is not used.") else tr("Tick it to read its cards."), color = Colors.dim, fontSize = 14.sp)
            else -> TvAnkiSettings(sources, settings, onAllow)
        }
    }
}
