package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import io.github.pedrubik2000.kumapie.ui.rememberNow
import kotlinx.coroutines.delay

@Composable
internal fun AllowPrompt(hold: Boolean, onSettings: () -> Unit, paceMs: Long = 10_000) {
    val context = LocalContext.current
    val asking = DeviceSync.live(DeviceSync.asking.collectAsState().value, rememberNow())
    val conflicts by DeviceSync.conflicts.collectAsState()
    val later = rememberSaveable { mutableStateListOf<String>() }
    val pending = asking.filter { "${it.device}/${it.code}" !in later }
    val flood = pending.size > MANY
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
    if (name != null) AlertDialog(onDismissRequest = { DeviceSync.dismissConflict(name) },
        text = { Text(DeviceSync.conflictLine(name)) },
        confirmButton = { TextButton(onClick = { DeviceSync.dismissConflict(name) }) { Text(tr("OK")) } })
    else if (flood && !hold) AlertDialog(onDismissRequest = { later += pending.map { "${it.device}/${it.code}" } },
        text = { Text(tr("Several devices are asking to pair. Check the codes in Settings › Sync.")) },
        confirmButton = { TextButton(onClick = { later += pending.map { "${it.device}/${it.code}" }; onSettings() }) { Text(tr("Open Settings")) } },
        dismissButton = { TextButton(onClick = { later += pending.map { "${it.device}/${it.code}" } }) { Text(tr("OK")) } })
    else if (a != null) AlertDialog(onDismissRequest = { later += "${a.device}/${a.code}" },
        text = { Text(tr("%1\$s wants to pair. Code %2\$s.", a.device, a.code)) },
        confirmButton = { TextButton(onClick = { DeviceSync.allow(context, a) }) { Text(tr("Allow")) } },
        dismissButton = { TextButton(onClick = { later += "${a.device}/${a.code}" }) { Text(tr("Not now")) } })
}

private const val MANY = 3
