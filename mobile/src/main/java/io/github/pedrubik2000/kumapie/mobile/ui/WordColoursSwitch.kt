package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@OptIn(DelicateCoroutinesApi::class)
@Composable
internal fun WordColoursSwitch(library: Library) {
    var colours by remember { mutableStateOf(!library.settings.plainWords) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Word colours (off: plain subtitles, lookup still works)"), fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(colours, {
            colours = it
            library.settings.plainWords = !it
            if (it) GlobalScope.launch(Dispatchers.IO) { library.refreshWordColours() }
        })
    }
}

internal suspend fun Library.refreshWordColours() {
    if (settings.plainWords) return
    languages.all.map { it.known }.filter { k -> k.modelReady && (k.ready || k.ankiApp()?.let(k::hasPermission) == true) }
        .forEach { it.refresh() }
}
