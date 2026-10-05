package io.github.pedrubik2000.kumapie.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.player.Action
import io.github.pedrubik2000.kumapie.player.Binding
import io.github.pedrubik2000.kumapie.player.KeyContext
import io.github.pedrubik2000.kumapie.player.defaultBindings
import io.github.pedrubik2000.kumapie.player.isReservedKey
import io.github.pedrubik2000.kumapie.player.keyNames

/**
 * Settings > Change buttons. Every action with its buttons; choosing one waits for a button (a short press, or
 * held for a long press) and gives it to that action. A button does one thing per context, so it leaves the
 * action that had it. Clear removes an action's buttons; Reset brings back the defaults. Back can't be taken.
 */
@Composable
fun ButtonsScreen(settings: Settings) {
    val keys = remember {
        mutableStateMapOf<KeyContext, Map<Binding, Action>>().apply { KeyContext.entries.forEach { put(it, settings.keys(it)) } }
    }
    var capturing by remember { mutableStateOf<Action?>(null) }
    val focus = remember { mutableStateMapOf<Action, FocusRequester>() }
    val firstRow = remember { FocusRequester() }

    fun save(context: KeyContext, map: Map<Binding, Action>) {
        keys[context] = map
        settings.setKeys(context, map)
    }

    fun assign(action: Action, binding: Binding) {
        val context = action.context
        save(context, keys.getValue(context) - binding + (binding to action))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Buttons", fontSize = 30.sp, color = Colors.text)
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    KeyContext.entries.forEach { settings.resetKeys(it); keys[it] = defaultBindings(it) }
                }) { Text("Reset to defaults") }
            }
            Text("Choose an action, then press the button for it (hold it for a long press). Back can't be changed.",
                color = Colors.dim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                KeyContext.entries.forEach { context ->
                    item(key = context.name) {
                        Text(context.label, color = Colors.accent, fontSize = 20.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                    items(Action.entries.filter { it.context == context }, key = { it.name }) { action ->
                        val fr = focus.getOrPut(action) { FocusRequester() }
                        val buttons = keyNames(keys.getValue(context).filterValues { it == action }.keys)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { capturing = action }, scale = ButtonDefaults.scale(focusedScale = 1.02f),
                                modifier = Modifier.weight(1f).focusRequester(fr)
                                    .let { if (action == Action.entries.first()) it.focusRequester(firstRow) else it }) {
                                Text(action.label, modifier = Modifier.weight(1f))
                                Text(buttons.ifEmpty { "—" }, color = if (buttons.isEmpty()) Colors.dim else Color.Unspecified)
                            }
                            Button(onClick = { save(context, keys.getValue(context).filterValues { it != action }) }) { Text("Clear") }
                        }
                    }
                }
            }
        }
        capturing?.let { action ->
            CaptureOverlay(action,
                onDone = { binding ->
                    if (binding != null) assign(action, binding)
                    capturing = null
                    focus[action]?.let { runCatching { it.requestFocus() } }
                })
        }
    }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }
}

/** Waits for one button: pressed briefly = short, held = long. Back cancels. */
@Composable
private fun CaptureOverlay(action: Action, onDone: (Binding?) -> Unit) {
    val focus = remember { FocusRequester() }
    var pressed by remember { mutableStateOf<Int?>(null) }
    var held by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                when {
                    k.keyCode == KeyEvent.KEYCODE_BACK -> { if (k.action == KeyEvent.ACTION_UP) onDone(null) }
                    isReservedKey(k.keyCode) -> Unit
                    k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0 -> { pressed = k.keyCode; held = false }
                    k.action == KeyEvent.ACTION_DOWN && k.keyCode == pressed -> held = true
                    k.action == KeyEvent.ACTION_UP && k.keyCode == pressed -> onDone(Binding(k.keyCode, held))
                }
                true
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.background(Colors.surface, RoundedCornerShape(16.dp)).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(action.label, fontSize = 24.sp, color = Colors.text)
            Text(if (held) "Holding… let go to save it as a long press" else "Press a button (hold it for a long press)",
                fontSize = 18.sp, color = Colors.learning)
            Text("Back: cancel", fontSize = 15.sp, color = Colors.dim)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
