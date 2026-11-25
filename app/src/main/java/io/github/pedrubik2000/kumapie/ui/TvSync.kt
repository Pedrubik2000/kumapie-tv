package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import io.github.pedrubik2000.kumapie.lang.SyncListener
import io.github.pedrubik2000.kumapie.lang.SyncService
import kotlinx.coroutines.launch

@Composable
internal fun TvSync(settings: Settings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var peer by remember { mutableStateOf(DeviceSync.with(settings)) }
    var message by remember { mutableStateOf("") }
    var looking by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var answers by remember { mutableStateOf(settings.prefs.getBoolean(DeviceSync.ANSWERS, false)) }
    var background by remember { mutableStateOf(settings.prefs.getBoolean(DeviceSync.BACKGROUND, false)) }
    val last by DeviceSync.last.collectAsState()
    val found by DeviceSync.found.collectAsState()
    val asking by DeviceSync.asking.collectAsState()
    fun pair(a: String) {
        message = tr("Pairing…")
        scope.launch {
            message = runCatching { DeviceSync.pair(context, a) }.getOrElse { tr("Couldn't pair: %s", it.message ?: it.toString()) }
            peer = DeviceSync.with(settings)
        }
    }
    Heading(tr("Sync with another device"), Modifier.padding(top = 24.dp))
    Text(tr("Words known, marks, reviews, places and stats, over the home Wi-Fi. Each device keeps its own cards."), color = Colors.dim)
    peer?.let { p ->
        Text(tr("Syncs with %1\$s (%2\$s)", p.name, p.address), color = Colors.text)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { message = tr("Syncing…"); scope.launch { message = DeviceSync.syncNow(context) } }) { Text(tr("Sync now")) }
            Button(onClick = { DeviceSync.forget(settings); peer = null }) { Text(tr("Stop syncing")) }
        }
    }
    if (last.isNotEmpty()) Text(last, color = Colors.dim)
    Button(enabled = !looking, onClick = { looking = true; scope.launch { DeviceSync.discover(context, 8_000); looking = false } }) {
        Text(if (looking) tr("Looking on the Wi-Fi…") else tr("Find devices"))
    }
    found.forEach { (name, a) -> Button(onClick = { pair(a) }) { Text(tr("Sync with %s", name)) } }
    val pairButton = remember { FocusRequester() }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TvTextField(address, { address = it }, onDone = { if (address.isNotBlank()) pair(address.trim()) },
            modifier = Modifier.width(640.dp).focusProperties { down = pairButton; right = pairButton },
            placeholder = tr("Or its address (192.168.1.20:%d)", DeviceSync.PORT))
        Button(onClick = { if (address.isNotBlank()) pair(address.trim()) }, modifier = Modifier.focusRequester(pairButton)) { Text(tr("Pair")) }
    }
    if (message.isNotEmpty()) Text(message, color = Colors.text)
    Button(onClick = {
        answers = !answers
        settings.prefs.edit().putBoolean(DeviceSync.ANSWERS, answers).apply()
        if (answers) SyncListener.start(context) else { SyncListener.stop(context); SyncService.stop(context) }
    }) { Text(if (answers) tr("✓ This device answers the others") else tr("This device answers the others")) }
    if (answers) {
        Button(onClick = {
            background = !background
            settings.prefs.edit().putBoolean(DeviceSync.BACKGROUND, background).apply()
            if (background) SyncService.start(context) else SyncService.stop(context)
        }) { Text(if (background) tr("✓ Also while kumapie is closed (a notification; Wi-Fi only)") else tr("Also while kumapie is closed (a notification; Wi-Fi only)")) }
        asking.forEach { a -> Button(onClick = { DeviceSync.allow(context, a) }) { Text(tr("Allow %1\$s (code %2\$s)", a.device, a.code)) } }
    }
}
