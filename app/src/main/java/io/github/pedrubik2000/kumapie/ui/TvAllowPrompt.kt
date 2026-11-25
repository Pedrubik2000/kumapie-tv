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
internal fun TvAllowPrompt() {
    val context = LocalContext.current
    val asking by DeviceSync.asking.collectAsState()
    val conflicts by DeviceSync.conflicts.collectAsState()
    val later = remember { mutableStateListOf<DeviceSync.Asking>() }
    val name = conflicts.firstOrNull()
    val a = asking.firstOrNull { it !in later }
    val line = name?.let { DeviceSync.conflictLine(it) } ?: a?.let { tr("%1\$s wants to pair. Code %2\$s.", it.device, it.code) } ?: return
    val focus = remember(name, a) { FocusRequester() }
    val no = { if (name != null) DeviceSync.dismissConflict(name) else if (a != null) later += a }
    androidx.compose.ui.window.Dialog(onDismissRequest = no, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)).onBackKey(onBack = no), contentAlignment = Alignment.Center) {
            Column(Modifier.width(640.dp).arcadePanel().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(line,
                    color = Colors.text, fontSize = 20.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (name != null) Button(onClick = no, modifier = Modifier.focusRequester(focus)) { Text(tr("OK"), fontSize = 16.sp) }
                    else a?.let {
                        Button(onClick = { DeviceSync.allow(context, it) }, modifier = Modifier.focusRequester(focus)) { Text(tr("Allow"), fontSize = 16.sp) }
                        Button(onClick = no) { Text(tr("Not now"), fontSize = 16.sp) }
                    }
                }
            }
        }
        LaunchedEffect(name, a) { focus.focusSoon() }
    }
}
