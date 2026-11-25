package io.github.pedrubik2000.kumapie.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.ColourDevice
import io.github.pedrubik2000.kumapie.lang.Language

@Composable
fun TvWordColouring(settings: Settings, onboarding: Boolean, onBack: () -> Unit, step: Int = 0, steps: Int = 0,
                    onContinue: () -> Unit = {}, onSkip: () -> Unit = {}) {
    val context = LocalContext.current
    val language = remember { tvLanguages(context, settings).japanese }
    TvWordColouring(settings, language, onboarding, onBack, step, steps, onContinue, onSkip)
}

@Composable
internal fun TvWordColouring(settings: Settings, language: Language, onboarding: Boolean, onBack: () -> Unit, step: Int, steps: Int,
                             onContinue: () -> Unit, onSkip: () -> Unit) {
    var colours by remember { mutableStateOf(!settings.plainWords) }
    val first = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler(onBack = onBack)
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize().onBackKey(onBack = onBack)) {
        Column(Modifier.fillMaxSize().padding(bottom = rememberHintRoom() + 8.dp).fadeBottom(36.dp, scroll).verticalScroll(scroll).padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).focusGroup(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    TvColourHeader(onboarding, step, steps)
                    TvColourCard(colours, first) {
                        colours = it
                        settings.plainWords = !it
                    }
                    if (colours) TvDevicePick(settings)
                }
                Column(Modifier.weight(1.1f).focusGroup().onPreviewKeyEvent { e ->
                    if (e.key != Key.DirectionLeft) false
                    else {
                        if (e.type == KeyEventType.KeyDown && !focusManager.moveFocus(FocusDirection.Left)) runCatching { first.requestFocus() }
                        true
                    }
                }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (colours) TvColourRight(settings, language) else TvPlainNote()
                }
            }
            if (onboarding) TvColourBottom(onBack, onContinue) {
                settings.plainWords = true
                onSkip()
            }
        }
        HintBar()
    }
}

@Composable
private fun TvColourHeader(onboarding: Boolean, step: Int, steps: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(Arcade.slab, RoundedCornerShape(10.dp)).border(1.dp, Arcade.line, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.FormatColorText, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Column {
            if (onboarding) TvCaption(tr("Step %1\$d of %2\$d · Optional", step, steps))
            Text(tr("Word colouring"), color = Colors.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Text(tr("Colour words in the subtitles by what you know, so new words stand out while you watch."), color = Colors.dim, fontSize = 16.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvColourCard(colours: Boolean, focus: FocusRequester, onColours: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().background(Arcade.slab, shape).border(1.dp, Arcade.line, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Colour words in subtitles"), color = Colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("Off: plain subtitles, and this step ends here."), color = Colors.dim, fontSize = 12.sp)
            }
            TvSwitch(colours, tr("Colour words in subtitles"), Modifier.focusRequester(focus), onColours)
        }
        Text(ColourSample.line(colours), color = Colors.text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        if (colours) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ColourSample.legend().forEach { (colour, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(9.dp).background(colour, RoundedCornerShape(2.dp)))
                    Text(label, color = Colors.text, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TvDevicePick(settings: Settings) {
    val context = LocalContext.current
    var answers by remember { mutableStateOf(ColourDevice.answers(settings)) }
    var confirmStop by remember { mutableStateOf(false) }
    val syncFocus = remember { FocusRequester() }
    val serverFocus = remember { FocusRequester() }
    var refocus by remember { mutableIntStateOf(0) }
    var toSync by remember { mutableStateOf(false) }
    LaunchedEffect(refocus) { if (refocus > 0) runCatching { (if (toSync) syncFocus else serverFocus).requestFocus() } }
    TvCaption(tr("This device"), Modifier.padding(top = 6.dp))
    if (settings.server.isNotEmpty()) return Text(tr("With a PC, the PC syncs your devices."), color = Colors.dim, fontSize = 14.sp)
    TvRadioCard(!answers, tr("Sync with a main server"), tr("Known words and new cards come from the main kumapie on the home Wi-Fi"), Modifier.focusRequester(syncFocus)) {
        if (answers) confirmStop = true
    }
    if (confirmStop) {
        Text(tr("Other devices sync through this one; stop?"), color = Colors.text, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TvChip(tr("Stop"), false) {
                confirmStop = false
                toSync = true
                refocus++
                answers = false
                ColourDevice.setAnswers(context, settings, false)
            }
            TvChip(tr("Keep"), false) {
                confirmStop = false
                toSync = false
                refocus++
            }
        }
    }
    TvRadioCard(answers, tr("This device is the main server"), tr("Phone, tablet, TV kumapie and kumahon sync with it"), Modifier.focusRequester(serverFocus)) {
        confirmStop = false
        answers = true
        ColourDevice.setAnswers(context, settings, true)
    }
    if (!answers && ColourDevice.peerName(settings) == null) Text(tr("Not paired yet: pair it in Settings."), color = Colors.dim, fontSize = 14.sp)
}

@Composable
private fun TvColourRight(settings: Settings, language: Language) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        TvCaption(tr("Known words come from"), Modifier.weight(1f))
        Text(tr("Tick one or more"), color = Colors.dim, fontSize = 14.sp)
    }
    Text("✓ " + tr("kumapie's own cards always count (reviewed once = known)."), color = Colors.dim, fontSize = 14.sp)
    TvColourSources(settings, language)
}

@Composable
private fun TvPlainNote() {
    Column(Modifier.fillMaxWidth().drawBehind {
        drawRoundRect(Arcade.line, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
    }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(tr("Plain subtitles"), color = Colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(tr("No sources to pick, nothing to import. Lookups, mining and reviews still work."), color = Colors.dim, fontSize = 14.sp)
        Text(tr("Turn it on any time in Settings › Word colouring."), color = Colors.dim, fontSize = 14.sp)
    }
}

@Composable
private fun TvColourBottom(onBack: () -> Unit, onContinue: () -> Unit, onSkip: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onBack, modifier = Modifier.hint(HintKind.CHOOSE)) { Text("‹ " + tr("Back")) }
        Button(onClick = onContinue, modifier = Modifier.hint(HintKind.CHOOSE),
            colors = ButtonDefaults.colors(containerColor = Color.White, contentColor = Color.Black, focusedContainerColor = Color.White, focusedContentColor = Color.Black)) {
            Text(tr("Continue") + " ›")
        }
        Text(tr("or") + " ", color = Colors.dim, fontSize = 14.sp)
        val shape = RoundedCornerShape(6.dp)
        val source = remember { MutableInteractionSource() }
        Text(tr("Not now"), color = Colors.text, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
            modifier = Modifier.hint(HintKind.CHOOSE).tvRing(shape, source)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onSkip).padding(horizontal = 4.dp, vertical = 3.dp))
        Text(tr(": plain subtitles, change it in Settings"), color = Colors.dim, fontSize = 14.sp)
    }
}
