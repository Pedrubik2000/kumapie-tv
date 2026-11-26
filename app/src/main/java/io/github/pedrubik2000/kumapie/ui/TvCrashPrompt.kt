package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.BuildConfig
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.CrashCollector
import io.github.pedrubik2000.kumapie.lang.CrashReport
import io.github.pedrubik2000.kumapie.lang.CrashReports
import io.github.pedrubik2000.kumapie.lang.Delivery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TvCrashPrompt(show: Boolean, startMs: Long = 2_000) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<CrashReport?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show && !checked) {
            delay(startMs)
            checked = true
            report = withContext(Dispatchers.IO) { CrashCollector.pending(context) }
        }
    }
    val r = report
    if (show && r != null) {
        val env = remember { CrashCollector.env(context, "tv", BuildConfig.VERSION_NAME) }
        TvCrashDialog({ CrashCollector.send(context, r, env, CrashReports.QR_CAP) }, { CrashCollector.mark(context) }, { report = null })
    }
}

@Composable
internal fun TvCrashDialog(send: suspend () -> Delivery, mark: () -> Unit, close: () -> Unit, guardMs: Long = 500,
                           question: String = tr("kumapie closed unexpectedly last time. Send the crash report?"),
                           detail: String = tr("Sends the error, the device model and the app version — nothing else.")) {
    var phase by remember { mutableStateOf("ask") }
    var refused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember(phase) { FocusRequester() }
    val openedAt = remember { System.nanoTime() }
    val ok = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
    val no = { mark(); close() }
    androidx.compose.ui.window.Dialog(onDismissRequest = no, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)).onBackKey(onBack = no)
            .onPreviewKeyEvent { it.key in ok && (System.nanoTime() - openedAt) / 1_000_000 < guardMs },
            contentAlignment = Alignment.Center) {
            Column(Modifier.width(720.dp).arcadePanel().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (val p = phase) {
                    "ask" -> {
                        Text(question, color = Colors.text, fontSize = 22.sp)
                        Text(detail, color = Colors.dim, fontSize = 16.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(onClick = {
                                mark()
                                phase = "sending"
                                scope.launch { val d = send(); refused = d.refused; phase = d.issue ?: "sent" }
                            }, modifier = Modifier.focusRequester(focus)) { Text(tr("Send"), fontSize = 16.sp) }
                            Button(onClick = no) { Text(tr("Not now"), fontSize = 16.sp) }
                        }
                    }
                    "sending" -> Text(tr("Sending…"), color = Colors.text, fontSize = 20.sp)
                    "sent" -> {
                        Text(tr("Sent to your kumapie PC."), color = Colors.text, fontSize = 20.sp)
                        Button(onClick = close, modifier = Modifier.focusRequester(focus)) { Text(tr("OK"), fontSize = 16.sp) }
                    }
                    else -> {
                        if (refused) Text(tr("Your kumapie PC refused the report."), color = Colors.dim, fontSize = 16.sp)
                        Text(tr("Scan with your phone to open a ready-filled GitHub issue."), color = Colors.text, fontSize = 20.sp)
                        val code = remember(p) { qr(p.toString(), 700) }
                        Image(code.asImageBitmap(), null, Modifier.size(320.dp).background(Color.White).padding(10.dp))
                        Button(onClick = close, modifier = Modifier.focusRequester(focus)) { Text(tr("Close"), fontSize = 16.sp) }
                    }
                }
            }
        }
        LaunchedEffect(phase) { if (phase != "sending") focus.focusSoon() }
    }
}
