package io.github.pedrubik2000.kumapie.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.BuildConfig
import io.github.pedrubik2000.kumapie.update.Updater
import kotlinx.coroutines.launch

/** "Version X is out": Update downloads it and hands it to Android's installer; Later closes. */
@Composable
fun UpdateDialog(release: Updater.Release, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(-1f) }
    var message by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    fun update() {
        if (!Updater.canInstall(context)) {
            message = "Allow kumapie to install apps on the page that opened, then press Update again."
            return
        }
        progress = 0f
        message = "Downloading…"
        scope.launch {
            runCatching { Updater.download(context, release) { progress = it } }
                .onSuccess { message = "Installing…"; Updater.install(context, it) }
                .onFailure { progress = -1f; message = "Download failed: ${it.message}" }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp).background(Colors.surface, RoundedCornerShape(16.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("kumapie ${release.version} is out", fontSize = 26.sp, color = Colors.text)
            Text("You have ${BuildConfig.VERSION_NAME}.", color = Colors.dim)
            if (release.notes.isNotBlank()) Text(release.notes.take(600), color = Colors.text, fontSize = 15.sp)
            if (progress >= 0f) ProgressBar(progress, Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message, color = Colors.dim)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = ::update, modifier = Modifier.focusRequester(focus)) { Text("Update") }
                Button(onClick = onClose) { Text("Later") }
            }
        }
    }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
