package io.github.pedrubik2000.kumapie.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.CrashCollector
import io.github.pedrubik2000.kumapie.lang.CrashReport
import io.github.pedrubik2000.kumapie.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CrashPrompt(show: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<CrashReport?>(null) }
    var checked by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("ask") }
    LaunchedEffect(show) {
        if (show && !checked) {
            delay(2_000)
            checked = true
            report = withContext(Dispatchers.IO) { CrashCollector.pending(context) }
        }
    }
    val r = report
    if (!show || r == null) return
    val env = remember { CrashCollector.env(context, "mobile", BuildConfig.VERSION_NAME) }
    val no = { CrashCollector.mark(context); report = null }
    AlertDialog(onDismissRequest = if (phase == "ask") no else ({ report = null }),
        title = { Text(tr("kumapie closed unexpectedly last time. Send the crash report?")) },
        text = {
            Text(when (phase) {
                "ask" -> tr("Sends the error, the device model and the app version — nothing else.")
                "sending" -> tr("Sending…")
                "sent" -> tr("Sent to your kumapie PC.")
                else -> tr("Open a ready-filled GitHub issue to send the report.")
            })
        },
        confirmButton = {
            when (phase) {
                "ask" -> TextButton(onClick = {
                    CrashCollector.mark(context)
                    phase = "sending"
                    scope.launch { phase = CrashCollector.send(context, r, env) ?: "sent" }
                }) { Text(tr("Send")) }
                "sending" -> Unit
                "sent" -> TextButton(onClick = { report = null }) { Text(tr("OK")) }
                else -> TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(phase)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    report = null
                }) { Text(tr("Open the GitHub issue")) }
            }
        },
        dismissButton = { if (phase == "ask") TextButton(onClick = no) { Text(tr("Not now")) } })
}
