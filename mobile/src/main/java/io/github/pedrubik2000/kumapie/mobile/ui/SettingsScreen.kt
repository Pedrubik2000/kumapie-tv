package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.mobile.BuildConfig
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The server address (first run too), downloads on the device, reports waiting, updates and the version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(library: Library, firstRun: Boolean, onSaved: () -> Unit, onUpdate: (Updater.Release) -> Unit, onBack: () -> Unit) {
    val settings = library.settings
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(settings.server) }
    var message by remember { mutableStateOf("") }
    var used by remember { mutableLongStateOf(0L) }
    var waiting by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        used = withContext(Dispatchers.IO) { library.downloads.bytesUsed() }
        waiting = library.pending.count()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (firstRun) "Welcome to kumapie" else "Settings") },
            navigationIcon = { if (!firstRun) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Server", color = Colors.accent)
            OutlinedTextField(server, { server = it }, label = { Text("https://my-pc.my-tailnet.ts.net:8445") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val url = Settings.normalizeServer(server)
                message = "Checking…"
                scope.launch {
                    runCatching { Api(url).info() }
                        .onSuccess {
                            settings.server = url
                            server = url
                            message = "Connected: ${it.episodes} episodes in ${it.language}."
                            onSaved()
                        }
                        .onFailure { message = "No answer from $url: ${it.message}" }
                }
            }) { Text("Save") }
            if (message.isNotEmpty()) Text(message, color = Colors.dim, fontSize = 14.sp)

            if (!firstRun) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("On this device", color = Colors.accent)
                Text("Downloads: ${"%.1f".format(used / 1e9)} GB", fontSize = 15.sp)
                OutlinedButton(onClick = { library.downloads.deleteAll(); refresh++ }) { Text("Remove all downloads") }
                Text(if (waiting == 0) "Nothing waiting to be sent to the PC." else "$waiting reports waiting for the PC (sent when it answers).",
                    fontSize = 15.sp, color = Colors.dim)
                if (waiting > 0) OutlinedButton(onClick = {
                    scope.launch { library.pending.flush(library.api, force = true); refresh++ }
                }) { Text("Send now") }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = {
                    message = "Looking for updates…"
                    scope.launch {
                        runCatching { Updater.newer() }
                            .onSuccess { r -> if (r == null) message = "This is the newest version." else { message = ""; onUpdate(r) } }
                            .onFailure { message = "Couldn't check: ${it.message}" }
                    }
                }) { Text("Check for updates") }
            }
            Text("Version ${BuildConfig.VERSION_NAME}", color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
