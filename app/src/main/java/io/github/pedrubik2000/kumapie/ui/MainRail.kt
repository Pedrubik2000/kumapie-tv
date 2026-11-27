package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.i18n.tr

internal enum class RailAt { HOME, LIBRARY, DISCOVER, REVIEW, STATS, SETTINGS }

internal class RailNav(val onHome: () -> Unit, val onLibrary: () -> Unit, val onDiscover: () -> Unit,
                       val onReview: () -> Unit, val onStats: () -> Unit, val onSettings: () -> Unit)

@Composable
internal fun RailBackdrop() = Box(Modifier.fillMaxHeight().width(RAIL).background(Brush.horizontalGradient(
    0f to Colors.background.copy(alpha = 0.92f), 0.7f to Colors.background.copy(alpha = 0.75f), 1f to Color.Transparent)))

internal object DueTick {
    var n by androidx.compose.runtime.mutableIntStateOf(0)
}

@Composable
private fun rememberDue(api: Discovery, lang: String?): Int? {
    val context = LocalContext.current
    val due by produceState<Int?>(null, api, lang, DueTick.n) { value = lang?.let { runCatching {
        if (api is Api) api.srsDue(it).let { d -> d.due + d.new } else io.github.pedrubik2000.kumapie.lang.ownDueCount(context, it) }.getOrNull() ?: value } }
    return due
}

@Composable
internal fun BoxScope.MainRail(api: Discovery, lang: String?, at: RailAt?, nav: RailNav, focus: MutableMap<String, FocusRequester>, toRows: FocusRequester?,
                               extra: RailItem? = null, onFocused: () -> Unit = {}, onHasFocus: (Boolean) -> Unit = {}) {
    val due = rememberDue(api, lang)
    val back = at == RailAt.HOME
    fun item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, here: RailAt?, go: () -> Unit, badge: Int = 0) =
        RailItem(icon, label, badge, selected = here != null && here == at, back = back, onClick = if (here != null && here == at) ({}) else go)
    Rail(listOfNotNull(
        item(Icons.Default.Home, tr("Home"), RailAt.HOME, nav.onHome),
        item(Icons.Default.VideoLibrary, tr("Library"), RailAt.LIBRARY, nav.onLibrary),
        item(Icons.Default.Explore, tr("Discover"), RailAt.DISCOVER, nav.onDiscover).takeIf { lang == "ja" },
        due?.let { item(Icons.Default.Style, tr("Review"), RailAt.REVIEW, nav.onReview, badge = it) },
        item(Icons.Default.BarChart, tr("Stats"), RailAt.STATS, nav.onStats),
        extra,
        item(Icons.Default.Settings, tr("Settings"), RailAt.SETTINGS, nav.onSettings),
    ), focus, toRows, Modifier.align(Alignment.TopStart).padding(top = 100.dp), onFocused, onHasFocus)
}

internal object RailGate {
    var left = false
}

internal fun Modifier.railGate(): Modifier = onPreviewKeyEvent {
    if (it.key == Key.DirectionLeft) RailGate.left = it.type == KeyEventType.KeyDown
    false
}
