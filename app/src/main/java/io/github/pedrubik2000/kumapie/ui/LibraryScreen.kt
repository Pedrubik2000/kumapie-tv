package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.i18n.tr

@Composable
fun LibraryScreen(onHome: () -> Unit, onDiscover: () -> Unit, onReview: () -> Unit, onSettings: () -> Unit) {
    val focus = remember { mutableMapOf<String, FocusRequester>() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = RAIL, top = 96.dp)) {
            Title(tr("Library"))
            Text(tr("Coming soon"), color = Colors.dim, modifier = Modifier.padding(top = 12.dp))
        }
        DiscoverRail(onHome, onReview, onSettings, onDiscover = onDiscover, library = true, focus = focus)
    }
    LaunchedEffect(Unit) { focus[tr("Library")]?.focusSoon() }
}
