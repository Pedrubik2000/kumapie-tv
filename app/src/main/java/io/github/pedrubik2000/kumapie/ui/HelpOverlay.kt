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
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.player.text
import io.github.pedrubik2000.kumapie.data.keys
import io.github.pedrubik2000.kumapie.player.Action
import io.github.pedrubik2000.kumapie.player.Binding
import io.github.pedrubik2000.kumapie.player.KeyContext
import io.github.pedrubik2000.kumapie.player.keyNames

/** Every action that has a button, as set now (Settings > Change buttons), named for the episode's [lang]. Any button closes it. */
@Composable
fun HelpOverlay(settings: Settings, lang: Lang) {
    Box(Modifier.fillMaxSize().background(Color(0xE6101018)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 56.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(tr("Buttons"), fontSize = 30.sp, color = Colors.text)
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                KeyContext.entries.forEach { context ->
                    Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(context.text, fontSize = 20.sp, color = Colors.accent)
                        val keys = settings.keys(context)
                        Action.entries.filter { it.context == context }.forEach { action ->
                            val bound = keys.filterValues { it == action }.keys
                            if (bound.isNotEmpty()) HelpRow(action.text(lang), bound)
                        }
                        if (context == KeyContext.PICKER) HelpRow(tr("Back: close the picker"), emptySet(), plain = true)
                    }
                }
            }
            Text(tr("Speed, pause at scene end, subtitles and this list: Options (Menu, or hold ↓).") + "   " +
                tr("Change the buttons in Settings > Change buttons.   Any button closes this."),
                fontSize = 15.sp, color = Colors.dim)
        }
    }
}

@Composable
private fun HelpRow(text: String, keys: Set<Binding>, plain: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        val names = keyNames(keys)
        Text(text, fontSize = 15.sp, color = Colors.text, modifier = Modifier.weight(1f))
        if (!plain) {
            Text(names.ifEmpty { "—" }, fontSize = 15.sp, color = if (names.isEmpty()) Colors.dim else Colors.learning,
                modifier = Modifier.padding(start = 12.dp).background(Colors.surface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 1.dp))
        }
    }
}
