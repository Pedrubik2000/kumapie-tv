package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import io.github.pedrubik2000.kumapie.lang.SyncListener
import io.github.pedrubik2000.kumapie.lang.SyncService
import io.github.pedrubik2000.kumapie.mobile.local.Transfer
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch

@Composable
internal fun SyncSection(settings: Settings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    Text(tr("Sync with another device"), color = Colors.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    if (settings.server.isNotEmpty()) return Text(tr("With a PC, the PC syncs your devices."), color = Colors.dim, fontSize = 14.sp)
    Text(tr("Words known, marks, reviews, places and stats, over the home Wi-Fi. Each device keeps its own cards."), color = Colors.dim, fontSize = 14.sp)
    var then by remember { mutableStateOf<(() -> Unit)?>(null) }
    val lan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { then?.invoke(); then = null }
    fun withLan(go: () -> Unit) {
        val p = "android.permission.ACCESS_LOCAL_NETWORK"
        if (android.os.Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED) go()
        else { then = go; lan.launch(p) }
    }
    var peer by remember { mutableStateOf(DeviceSync.with(settings)) }
    var message by remember { mutableStateOf("") }
    var looking by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    val last by DeviceSync.last.collectAsState()
    val found by DeviceSync.found.collectAsState()
    val asking by DeviceSync.asking.collectAsState()
    fun pair(a: String) = withLan {
        message = tr("Pairing…")
        scope.launch {
            message = runCatching { DeviceSync.pair(context, a) }.getOrElse { tr("Couldn't pair: %s", it.message ?: it.toString()) }
            peer = DeviceSync.with(settings)
        }
    }
    peer?.let { p ->
        Text(tr("Syncs with %1\$s (%2\$s)", p.name, p.address), fontSize = 15.sp)
        Row {
            OutlinedButton(onClick = { withLan { message = tr("Syncing…"); scope.launch { message = DeviceSync.syncNow(context) } } }) { Text(tr("Sync now")) }
            OutlinedButton(onClick = { DeviceSync.forget(settings); peer = null }) { Text(tr("Stop syncing")) }
        }
    }
    if (last.isNotEmpty()) Text(last, color = Colors.dim, fontSize = 14.sp)
    OutlinedButton(enabled = !looking, onClick = {
        withLan { looking = true; scope.launch { DeviceSync.discover(context, 8_000); looking = false } }
    }) { Text(if (looking) tr("Looking on the Wi-Fi…") else tr("Find devices")) }
    found.forEach { (name, a) -> OutlinedButton(onClick = { pair(a) }) { Text(tr("Sync with %s", name)) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(address, { address = it }, Modifier.weight(1f), label = { Text(tr("Or its address (192.168.1.20:%d)", DeviceSync.PORT)) }, singleLine = true)
        OutlinedButton(enabled = address.isNotBlank(), onClick = { pair(address.trim()) }) { Text(tr("Pair")) }
    }
    if (message.isNotEmpty()) Text(message, fontSize = 15.sp)
    var answers by remember { mutableStateOf(settings.prefs.getBoolean(DeviceSync.ANSWERS, false)) }
    var background by remember { mutableStateOf(settings.prefs.getBoolean(DeviceSync.BACKGROUND, false)) }
    fun answer(on: Boolean) = withLan {
        answers = on
        settings.prefs.edit().putBoolean(DeviceSync.ANSWERS, on).apply()
        if (on) SyncListener.start(context) else { SyncListener.stop(context); SyncService.stop(context) }
    }
    fun keep(on: Boolean) {
        background = on
        settings.prefs.edit().putBoolean(DeviceSync.BACKGROUND, on).apply()
        if (on) SyncService.start(context) else SyncService.stop(context)
    }
    Row(Modifier.fillMaxWidth().toggleable(answers) { answer(it) }, verticalAlignment = Alignment.CenterVertically) {
        Text(tr("This device answers the others"), Modifier.weight(1f))
        Switch(answers, null)
    }
    if (answers) {
        Text(tr("Its address: %1\$s:%2\$d", Transfer.address() ?: "?", DeviceSync.PORT), color = Colors.dim, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().toggleable(background) { keep(it) }, verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Also while kumapie is closed (a notification; Wi-Fi only)"), Modifier.weight(1f))
            Switch(background, null)
        }
        asking.forEach { a -> OutlinedButton(onClick = { DeviceSync.allow(context, a) }) { Text(tr("Allow %1\$s (code %2\$s)", a.device, a.code)) } }
    }
}
