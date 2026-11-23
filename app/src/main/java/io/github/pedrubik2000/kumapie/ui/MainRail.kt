package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.i18n.tr

internal enum class RailAt { HOME, LIBRARY, DISCOVER }

internal class RailNav(val onHome: () -> Unit, val onLibrary: () -> Unit, val onDiscover: () -> Unit,
                       val onReview: () -> Unit, val onStats: () -> Unit, val onSettings: () -> Unit)

@Composable
private fun rememberDue(api: Discovery, lang: String?): Int? {
    val context = LocalContext.current
    val due by produceState<Int?>(null, lang) { value = lang?.let { runCatching {
        if (api is Api) api.srsDue(it).let { d -> d.due + d.new } else io.github.pedrubik2000.kumapie.lang.ownDueCount(context, it) }.getOrNull() } }
    return due
}

@Composable
internal fun BoxScope.MainRail(api: Discovery, lang: String?, at: RailAt, nav: RailNav, focus: MutableMap<String, FocusRequester>, toRows: FocusRequester?,
                               extra: RailItem? = null, onFocused: () -> Unit = {}) {
    val due = rememberDue(api, lang)
    val back = at == RailAt.HOME
    fun item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, here: RailAt?, go: () -> Unit, badge: Int = 0) =
        RailItem(icon, label, badge, selected = here == at, back = back, onClick = if (here == at) ({}) else go)
    Rail(listOfNotNull(
        item(Icons.Default.Home, tr("Home"), RailAt.HOME, nav.onHome),
        item(Icons.Default.VideoLibrary, tr("Library"), RailAt.LIBRARY, nav.onLibrary),
        item(Icons.Default.Explore, tr("Discover"), RailAt.DISCOVER, nav.onDiscover).takeIf { lang == "ja" },
        due?.let { item(Icons.Default.Style, tr("Review"), null, nav.onReview, badge = it) },
        item(Icons.Default.BarChart, tr("Stats"), null, nav.onStats),
        extra,
        item(Icons.Default.Settings, tr("Settings"), null, nav.onSettings),
    ), focus, toRows, Modifier.align(Alignment.TopStart).padding(top = 100.dp), onFocused)
}
