package io.github.pedrubik2000.kumapie.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.keys
import io.github.pedrubik2000.kumapie.player.Action
import io.github.pedrubik2000.kumapie.player.Binding
import io.github.pedrubik2000.kumapie.player.KeyContext
import io.github.pedrubik2000.kumapie.player.keyNames

/** Every action and its buttons, as set now (Settings > Change buttons). Any button closes it. */
@Composable
fun HelpOverlay(settings: Settings) {
    Box(Modifier.fillMaxSize().background(Color(0xE6101018)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 56.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Buttons", fontSize = 30.sp, color = Colors.text)
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                KeyContext.entries.forEach { context ->
                    Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(context.label, fontSize = 20.sp, color = Colors.accent)
                        val keys = settings.keys(context)
                        Action.entries.filter { it.context == context }.forEach { action ->
                            HelpRow(action, keys.filterValues { it == action }.keys)
                        }
                        if (context == KeyContext.PICKER) HelpRow(null, emptySet(), "Back: close the picker")
                    }
                }
            }
            Text("Change them in Settings > Change buttons. This screen can be turned off there too.   Any button closes it.",
                fontSize = 15.sp, color = Colors.dim)
        }
    }
}

@Composable
private fun HelpRow(action: Action?, keys: Set<Binding>, text: String? = null) {
    Row(Modifier.fillMaxWidth()) {
        val names = keyNames(keys)
        Text(text ?: action!!.label, fontSize = 15.sp, color = Colors.text, modifier = Modifier.weight(1f))
        if (text == null) {
            Text(names.ifEmpty { "—" }, fontSize = 15.sp, color = if (names.isEmpty()) Colors.dim else Colors.learning,
                modifier = Modifier.padding(start = 12.dp).background(Colors.surface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 1.dp))
        }
    }
}
