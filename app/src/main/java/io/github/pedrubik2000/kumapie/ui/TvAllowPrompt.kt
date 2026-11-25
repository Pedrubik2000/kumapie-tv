package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.DeviceSync
@Composable
internal fun TvAllowPrompt(hold: Boolean, onSettings: () -> Unit, paceMs: Long = 10_000, guardMs: Long = 500) {
    val context = LocalContext.current
    val asking = DeviceSync.live(DeviceSync.asking.collectAsState().value, rememberNow())
    val conflicts by DeviceSync.conflicts.collectAsState()
    val later = rememberSaveable { mutableStateListOf<String>() }
    val pending = asking.filter { "${it.device}/${it.code}" !in later }
    val flood = pending.size > 3
    val cand = pending.firstOrNull().takeIf { !flood }
    var open by remember { mutableStateOf<DeviceSync.Asking?>(null) }
    var cool by remember { mutableStateOf(false) }
    LaunchedEffect(cand, hold, cool) {
        open = when {
            hold || cand == null -> null
            cand === open -> cand
            cool -> null
            else -> cand.also { cool = true }
        }
    }
    LaunchedEffect(cool) { if (cool) { delay(paceMs); cool = false } }
    val name = conflicts.firstOrNull().takeIf { !hold }
    val a = open
    val many = flood && !hold && name == null
    val line = name?.let { DeviceSync.conflictLine(it) }
        ?: (if (many) tr("Several devices are asking to pair. Check the codes in Settings › Sync.") else null)
        ?: a?.let { tr("%1\$s wants to pair. Code %2\$s.", it.device, it.code) } ?: return
    val hide = { later += pending.map { "${it.device}/${it.code}" } }
    val no = { if (name != null) DeviceSync.dismissConflict(name) else if (many) hide() else if (a != null) later += "${a.device}/${a.code}" }
    val focus = remember(name, a) { FocusRequester() }
    val openedAt = remember(name, a) { System.nanoTime() }
    val ok = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
    androidx.compose.ui.window.Dialog(onDismissRequest = no, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)).onBackKey(onBack = no)
            .onPreviewKeyEvent { it.key in ok && (System.nanoTime() - openedAt) / 1_000_000 < guardMs },
            contentAlignment = Alignment.Center) {
            Column(Modifier.width(640.dp).arcadePanel().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(line, color = Colors.text, fontSize = 20.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (name != null) Button(onClick = no, modifier = Modifier.focusRequester(focus)) { Text(tr("OK"), fontSize = 16.sp) }
                    else if (many) {
                        Button(onClick = { hide(); onSettings() }) { Text(tr("Open Settings"), fontSize = 16.sp) }
                        Button(onClick = no, modifier = Modifier.focusRequester(focus)) { Text(tr("OK"), fontSize = 16.sp) }
                    } else a?.let {
                        Button(onClick = { DeviceSync.allow(context, it) }) { Text(tr("Allow"), fontSize = 16.sp) }
                        Button(onClick = no, modifier = Modifier.focusRequester(focus)) { Text(tr("Not now"), fontSize = 16.sp) }
                    }
                }
            }
        }
        LaunchedEffect(name, a) { focus.focusSoon() }
    }
}
