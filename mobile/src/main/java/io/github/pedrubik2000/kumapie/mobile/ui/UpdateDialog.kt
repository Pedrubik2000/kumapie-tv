package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import io.github.pedrubik2000.kumapie.mobile.BuildConfig
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.update.Updater
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.launch

/** "Version X is out": Update downloads it and hands it to Android's installer; Later closes. */
@Composable
fun UpdateDialog(release: Updater.Release, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(-1f) }
    var message by remember { mutableStateOf("") }

    fun update() {
        if (!Updater.canInstall(context)) {
            message = tr("Allow kumapie to install apps on the page that opened, then tap Update again.")
            return
        }
        progress = 0f
        message = tr("Downloading…")
        scope.launch {
            runCatching { Updater.download(context, release) { progress = it } }
                .onSuccess { message = tr("Installing…"); Updater.install(context, it) }
                .onFailure { progress = -1f; message = tr("Download failed: %s", it.message) }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("kumapie %s is out", release.version)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("You have %s.", BuildConfig.VERSION_NAME), color = Colors.dim)
                if (release.notes.isNotBlank()) Text(release.notes.take(800), fontSize = 14.sp)
                if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                if (message.isNotEmpty()) Text(message, color = Colors.dim)
            }
        },
        confirmButton = { TextButton(onClick = ::update) { Text(tr("Update")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Later")) } },
    )
}
