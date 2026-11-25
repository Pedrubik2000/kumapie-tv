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
import kotlinx.coroutines.delay

@Composable
internal fun AllowPrompt(hold: Boolean, paceMs: Long = 10_000) {
    val context = LocalContext.current
    val asking by DeviceSync.asking.collectAsState()
    val conflicts by DeviceSync.conflicts.collectAsState()
    val later = rememberSaveable { mutableStateListOf<String>() }
    val cand = asking.firstOrNull { "${it.device}/${it.code}" !in later }
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
    else if (a != null) AlertDialog(onDismissRequest = { later += "${a.device}/${a.code}" },
        text = { Text(tr("%1\$s wants to pair. Code %2\$s.", a.device, a.code)) },
        confirmButton = { TextButton(onClick = { DeviceSync.allow(context, a) }) { Text(tr("Allow")) } },
        dismissButton = { TextButton(onClick = { later += "${a.device}/${a.code}" }) { Text(tr("Not now")) } })
}
