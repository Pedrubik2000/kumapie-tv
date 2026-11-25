package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.i18n.tr
import android.view.KeyEvent
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.BuildConfig
import io.github.pedrubik2000.kumapie.update.Updater
import kotlinx.coroutines.launch

@Composable
private fun ScrollingNotes(notes: String, modifier: Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    Text(notes, color = Colors.text, fontSize = 15.sp,
        modifier = modifier.onFocusChanged { focused = it.hasFocus }
            .border(2.dp, if (focused) Colors.accent else Color.Transparent, RoundedCornerShape(8.dp))
            .onPreviewKeyEvent {
                val down = it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                if (it.type != KeyEventType.KeyDown || !(down || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP)) return@onPreviewKeyEvent false
                val room = if (down) scroll.canScrollForward else scroll.canScrollBackward
                if (room) scope.launch { scroll.animateScrollBy(if (down) 120f else -120f) }
                room
            }
            .focusable().verticalScroll(scroll).padding(8.dp))
}

/** "Version X is out": Update downloads it and hands it to Android's installer; Later closes. */
@Composable
fun UpdateDialog(release: Updater.Release, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(-1f) }
    var message by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    fun update() { scope.launch { // shared with the phone (Updater.update)
        Updater.update(context, release, tr("Allow kumapie to install apps on the page that opened, then press Update again."),
            { progress = it }, { message = it }) }
    }

    // Its own window: drawn over Home in the same one, Home took the focus when its titles came in later and the remote
    // moved behind the dialog (Chromecast, 2026-11-18).
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).onBackKey(onBack = onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp).heightIn(max = 460.dp).arcadePanel().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title("kumapie ${release.version} ", tr("is out"), fontSize = 26.sp)
            Text(tr("You have %s.", BuildConfig.VERSION_NAME), color = Colors.dim)
            if (release.notes.isNotBlank()) ScrollingNotes(release.notes, Modifier.weight(1f, fill = false))
            if (progress >= 0f) ProgressBar(progress, Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message, color = Colors.dim)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = ::update, modifier = Modifier.focusRequester(focus)) { Text(tr("Update")) }
                Button(onClick = onClose) { Text(tr("Later")) }
            }
        }
    }
    LaunchedEffect(Unit) { focus.focusSoon() }
    }
}
