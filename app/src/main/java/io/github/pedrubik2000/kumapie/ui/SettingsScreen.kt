package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.BuildConfig
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.update.Updater
import kotlinx.coroutines.launch

/**
 * The server address and updates. Also the first-run screen (firstRun = true): the address can be typed here,
 * or sent from the PC: adb shell am start -n <app id>/io.github.pedrubik2000.kumapie.MainActivity --es server <url>
 */
@Composable
fun SettingsScreen(settings: Settings, firstRun: Boolean, onSaved: () -> Unit, onUpdate: (Updater.Release) -> Unit,
                   onButtons: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(settings.server) }
    var status by remember { mutableStateOf("") }
    var updateStatus by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    fun save() {
        val url = Settings.normalizeServer(server)
        status = "Connecting…"
        scope.launch {
            runCatching { Api(url).info() }
                .onSuccess {
                    settings.server = url
                    status = "Connected: ${it.episodes} episodes"
                    onSaved()
                }
                .onFailure { status = "Can't reach $url (${it.message})" }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (firstRun) "Welcome to kumapie" else "Settings", fontSize = 32.sp, color = Colors.text)
        if (firstRun) {
            Text("Type the address of the dojo server on the PC (Tailscale), e.g. https://my-pc.tailnet.ts.net:8445",
                color = Colors.dim)
        }
        Text("Server", fontSize = 18.sp, color = Colors.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvTextField(server, { server = it }, onDone = ::save, modifier = Modifier.width(640.dp).focusRequester(focus))
            Button(onClick = ::save) { Text("Save") }
        }
        if (status.isNotEmpty()) Text(status, color = Colors.dim)

        if (!firstRun) {
            Text("Buttons and help", fontSize = 18.sp, color = Colors.dim, modifier = Modifier.padding(top = 24.dp))
            var showHelp by remember { mutableStateOf(settings.showHelp) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onButtons) { Text("Change buttons") }
                Button(onClick = { showHelp = !showHelp; settings.showHelp = showHelp }) {
                    Text("Help screen and key hints: " + if (showHelp) "on" else "off")
                }
            }
            Text("Updates", fontSize = 18.sp, color = Colors.dim, modifier = Modifier.padding(top = 24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = {
                    updateStatus = "Checking…"
                    scope.launch {
                        runCatching { Updater.newer() }
                            .onSuccess { r -> if (r == null) updateStatus = "Up to date" else { updateStatus = ""; onUpdate(r) } }
                            .onFailure { updateStatus = "Couldn't check: ${it.message}" }
                    }
                }) { Text("Check for updates") }
            }
            if (updateStatus.isNotEmpty()) Text(updateStatus, color = Colors.dim)
            Text("Version ${BuildConfig.VERSION_NAME}", color = Colors.dim, modifier = Modifier.padding(top = 24.dp))
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
