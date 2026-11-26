package io.github.pedrubik2000.kumapie.mobile.ui

import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.CrashCollector
import io.github.pedrubik2000.kumapie.lang.SubReports
import io.github.pedrubik2000.kumapie.mobile.BuildConfig
import io.github.pedrubik2000.kumapie.player.SceneController
import kotlinx.coroutines.launch

/** "Report subtitle timing": CrashPrompt's dialog (PC first, else a ready-filled GitHub issue) for the subtitle lines on screen. */
@Composable
internal fun SubReportDialog(ctl: SceneController, prefs: SharedPreferences, shift: Double?, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("ask") }
    var refused by remember { mutableStateOf(false) }
    val report = remember { SubReports.of(ctl, prefs, shift) }
    val env = remember { CrashCollector.env(context, "mobile", BuildConfig.VERSION_NAME) }
    AlertDialog(onDismissRequest = { if (phase != "sending") close() },
        title = { Text(tr("Report the subtitle timing of this episode to your PC?")) },
        text = {
            Text(when (phase) {
                "ask" -> tr("Sends the title, episode, subtitle file, the shifts and the lines on screen, nothing else.")
                "sending" -> tr("Sending…")
                "sent" -> tr("Sent to your kumapie PC.")
                else -> (if (refused) tr("Your kumapie PC refused the report.") + " " else "") + tr("Open a ready-filled GitHub issue to send the report.")
            })
        },
        confirmButton = {
            when (phase) {
                "ask" -> TextButton(onClick = {
                    phase = "sending"
                    scope.launch { val d = SubReports.send(context, report, env); refused = d.refused; phase = d.issue ?: "sent" }
                }) { Text(tr("Send")) }
                "sending" -> Unit
                "sent" -> TextButton(onClick = close) { Text(tr("OK")) }
                else -> TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(phase)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    close()
                }) { Text(tr("Open the GitHub issue")) }
            }
        },
        dismissButton = { if (phase == "ask") TextButton(onClick = close) { Text(tr("Not now")) } })
}
