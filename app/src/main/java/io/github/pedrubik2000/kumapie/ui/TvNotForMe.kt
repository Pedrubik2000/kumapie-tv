package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr

@Composable
internal fun TvNotForMe(settings: Settings) {
    val store = remember { PickStore(settings.prefs) }
    var list by remember { mutableStateOf(store.neverList()) }
    var open by remember { mutableStateOf(false) }
    if (list.isEmpty()) return
    Button(onClick = { open = !open }) { Text(tr("Not for me: %d titles", list.size)) }
    if (open) {
        Text(tr("OK on a title puts it back in your picks."), color = Colors.dim)
        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { (id, name) ->
                key(id) { Button(onClick = { store.undoNotForMe(id); list = store.neverList() }) { Text("✕ " + name.ifEmpty { "#$id" }) } }
            }
        }
    }
}
