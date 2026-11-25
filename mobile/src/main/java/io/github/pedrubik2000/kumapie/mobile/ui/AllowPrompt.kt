package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.DeviceSync

@Composable
internal fun AllowPrompt() {
    val context = LocalContext.current
    val asking by DeviceSync.asking.collectAsState()
    val conflicts by DeviceSync.conflicts.collectAsState()
    val later = remember { mutableStateListOf<DeviceSync.Asking>() }
    val name = conflicts.firstOrNull()
    val a = asking.firstOrNull { it !in later }
    if (name != null) AlertDialog(onDismissRequest = { DeviceSync.dismissConflict(name) },
        text = { Text(DeviceSync.conflictLine(name)) },
        confirmButton = { TextButton(onClick = { DeviceSync.dismissConflict(name) }) { Text(tr("OK")) } })
    else if (a != null) AlertDialog(onDismissRequest = { later += a },
        text = { Text(tr("%1\$s wants to pair. Code %2\$s.", a.device, a.code)) },
        confirmButton = { TextButton(onClick = { DeviceSync.allow(context, a) }) { Text(tr("Allow")) } },
        dismissButton = { TextButton(onClick = { later += a }) { Text(tr("Not now")) } })
}
