package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.ColourDevice
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.ColourSample
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, DelicateCoroutinesApi::class)
@Composable
fun WordColouringScreen(library: Library, onboarding: Boolean, onBack: () -> Unit, step: Int = 0, steps: Int = 0,
                        onContinue: () -> Unit = {}, onSkip: () -> Unit = {}) {
    val settings = library.settings
    var colours by remember { mutableStateOf(!settings.plainWords) }
    val learning = remember { library.languages.all.filter { it.lang.code in settings.learning() }.ifEmpty { listOf(library.languages.japanese) } }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        if (!onboarding) TopAppBar(title = { Text(tr("Word colouring"), fontSize = 32.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
    }, bottomBar = {
        if (onboarding) ColourBottomRow(onBack, onContinue) {
            settings.plainWords = true
            onSkip()
        }
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 840.dp
            val left: @Composable () -> Unit = {
                ColourLeft(settings, onboarding, step, steps, wide, colours) {
                    colours = it
                    settings.plainWords = !it
                    if (it) GlobalScope.launch(Dispatchers.IO) { library.refreshWordColours() }
                }
            }
            val right: @Composable () -> Unit = {
                if (colours) ColourRight(library, learning)
                else DashedNote(tr("Plain subtitles"), listOf(tr("No sources to pick, nothing to import. Lookups, mining and reviews still work."),
                    tr("Turn it on any time in Settings › Word colouring.")))
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { left() }
                    Column(Modifier.weight(1f)) { right() }
                } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { left() }
                    right()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourLeft(settings: io.github.pedrubik2000.kumapie.data.Settings, onboarding: Boolean, step: Int, steps: Int, wide: Boolean,
                       colours: Boolean, onColours: (Boolean) -> Unit) {
    if (onboarding) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).glass(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.FormatColorText, null, tint = Color.White)
        }
        Column {
            ColourCaption(tr("Step %1\$d of %2\$d · Optional", step, steps))
            Text(tr("Word colouring"), color = Colors.text, fontSize = if (wide) 34.sp else 28.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Text(tr("Colour words in the subtitles by what you know, so new words stand out while you watch."), color = Glass.dim, fontSize = 15.sp)
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).toggleable(colours, role = Role.Switch, onValueChange = onColours), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Colour words in subtitles"), color = Colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("Off: plain subtitles, and this step ends here."), color = Glass.dim, fontSize = 12.5.sp)
            }
            Switch(colours, null, colors = SwitchDefaults.colors(checkedTrackColor = Color.White, checkedThumbColor = Color.Black, checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = Glass.line, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
        }
        Text(ColourSample.line(colours), color = Colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (colours) FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ColourSample.legend().forEach { (colour, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(11.dp).background(colour, RoundedCornerShape(3.dp)))
                    Text(label, color = Colors.text, fontSize = 12.sp)
                }
            }
        }
    }
    if (colours) ColourDevicePick(settings)
}

@Composable
private fun ColourDevicePick(settings: io.github.pedrubik2000.kumapie.data.Settings) {
    val context = LocalContext.current
    ColourCaption(tr("This device"), Modifier.padding(top = 8.dp))
    if (settings.server.isNotEmpty()) return Text(tr("With a PC, the PC syncs your devices."), color = Glass.dim, fontSize = 14.sp)
    var answers by remember { mutableStateOf(ColourDevice.answers(settings)) }
    var then by remember { mutableStateOf<(() -> Unit)?>(null) }
    val lan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { then?.invoke(); then = null }
    fun serve() {
        val go = { answers = true; ColourDevice.setAnswers(context, settings, true) }
        val p = "android.permission.ACCESS_LOCAL_NETWORK"
        if (android.os.Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED) go()
        else { then = go; lan.launch(p) }
    }
    var confirmStop by remember { mutableStateOf(false) }
    ColourRadioCard(!answers, tr("Sync with a main server"), tr("Known words and new cards come from the main kumapie on the home Wi-Fi")) {
        if (answers) confirmStop = true
    }
    if (confirmStop) {
        Text(tr("Other devices sync through this one; stop?"), color = Colors.text, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                confirmStop = false
                answers = false
                ColourDevice.setAnswers(context, settings, false)
            }) { Text(tr("Stop")) }
            OutlinedButton(onClick = { confirmStop = false }) { Text(tr("Keep")) }
        }
    }
    ColourRadioCard(answers, tr("This device is the main server"), tr("Phone, tablet, TV kumapie and kumahon sync with it")) {
        confirmStop = false
        serve()
    }
    if (!answers && ColourDevice.peerName(settings) == null) Text(tr("Not paired yet: pair it in Settings."), color = Glass.dim, fontSize = 13.sp)
}

@Composable
private fun ColourRight(library: Library, learning: List<io.github.pedrubik2000.kumapie.lang.Language>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            ColourCaption(tr("Known words come from"), Modifier.weight(1f))
            Text(tr("Tick one or more"), color = Glass.dim, fontSize = 13.sp)
        }
        Text("✓ " + tr("kumapie's own cards always count (known = card reviewed once)."), color = Glass.dim, fontSize = 13.sp)
        ColourSourceList(library, learning)
    }
}

@Composable
private fun ColourBottomRow(onBack: () -> Unit, onContinue: () -> Unit, onSkip: () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Pill(tr("Back"), onBack)
        Pill(tr("Continue"), onContinue, primary = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("or") + " ", color = Glass.dim, fontSize = 14.sp)
            Text(tr("Not now"), color = Glass.dim, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
                modifier = Modifier.sizeIn(minHeight = 48.dp).clickable(role = Role.Button, onClick = onSkip).padding(vertical = 14.dp))
            Text(tr(": plain subtitles, change it in Settings"), color = Glass.dim, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun WordColouringRow(settings: io.github.pedrubik2000.kumapie.data.Settings, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).clickable(role = Role.Button, onClick = onOpen).padding(vertical = 4.dp)) {
        Text(tr("Word colouring"), color = Colors.text, fontSize = 17.sp)
        Text(if (settings.plainWords) tr("Off: plain subtitles") else tr("On: colours by what you know"), color = Glass.dim, fontSize = 14.sp)
    }
}
